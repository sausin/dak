package app.dak.index.enrich

import app.dak.classify.ClassifierPipeline
import app.dak.classify.CloudClassifier
import app.dak.classify.MessageModel
import app.dak.classify.NoCloudClassifier
import app.dak.classify.SenderId
import app.dak.classify.SenderKind
import app.dak.classify.SenderRegion
import app.dak.classify.TemplateBundle
import app.dak.classify.scam.FakeCreditDetector
import app.dak.classify.scam.HintDirection
import app.dak.classify.scam.ScamLabels
import app.dak.classify.scam.TransactionHint
import app.dak.core.model.Category
import app.dak.core.model.Classification
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.CurrencyTable
import app.dak.finance.parser.TransactionParser
import app.dak.index.scam.ScamContextSource
import app.dak.telephony.region.RegionProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the enricher derives from one message. */
data class Enrichment(
    val classification: Classification,
    val transaction: ExtractedTransaction?,
)

/**
 * The narrow seam between the index and the classification / finance modules. The index only ever calls this
 * interface, so a different pipeline (e.g. a TFLite model) can be swapped in without touching sync code.
 */
interface MessageEnricher {
    /**
     * Version of the templates + model + enrichment logic. Rows indexed with another version are re-enriched by a
     * re-index pass ([app.dak.index.sync.IndexMaintenance.requestReindex]).
     */
    val version: Int

    /**
     * Classifies and parses one message. [allowCloud] permits the opt-in cloud stage (only for single incoming
     * messages, never for backfill). Must be safe to call concurrently.
     */
    suspend fun enrich(message: Message, allowCloud: Boolean): Enrichment

    /**
     * Brand-level fold key of a sender channel (`SenderId.mergeKey` form): channels whose headers belong to one
     * brand in the template bundle share it (e.g. `HDFC` and `HDFCBK` -> `HDFCBK`). Null when unknown. Must be cheap
     * (called for every indexed message) and safe to call concurrently.
     */
    fun brandFoldKey(channel: String): String? = null
}

/**
 * Default [MessageEnricher]: `app.dak.classify.ClassifierPipeline` (templates -> Naive Bayes model -> optional cloud)
 * plus `app.dak.finance.parser.TransactionParser`.
 *
 * [enrich] is lock-free: the pipeline, the parser and the detector are all thread-safe, so the indexer classifies a
 * batch on several threads at once (`IndexIngestor`). The template bundle can be replaced at runtime
 * ([installTemplates]); callers then request a re-index. A call racing an install may still use the old bundle, and
 * its row then carries the old [version], so the re-index picks it up.
 */
