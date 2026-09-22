package app.dak.index.repo

import app.dak.core.model.Category
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.index.ContactLookup
import app.dak.index.ConversationSummary
import app.dak.index.MessageItem
import app.dak.index.OtpItem
import app.dak.index.TransactionItem
import app.dak.index.db.dao.ConversationRow
import app.dak.index.db.entity.ConversationPrefs
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.enrich.ConversationIds
import app.dak.index.sync.IndexRowMapper
import app.dak.telephony.ProviderThread

/** Mapping from index rows / provider objects to the UI types. */
internal object Mappers {

    fun messageItem(row: IndexedMessage, otpRepeatedLater: Boolean = false): MessageItem = MessageItem(
        key = MessageKey(row.kind, row.providerId),
        conversationId = row.conversationId,
        threadId = row.threadId,
        address = row.address,
        body = row.body,
        dateMillis = row.dateMillis,
        box = row.box,
        read = row.read,
        subId = row.subId,
        attachments = IndexRowMapper.attachments(row.attachmentsJson),
        category = row.category,
        confidence = row.confidence,
        canonicalSender = row.canonicalSender,
        labels = row.labels,
        otp = row.otpCode?.let { OtpItem(it, row.otpConsumedBy, row.webOtpDomain, otpRepeatedLater) },
        transaction = if (row.amountMinor != null && row.currency != null && row.direction != null) {
            TransactionItem(row.direction, row.amountMinor, row.currency, row.instrumentLast4, row.merchant, row.accountId)
        } else {
            null
        },
        hasLink = row.hasLink,
        starred = row.starred,
        archived = row.archived,
        enriched = true,
    )

    /** A message read straight from the provider (not indexed yet): no enrichment. */
    fun providerMessageItem(message: Message): MessageItem = MessageItem(
        key = message.key,
        conversationId = ConversationIds.forThread(message.threadId),
        threadId = message.threadId,
        address = message.address,
        body = message.body,
        dateMillis = message.dateMillis,
        box = message.box,
        read = message.read,
        subId = message.subId,
        attachments = message.attachments,
        category = Category.UNKNOWN,
        confidence = 0f,
        canonicalSender = null,
        labels = emptySet(),
        otp = null,
        transaction = null,
        hasLink = false,
        starred = false,
        archived = false,
        enriched = false,
    )

    fun conversationSummary(row: ConversationRow, contacts: ContactLookup): ConversationSummary {
        val merged = ConversationIds.isMergeGroup(row.conversationId)
        val title = if (merged) {
            row.groupName ?: row.canonicalSender ?: row.address
        } else {
            val addresses = if (row.kind == MessageKind.MMS) splitAddresses(row.address) else listOf(row.address)
            titleForAddresses(addresses, contacts) ?: row.canonicalSender ?: row.address
        }
        return ConversationSummary(
            conversationId = row.conversationId,
            title = title,
            address = row.address,
            snippet = row.snippet,
            dateMillis = row.dateMillis,
            unreadCount = row.unreadCount,
            messageCount = row.messageCount,
            category = row.category,
            subIds = parseInts(row.subIds),
            threadIds = parseLongs(row.threadIds),
            isMergedSender = merged,
            pinned = row.pinned,
            muted = row.muted,
            archived = row.archived,
            starred = row.starred,
            lastBox = row.box,
            hasAttachment = row.hasAttachment,
            enriched = true,
        )
    }

    fun providerSummary(thread: ProviderThread, prefs: ConversationPrefs?, contacts: ContactLookup): ConversationSummary {
        val address = thread.addresses.joinToString(" ")
        return ConversationSummary(
            conversationId = ConversationIds.forThread(thread.threadId),
            title = titleForAddresses(thread.addresses, contacts) ?: address,
            address = address,
            snippet = IndexRowMapper.preview(thread.snippet),
            dateMillis = thread.dateMillis,
            unreadCount = thread.unreadCount,
            messageCount = thread.messageCount,
            category = Category.UNKNOWN,
            subIds = emptySet(),
            threadIds = setOf(thread.threadId),
            isMergedSender = false,
            pinned = prefs?.pinned ?: false,
            muted = prefs?.muted ?: false,
            archived = prefs?.archived ?: false,
            starred = prefs?.starred ?: false,
            lastBox = MessageBox.INBOX,
            hasAttachment = false,
            enriched = false,
        )
    }

    /** Contact names for the given addresses joined with ", ", or null if none resolves. */
    fun titleForAddresses(addresses: List<String>, contacts: ContactLookup): String? {
        if (addresses.isEmpty()) return null
        val names = addresses.map { contacts.displayName(it) }
        if (names.all { it == null }) return null
        return addresses.indices.joinToString(", ") { names[it] ?: addresses[it] }
    }

    /** MMS rows store recipients space-joined (SMS rows hold a single address and are never split). */
    fun splitAddresses(address: String): List<String> =
        address.split(' ', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(address) }

    fun parseInts(csv: String?): Set<Int> =
        csv?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()

    fun parseLongs(csv: String?): Set<Long> =
        csv?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()
}
