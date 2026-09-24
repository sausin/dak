package app.dak.safety

import app.dak.classify.scam.FakeCreditDetector
import app.dak.classify.scam.HintDirection
import app.dak.classify.scam.ScamLabels
import app.dak.classify.scam.ScamVerdict
import app.dak.classify.scam.TransactionHint
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.TransactionDirection
import app.dak.di.AndroidContactLookup
import app.dak.finance.money.CurrencyTable
import app.dak.finance.parser.TransactionParser
import app.dak.index.enrich.ClassifierAssets
import app.dak.index.scam.IndexScamContext
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.region.RegionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fake-credit scam check for paths that run before the index (the notification, an automation that could not wait
 * for the index), and the "Warn about fake credit alerts" switch for every warning surface (notification, bubble
 * banner, inbox chip, entity sheet). On-device rules only (`app.dak.classify.scam.FakeCreditDetector`); see
 * docs/security/fake-credit-scams.md.
 *
 * It gives the detector the same context the index does ([IndexScamContext]: the parsed amount and account, the
 * ledger's known accounts, the last 48 hours from this sender), so a notification and the thread agree on a
 * verdict. Context lookups are bounded ([CONTEXT_TIMEOUT_MILLIS]); on timeout the message alone is judged.
 */
@Singleton
class FakeCreditCheck @Inject constructor(
    private val contacts: AndroidContactLookup,
    private val settings: SettingsStore,
    private val regions: RegionProvider,
    private val scamContext: IndexScamContext,
) {
    private val detector: FakeCreditDetector by lazy { FakeCreditDetector(ClassifierAssets.defaultTemplates) }

    /** True when the user wants fake-credit warnings shown (default on). */
    fun warningsEnabled(): Boolean = settings.get(DakSettings.fakeCreditWarnings)

    /** Verdict to *show* for an incoming [message]; [ScamVerdict.None] when warnings are off or on any error. */
    suspend fun verdictFor(message: Message): ScamVerdict =
        if (!warningsEnabled()) ScamVerdict.None else evaluate(message)

    /**
     * Verdict for an incoming [message] whatever the warnings switch says: for safety decisions that must not depend
     * on a display preference (e.g. never auto-forwarding a likely fake). [ScamVerdict.None] on any error.
     */
    suspend fun evaluate(message: Message): ScamVerdict {
        if (message.box != MessageBox.INBOX) return ScamVerdict.None
        return try {
            if (scamContext.isDismissed(message.key)) return ScamVerdict.None
            // India's DLT rules only for messages on an Indian SIM; null (unknown region) means generic rules.
            val profile = regions.forSubId(message.subId)
            val region = profile.countryIso
            if (!detector.isCandidate(message.address, message.body, region)) return ScamVerdict.None
            val known = withTimeoutOrNull(CONTEXT_TIMEOUT_MILLIS) { scamContext.knownAccounts() }.orEmpty()
            val recent = if (detector.needsRecentMessages(message.address, message.body, region)) {
                withTimeoutOrNull(CONTEXT_TIMEOUT_MILLIS) { scamContext.recentMessages(message) }.orEmpty()
            } else {
                emptyList()
            }
            detector.evaluate(
                address = message.address,
                body = message.body,
                hint = hintOf(message, profile.homeCurrency),
                knownAccounts = known,
                isSavedContact = contacts.isContact(message.address),
                recentMessages = recent,
                dateMillis = message.dateMillis,
                region = region,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            ScamVerdict.None
        }
    }

    /** The warning to show for an indexed message's [labels], or null (none, dismissed, or warnings off). */
    fun verdictFromLabels(labels: Set<String>): ScamVerdict? =
        if (warningsEnabled()) ScamLabels.fromLabels(labels) else null

    /** What the SMS itself claims (credit/debit, amount, account digits), as the index would parse it. */
    private fun hintOf(message: Message, homeCurrency: String?): TransactionHint? {
        val parsed = TransactionParser.parse(message.address, message.body, CurrencyTable.symbolMapFor(homeCurrency)) ?: return null
        return TransactionHint(
            direction = if (parsed.direction == TransactionDirection.CREDIT) HintDirection.CREDIT else HintDirection.DEBIT,
            amountMinor = parsed.amountMinor,
            last4 = parsed.last4,
        )
    }

    private companion object {
        /** Per lookup; the notification must be posted well within the incoming-handler budget. */
        const val CONTEXT_TIMEOUT_MILLIS = 400L
    }
}