class DefaultMessageEnricher(
    private val isContact: (String) -> Boolean,
    private val cloud: CloudClassifier = NoCloudClassifier,
    private val modelLoader: () -> MessageModel = { ClassifierAssets.model },
    initialTemplates: (() -> TemplateBundle) = { ClassifierAssets.defaultTemplates },
    private val scamContext: ScamContextSource = ScamContextSource.None,
    /** Region of the SIM a message arrived on (sender conventions such as India's DLT headers follow it). */
    private val regionFor: (subId: Int) -> RegionProfile = { RegionProfile.UNKNOWN },
) : MessageEnricher {

    private val mutex = Mutex()

    @Volatile
    private var templatesLoader: () -> TemplateBundle = initialTemplates

    @Volatile
    private var state: State? = null

    private class State(
        val templates: TemplateBundle,
        val local: ClassifierPipeline,
        val withCloud: ClassifierPipeline,
        val scam: FakeCreditDetector,
    )

    override val version: Int
        get() = versionOf(ensureState().templates)

    /** Replaces the template bundle (e.g. after a verified OTA update). Returns the new [version]. */
    suspend fun installTemplates(bundle: TemplateBundle): Int = mutex.withLock {
        // Under the same lock as ensureState(), so a state being built from the old bundle cannot land afterwards.
        synchronized(this) {
            templatesLoader = { bundle }
            state = null
        }
        versionOf(ensureState().templates)
    }

    override suspend fun enrich(message: Message, allowCloud: Boolean): Enrichment {
        val s = ensureState()
        // Jev (the opt-in cloud stage) never sees messages from people: a phone-number sender stays on the phone.
        val pipeline = if (allowCloud && cloudEligibleSender(message.address)) s.withCloud else s.local
        val classification = pipeline.classify(message.address, message.body, message.subId)
        val transaction = if (shouldParseTransaction(message.address, classification.category)) {
            // A bare "$" reads as the SIM region's own dollar (CAD, AUD, SGD...), else USD.
            val home = runCatching { regionFor(message.subId).homeCurrency }.getOrNull()
            TransactionParser.parse(message.address, message.body, CurrencyTable.symbolMapFor(home))
        } else {
            null
        }
        // The detector is thread-safe and its context may hit the database.
        return withScamLabels(message, Enrichment(classification, transaction), s.scam)
    }

    /**
     * Adds fake-credit scam labels (`ScamLabels`) to incoming messages; see docs/security/fake-credit-scams.md.
     * Likely fakes keep their parsed transaction (for display) but the ledger skips them (`ScamLabels.excludedFromLedger`).
     */
    private suspend fun withScamLabels(message: Message, base: Enrichment, detector: FakeCreditDetector): Enrichment {
        if (message.box != MessageBox.INBOX) return base
        // India's DLT-based rules apply only to messages on an Indian SIM; null (unknown) means generic rules.
        val region = runCatching { regionFor(message.subId).countryIso }.getOrNull()
        val labels: Set<String> = try {
            when {
                scamContext.isDismissed(message.key) -> setOf(ScamLabels.DISMISSED)
                !detector.isCandidate(message.address, message.body, region) -> emptySet()
                else -> {
                    val hint = base.transaction?.let {
                        TransactionHint(
                            direction = if (it.direction == TransactionDirection.CREDIT) HintDirection.CREDIT else HintDirection.DEBIT,
                            amountMinor = it.amountMinor,
                            last4 = it.last4,
                        )
                    }
                    val recent = if (detector.needsRecentMessages(message.address, message.body, region)) {
                        scamContext.recentMessages(message)
                    } else {
                        emptyList()
                    }
                    val verdict = detector.evaluate(
                        address = message.address,
                        body = message.body,
                        hint = hint,
                        knownAccounts = scamContext.knownAccounts(),
                        isSavedContact = isContact(message.address),
                        recentMessages = recent,
                        dateMillis = message.dateMillis,
                        region = region,
                    )
                    ScamLabels.toLabels(verdict)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            emptySet() // never let a warning heuristic break indexing
        }
        if (labels.isEmpty()) return base
        return base.copy(classification = base.classification.copy(labels = base.classification.labels + labels))
    }

    override fun brandFoldKey(channel: String): String? = ensureState().templates.brandKey(channel)

    private fun senderRegion(subId: Int): SenderRegion = SenderRegion.of(regionFor(subId).countryIso)

    private fun ensureState(): State {
        state?.let { return it }
        synchronized(this) {
            state?.let { return it }
            val templates = templatesLoader()
            val model = modelLoader()
            val created = State(
                templates = templates,
                local = ClassifierPipeline(templates, model, NoCloudClassifier, isContact, regionFor = { subId -> senderRegion(subId) }),
                withCloud = ClassifierPipeline(templates, model, cloud, isContact, regionFor = { subId -> senderRegion(subId) }),
                scam = FakeCreditDetector(templates),
            )
            state = created
            return created
        }
    }

    companion object {
        /**
         * Bump when enrichment logic in this module changes in a way that requires re-indexing.
         * 2: brand-level sender folding, repeat groups, account ids from all visible digits.
         * 3: fake-credit scam labels (`app.dak.classify.scam`).
         * 4: instrument groups (debit / prepaid / loan / UPI-vs-account detection, linked bank accounts); ledger
         *    entries are re-keyed by DB migration 2 -> 3 and refilled by this re-index.
         * 5: canonical amount tokens (`app.dak.search.AmountTokens`) in the FTS text, so every spelling of an amount
         *    matches.
         * 6: courier / order / invoice updates classify as transactions (not promotions or spam, whatever their
         *    "rate us" / feedback links), carrier "now available to take calls" alerts as personal (not spam), and
         *    `-T` / `-S` DLT routes damp promotion / spam model scores (template bundle 2 + `ClassifierPipeline`).
         * 7: single-character account masks (`A/c X5073`), bare `Bal INR` balances, unknown DLT bank headers keep their
         *    own accounts instead of sharing `UNKNOWN`, and the other party's account in a transfer ("credited to
         *    beneficiary A/c XX5632") is never the user's (such confirmations no longer create accounts).
         * 8: role-based transaction parsing (direction cues, own vs counterparty numbers, amount roles; failed, future,
         *    request and statement SMS are no longer transactions) and generic categorisation (template bundle 3:
         *    structure-based logistics / order / bill / booking / service rules, route-aware spam, scheme-less links,
         *    numeric DLT headers).
         * 9: UTS #46 link hosts and UTS #39 sender checks: mixed-script / look-alike (non-ASCII) senders get the
         *    unknown-sender-link and spoofing labels, non-ASCII "DLT headers" are no longer DLT headers.
         * 10: investments (template bundle 4): mutual fund / demat instruments, investment labels, SIP own-account
         *    transfers excluded from spending; ledger schema 5 -> 6 refilled by this re-index.
         */
        const val LOGIC_REVISION = 10

        fun versionOf(templates: TemplateBundle): Int = templates.version * 100 + LOGIC_REVISION

        /**
         * Senders whose messages may go to the cloud stage: businesses (DLT headers, short codes, other alphanumeric
         * IDs). A phone number is a person, and neither their number nor their text ever leaves the phone.
         */
        fun cloudEligibleSender(address: String): Boolean = SenderId.classify(address) != SenderKind.PHONE_NUMBER

        /**
         * Transactions are parsed for messages classified as transactions, and for unclassified messages from
         * non-personal senders (bank headers the templates do not know yet). Never for personal chats.
         */
        fun shouldParseTransaction(address: String, category: Category): Boolean = when (category) {
            Category.TRANSACTION -> true
            Category.UNKNOWN -> SenderId.classify(address).let { it == SenderKind.DLT_HEADER || it == SenderKind.ALPHANUMERIC }
            else -> false
        }
    }
}
