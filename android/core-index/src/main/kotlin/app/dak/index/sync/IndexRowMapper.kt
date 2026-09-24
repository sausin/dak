package app.dak.index.sync

import app.dak.core.model.Attachment
import app.dak.core.model.DeliveryStatus
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InvestmentAction
import app.dak.core.model.Message
import app.dak.core.model.MessageKey
import app.dak.finance.ledger.Account
import app.dak.index.db.IndexJson
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.MessageFlag
import app.dak.index.enrich.Enrichment
import app.dak.index.enrich.GroupingRules
import app.dak.index.enrich.LinkDetector
import app.dak.index.enrich.SenderGrouping
import app.dak.finance.money.MoneyParser
import app.dak.search.AmountTokens
import app.dak.search.TextNormalizer
import kotlinx.serialization.builtins.ListSerializer

/** Pure mapping between provider [Message]s, enrichment results and index rows. */
internal object IndexRowMapper {

    private val attachmentsSerializer = ListSerializer(Attachment.serializer())

    /** Builds a fully enriched row. [repeatGroup] carries over the existing row's repeat group, if any. */
    fun build(
        message: Message,
        enrichment: Enrichment,
        rules: GroupingRules,
        flag: MessageFlag?,
        consumedBy: String?,
        version: Int,
        nowMillis: Long,
        repeatGroup: String? = null,
    ): IndexedMessage {
        val c = enrichment.classification
        val txn = enrichment.transaction
        // A valuation statement moves no money: the ledger reads it (transactionJson + accountId) for the account's
        // current value, but the message shows no amount chip and matches no amount search or automation.
        val moved = txn?.takeIf { it.investmentAction != InvestmentAction.VALUATION }
        val grouping = SenderGrouping.resolve(message.address, message.threadId, rules)
        return IndexedMessage(
            kind = message.kind,
            providerId = message.providerId,
            threadId = message.threadId,
            subId = message.subId,
            address = message.address,
            mergeKey = grouping.mergeKey,
            conversationId = grouping.conversationId,
            dateMillis = message.dateMillis,
            box = message.box,
            read = message.read,
            seen = message.seen,
            category = c.category,
            confidence = c.confidence,
            classifierSource = c.source,
            canonicalSender = c.canonicalSender,
            labels = c.labels,
            otpCode = c.otp?.code,
            otpConsumedBy = consumedBy,
            retrieverHash = c.otp?.retrieverHash,
            webOtpDomain = c.otp?.webOtpDomain,
            hasLink = LinkDetector.containsLink(message.body),
            hasAttachment = message.attachments.isNotEmpty(),
            amountMinor = moved?.amountMinor,
            currency = moved?.currency,
            direction = moved?.direction,
            instrumentLast4 = txn?.last4,
            merchant = moved?.merchant,
            accountId = txn?.let { Account.idOf(it) },
            transactionJson = txn?.let { IndexJson.json.encodeToString(ExtractedTransaction.serializer(), it) },
            starred = flag?.starred ?: false,
            archived = flag?.archived ?: false,
            indexedAt = nowMillis,
            body = message.body,
            bodyPreview = preview(message.body),
            attachmentsJson = IndexJson.json.encodeToString(attachmentsSerializer, message.attachments),
            templateVersion = version,
            searchText = searchText(message.body),
            searchSender = searchSender(message.address, c.canonicalSender),
            repeatGroup = repeatGroup,
            deliveryStatus = message.deliveryStatus.code,
            deliveredAtMillis = message.deliveredAtMillis,
        )
    }

    /**
     * Refreshes only the provider-owned, volatile fields (box, read state, delivery report, SIM, thread, attachments) of an
     * already-enriched row whose body did not change, keeping its classification.
     */
    fun refresh(existing: IndexedMessage, message: Message, rules: GroupingRules, nowMillis: Long): IndexedMessage {
        val grouping = SenderGrouping.resolve(message.address, message.threadId, rules)
        return existing.copy(
            threadId = message.threadId,
            subId = message.subId,
            address = message.address,
            mergeKey = grouping.mergeKey,
            conversationId = grouping.conversationId,
            dateMillis = message.dateMillis,
            box = message.box,
            read = message.read,
            seen = message.seen,
            hasAttachment = message.attachments.isNotEmpty(),
            attachmentsJson = IndexJson.json.encodeToString(attachmentsSerializer, message.attachments),
            indexedAt = nowMillis,
            deliveryStatus = message.deliveryStatus.code,
            deliveredAtMillis = message.deliveredAtMillis,
        )
    }

    /** True when [existing] can be refreshed instead of re-enriched. */
    fun canRefresh(existing: IndexedMessage, message: Message, version: Int): Boolean =
        existing.templateVersion == version && existing.body == message.body && existing.address == message.address

    /** Rebuilds the provider [Message] snapshot held by a row (for restore, backup, or provider-less display). */
    fun toMessage(row: IndexedMessage): Message = Message(
        providerId = row.providerId,
        kind = row.kind,
        threadId = row.threadId,
        address = row.address,
        body = row.body,
        dateMillis = row.dateMillis,
        subId = row.subId,
        box = row.box,
        read = row.read,
        seen = row.seen,
        attachments = attachments(row.attachmentsJson),
        deliveryStatus = DeliveryStatus.fromCode(row.deliveryStatus),
        deliveredAtMillis = row.deliveredAtMillis,
    )

    fun attachments(json: String): List<Attachment> =
        if (json.isEmpty()) emptyList()
        else runCatching { IndexJson.json.decodeFromString(attachmentsSerializer, json) }.getOrDefault(emptyList())

    fun transaction(row: IndexedMessage): ExtractedTransaction? = transaction(row.transactionJson)

    /** Decodes a row's `transactionJson`; null when absent or unreadable. */
    fun transaction(json: String?): ExtractedTransaction? = json?.let {
        runCatching { IndexJson.json.decodeFromString(ExtractedTransaction.serializer(), it) }.getOrNull()
    }

    fun key(row: IndexedMessage): MessageKey = MessageKey(row.kind, row.providerId)

    fun preview(body: String): String {
        val collapsed = body.replace(WHITESPACE, " ").trim()
        return if (collapsed.length <= IndexedMessage.PREVIEW_LENGTH) collapsed else collapsed.take(IndexedMessage.PREVIEW_LENGTH)
    }

    /**
     * The FTS text of a body: the normalized body plus canonical amount tokens (`amt50000000 amtinr50000000`, see
     * [AmountTokens]) for every amount it mentions, so any spelling of an amount finds it. Search-only: the
     * displayed body, previews and highlights always come from the raw body.
     */
    fun searchText(body: String): String {
        val normalized = TextNormalizer.normalize(body)
        val amounts = try {
            MoneyParser.findAllForSearch(body).mapNotNull { m -> m.hundredths?.let { it to m.currency } }
        } catch (e: RuntimeException) {
            emptyList() // amount tokens are a search nicety; never let them break indexing
        }
        val tokens = AmountTokens.indexText(amounts)
        return if (tokens.isEmpty()) normalized else "$normalized $tokens"
    }

    fun searchSender(address: String, canonicalSender: String?): String =
        TextNormalizer.normalize(listOfNotNull(address, canonicalSender).joinToString(" "))

    private val WHITESPACE = Regex("\\s+")
}
