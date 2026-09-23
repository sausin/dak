package app.dak.index.sql

import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.index.InboxTab

/**
 * Pure SQL for the conversation list: messages grouped by `conversationId` (so merge groups collapse
 * `VM-HDFCBK` / `JD-HDFCBK` into one row), filtered by [InboxTab] and optionally by SIM, pinned first then newest.
 *
 * Category tabs filter at the message level, so the HDFC conversation shows in both Transactions (with its latest
 * transaction as the snippet) and OTP (with its latest OTP).
 *
 * Result columns (see `ConversationRow`): conversationId, dateMillis, kind, providerId, threadId, address,
 * mergeKey, canonicalSender, snippet, category, box, hasAttachment, unreadCount, messageCount, subIds, threadIds,
 * pinned, muted, archived, starred, groupName, repeatGroup.
 */
internal object ConversationSqlBuilder {

    fun page(tab: InboxTab, subId: Int?, limit: Int, offset: Int): SqlQuery {
        val (where, args) = where(tab, subId)
        // Exactly one MAX() aggregate, so the bare message columns come from each conversation's newest message.
        val sql = buildString {
            append("SELECT m.conversationId AS conversationId, MAX(m.dateMillis) AS dateMillis, ")
            append("m.kind AS kind, m.providerId AS providerId, m.threadId AS threadId, m.address AS address, ")
            append("m.mergeKey AS mergeKey, m.canonicalSender AS canonicalSender, m.bodyPreview AS snippet, ")
            append("m.category AS category, m.box AS box, m.hasAttachment AS hasAttachment, m.repeatGroup AS repeatGroup, ")
            append("SUM(CASE WHEN m.read = 0 AND m.box = '${MessageBox.INBOX.name}' THEN 1 ELSE 0 END) AS unreadCount, ")
            append("COUNT(*) AS messageCount, ")
            append("GROUP_CONCAT(DISTINCT m.subId) AS subIds, ")
            append("GROUP_CONCAT(DISTINCT m.threadId) AS threadIds, ")
            append("COALESCE(p.pinned, 0) AS pinned, COALESCE(p.muted, 0) AS muted, ")
            append("COALESCE(p.archived, 0) AS archived, COALESCE(p.starred, 0) AS starred, ")
            append("g.displayName AS groupName ")
            append(FROM)
            append("WHERE ").append(where).append(' ')
            append("GROUP BY m.conversationId ")
            append("ORDER BY pinned DESC, dateMillis DESC, conversationId ASC ")
            append("LIMIT ? OFFSET ?")
        }
        return SqlQuery(sql, args + listOf(limit.toLong(), offset.toLong()))
    }

    /** Number of conversations [page] can return. Result column: `n`. */
    fun count(tab: InboxTab, subId: Int?): SqlQuery {
        val (where, args) = where(tab, subId)
        return SqlQuery(
            "SELECT COUNT(*) AS n FROM (SELECT m.conversationId $FROM WHERE $where GROUP BY m.conversationId)",
            args,
        )
    }

    private const val FROM =
        "FROM ${Tables.MESSAGE} m " +
            "LEFT JOIN ${Tables.PREFS} p ON p.conversationId = m.conversationId " +
            "LEFT JOIN ${Tables.MERGE_GROUP} g ON g.mergeKey = m.mergeKey AND m.conversationId = ('m:' || m.mergeKey) "

    private const val NOT_ARCHIVED = "COALESCE(p.archived, 0) = 0 AND m.archived = 0"

    private fun where(tab: InboxTab, subId: Int?): Pair<String, List<Any>> {
        val parts = ArrayList<String>()
        val args = ArrayList<Any>()
        when (tab) {
            InboxTab.ALL -> {
                parts += NOT_ARCHIVED
                parts += "m.category <> ?"; args += Category.SPAM.name
            }
            InboxTab.PERSONAL, InboxTab.TRANSACTION, InboxTab.OTP, InboxTab.PROMOTION -> {
                parts += NOT_ARCHIVED
                parts += "m.category = ?"; args += categoryOf(tab).name
            }
            InboxTab.SPAM -> {
                parts += "m.category = ?"; args += Category.SPAM.name
            }
            InboxTab.ARCHIVED -> parts += "(COALESCE(p.archived, 0) = 1 OR m.archived = 1)"
            InboxTab.STARRED -> parts += "(COALESCE(p.starred, 0) = 1 OR m.starred = 1)"
        }
        if (subId != null) {
            parts += "m.subId = ?"; args += subId.toLong()
        }
        return parts.joinToString(" AND ") to args
    }

    /** The message category a category tab shows; [InboxTab.ALL] and folder tabs have none. */
    fun categoryOf(tab: InboxTab): Category = when (tab) {
        InboxTab.PERSONAL -> Category.PERSONAL
        InboxTab.TRANSACTION -> Category.TRANSACTION
        InboxTab.OTP -> Category.OTP
        InboxTab.PROMOTION -> Category.PROMOTION
        InboxTab.SPAM -> Category.SPAM
        else -> throw IllegalArgumentException("$tab has no single category")
    }
}
