package app.dak.index.enrich

import app.dak.classify.ClassifierPipeline
import app.dak.classify.CloudClassifier
import app.dak.classify.MessageModel
import app.dak.classify.NoCloudClassifier
import app.dak.classify.SenderId
import app.dak.classify.SenderKind
import app.dak.classify.TemplateBundle
import app.dak.core.model.Category
import app.dak.core.model.Classification
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.Message
import app.dak.finance.parser.TransactionParser
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
 * The pipeline keeps an unsynchronized regex cache, so calls are serialized with a mutex. The template bundle can
 * be replaced at runtime ([installTemplates]); callers then request a re-index.
 */
class DefaultMessageEnricher(
    private val isContact: (String) -> Boolean,
    private val cloud: CloudClassifier = NoCloudClassifier,
    private val modelLoader: () -> MessageModel = { ClassifierAssets.model },
    initialTemplates: (() -> TemplateBundle) = { ClassifierAssets.defaultTemplates },
) : MessageEnricher {

    private val mutex = Mutex()
    private var templatesLoader: () -> TemplateBundle = initialTemplates

    @Volatile
    private var state: State? = null

    private class State(val templates: TemplateBundle, val local: ClassifierPipeline, val withCloud: ClassifierPipeline)

    override val version: Int
        get() = versionOf(ensureState().templates)

    /** Replaces the template bundle (e.g. after a verified OTA update). Returns the new [version]. */
    suspend fun installTemplates(bundle: TemplateBundle): Int = mutex.withLock {
        templatesLoader = { bundle }
        state = null
        versionOf(ensureState().templates)
    }

    override suspend fun enrich(message: Message, allowCloud: Boolean): Enrichment = mutex.withLock {
        val s = ensureState()
        val pipeline = if (allowCloud) s.withCloud else s.local
        val classification = pipeline.classify(message.address, message.body, message.subId)
        val transaction = if (shouldParseTransaction(message.address, classification.category)) {
            TransactionParser.parse(message.address, message.body)
        } else {
            null
        }
        Enrichment(classification, transaction)
    }

    override fun brandFoldKey(channel: String): String? = ensureState().templates.brandKey(channel)

    private fun ensureState(): State {
        state?.let { return it }
        synchronized(this) {
            state?.let { return it }
            val templates = templatesLoader()
            val model = modelLoader()
            val created = State(
                templates = templates,
                local = ClassifierPipeline(templates, model, NoCloudClassifier, isContact),
                withCloud = ClassifierPipeline(templates, model, cloud, isContact),
            )
            state = created
            return created
        }
    }

    companion object {
        /**
         * Bump when enrichment logic in this module changes in a way that requires re-indexing.
         * 2: brand-level sender folding, repeat groups, account ids from all visible digits.
         */
        const val LOGIC_REVISION = 2

        fun versionOf(templates: TemplateBundle): Int = templates.version * 100 + LOGIC_REVISION

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
