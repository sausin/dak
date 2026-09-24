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
 * pinned, muted, archived, starred, incognito, groupName, repeatGroup, deliveryStatus.
 */
internal object ConversationSqlBuilder {

    /**
     * One page of conversations, in two steps within one statement (docs/performance.md, "Ledger and inbox"):
     *
     * 1. `c`: filter and aggregate per conversation, sort and cut the page. It reads only columns of the covering
     *    index `(conversationId, dateMillis, category, subId, archived, starred, read, box, threadId)`, never the
     *    rows themselves (bodies, FTS text, JSON), and carries the rowid of each conversation's newest matching
     *    message: with exactly one MAX() aggregate, SQLite takes the bare `m.rowid` from the row holding the maximum.
     * 2. Only the page's newest messages (at most [limit] rows) are then read for their display columns.
     */
    fun page(tab: InboxTab, subId: Int?, limit: Int, offset: Int): SqlQuery {
        val (where, args) = where(tab, subId)
        val sql = buildString {
            append("SELECT c.cid AS conversationId, c.newest AS dateMillis, ")
            append("n.kind AS kind, n.providerId AS providerId, n.threadId AS threadId, n.address AS address, ")
            append("n.mergeKey AS mergeKey, n.canonicalSender AS canonicalSender, n.bodyPreview AS snippet, ")
            append("n.category AS category, n.box AS box, n.hasAttachment AS hasAttachment, n.repeatGroup AS repeatGroup, ")
            append("n.deliveryStatus AS deliveryStatus, ")
            append("c.unread AS unreadCount, c.total AS messageCount, c.subs AS subIds, c.threads AS threadIds, ")
            append("c.pin AS pinned, c.mute AS muted, c.arch AS archived, c.star AS starred, c.incog AS incognito, ")
            append("g.displayName AS groupName ")
            append("FROM (")
            // Exactly one MAX() aggregate, so the bare m.rowid comes from each conversation's newest matching message.
            append("SELECT m.conversationId AS cid, MAX(m.dateMillis) AS newest, m.rowid AS rid, ")
            append("SUM(CASE WHEN m.read = 0 AND m.box = '${MessageBox.INBOX.name}' THEN 1 ELSE 0 END) AS unread, ")
            append("COUNT(*) AS total, GROUP_CONCAT(DISTINCT m.subId) AS subs, GROUP_CONCAT(DISTINCT m.threadId) AS threads, ")
            append("COALESCE(p.pinned, 0) AS pin, COALESCE(p.muted, 0) AS mute, COALESCE(p.archived, 0) AS arch, ")
            append("COALESCE(p.starred, 0) AS star, (p.incognitoSince IS NOT NULL) AS incog ")
            append(FROM)
            append("WHERE ").append(where).append(' ')
            append("GROUP BY m.conversationId ")
            append("ORDER BY pin DESC, newest DESC, cid ASC ")
            append("LIMIT ? OFFSET ?")
            append(") c ")
            append("JOIN ${Tables.MESSAGE} n ON n.rowid = c.rid ")
            append("LEFT JOIN ${Tables.MERGE_GROUP} g ON g.mergeKey = n.mergeKey AND n.conversationId = ('m:' || n.mergeKey) ")
            append("ORDER BY pinned DESC, dateMillis DESC, conversationId ASC")
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

    /** The messages and their conversation prefs (the only tables the filters read). */
    private const val FROM =
        "FROM ${Tables.MESSAGE} m " +
            "LEFT JOIN ${Tables.PREFS} p ON p.conversationId = m.conversationId "

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
