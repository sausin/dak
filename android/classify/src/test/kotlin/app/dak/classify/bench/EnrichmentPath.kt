package app.dak.classify.bench

import app.dak.classify.ClassifierPipeline
import app.dak.classify.LinkPresence
import app.dak.classify.NaiveBayesModel
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
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import app.dak.finance.parser.TransactionParser
import app.dak.search.TextNormalizer

/** Everything the index derives from one message on the CPU (what `IndexRowMapper.build` stores). */
data class EnrichedRow(
    val classification: Classification,
    val transaction: ExtractedTransaction?,
    val accountId: String?,
    val hasLink: Boolean,
    val searchText: String,
    val searchSender: String,
)

/**
 * The CPU part of `:core-index`'s write path, reproduced on the JVM for the benchmark: `DefaultMessageEnricher.enrich`
 * (templates -> model via [ClassifierPipeline], then `TransactionParser`, then the fake-credit detector for incoming
 * messages) followed by the per-row derivations of `IndexRowMapper.build` (link flag, account id, FTS text).
 * Database and provider I/O are not included; they are measured on devices, not here.
 *
 * Thread-safe when [pipeline] is (it is).
 */
class EnrichmentPath(
    val pipeline: ClassifierPipeline,
    private val detector: FakeCreditDetector,
    private val isContact: (String) -> Boolean,
) {

    suspend fun enrich(message: CorpusMessage): EnrichedRow {
        val classification = pipeline.classify(message.address, message.body, message.subId)
        val transaction = if (shouldParseTransaction(message.address, classification.category)) {
            TransactionParser.parse(message.address, message.body)
        } else {
            null
        }
        val labels = if (message.incoming) scamLabels(message, transaction) else emptySet()
        val finalClassification = if (labels.isEmpty()) classification else classification.copy(labels = classification.labels + labels)
        return EnrichedRow(
            classification = finalClassification,
            transaction = transaction,
            accountId = transaction?.let { Account.idOf(it) },
            hasLink = LinkPresence.containsLink(message.body),
            searchText = TextNormalizer.normalize(message.body),
            searchSender = TextNormalizer.normalize(listOfNotNull(message.address, finalClassification.canonicalSender).joinToString(" ")),
        )
    }

    private fun scamLabels(message: CorpusMessage, transaction: ExtractedTransaction?): Set<String> {
        if (!detector.isCandidate(message.address, message.body)) return emptySet()
        val hint = transaction?.let {
            TransactionHint(
                direction = if (it.direction == TransactionDirection.CREDIT) HintDirection.CREDIT else HintDirection.DEBIT,
                amountMinor = it.amountMinor,
                last4 = it.last4,
            )
        }
        detector.needsRecentMessages(message.address, message.body)
        val verdict = detector.evaluate(
            address = message.address,
            body = message.body,
            hint = hint,
            isSavedContact = isContact(message.address),
            dateMillis = 1_758_600_000_000L,
        )
        return ScamLabels.toLabels(verdict)
    }

    companion object {
        /** Same rule as `DefaultMessageEnricher.shouldParseTransaction`. */
        fun shouldParseTransaction(address: String, category: Category): Boolean = when (category) {
            Category.TRANSACTION -> true
            Category.UNKNOWN -> SenderId.classify(address).let { it == SenderKind.DLT_HEADER || it == SenderKind.ALPHANUMERIC }
            else -> false
        }

        /** A deterministic stand-in for the contacts lookup: roughly one phone number in three is "saved". */
        val fakeContacts: (String) -> Boolean = { address -> address.startsWith("+91") && address.last().code % 3 == 0 }

        fun create(
            templates: TemplateBundle = TemplateBundle.loadDefault(),
            model: NaiveBayesModel = NaiveBayesModel.loadDefault(),
            pipelineFactory: (TemplateBundle, NaiveBayesModel) -> ClassifierPipeline = { t, m ->
                ClassifierPipeline(t, m, contactLookup = fakeContacts, regionFor = { SenderRegion.INDIA })
            },
        ): EnrichmentPath = EnrichmentPath(pipelineFactory(templates, model), FakeCreditDetector(templates), fakeContacts)
    }
}
