package app.dak.index.sql

import app.dak.index.InboxTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationSqlBuilderTest {

    @Test
    fun allTabExcludesSpamAndArchived() {
        val q = ConversationSqlBuilder.page(InboxTab.ALL, null, 30, 60)
        assertTrue(q.sql.contains("COALESCE(p.archived, 0) = 0 AND m.archived = 0 AND m.category <> ?"))
        assertTrue(q.sql.contains("GROUP BY m.conversationId"))
        assertTrue(q.sql.contains("ORDER BY pinned DESC, dateMillis DESC"))
        assertEquals(listOf<Any>("SPAM", 30L, 60L), q.args)
    }

    @Test
    fun categoryTabAndSimFilter() {
        val q = ConversationSqlBuilder.page(InboxTab.OTP, 3, 10, 0)
        assertTrue(q.sql.contains("m.category = ? AND m.subId = ?"))
        assertEquals(listOf<Any>("OTP", 3L, 10L, 0L), q.args)
    }

    @Test
    fun folderTabs() {
        assertTrue(ConversationSqlBuilder.page(InboxTab.ARCHIVED, null, 1, 0).sql.contains("(COALESCE(p.archived, 0) = 1 OR m.archived = 1)"))
        assertTrue(ConversationSqlBuilder.page(InboxTab.STARRED, null, 1, 0).sql.contains("(COALESCE(p.starred, 0) = 1 OR m.starred = 1)"))
        assertEquals(listOf<Any>("SPAM", 1L, 0L), ConversationSqlBuilder.page(InboxTab.SPAM, null, 1, 0).args)
    }

    @Test
    fun selectsEveryConversationRowColumnExactlyOnce() {
        val sql = ConversationSqlBuilder.page(InboxTab.ALL, null, 1, 0).sql
        val columns = listOf(
            "conversationId", "dateMillis", "kind", "providerId", "threadId", "address", "mergeKey",
            "canonicalSender", "snippet", "category", "box", "hasAttachment", "unreadCount", "messageCount",
            "subIds", "threadIds", "pinned", "muted", "archived", "starred", "groupName",
        )
        for (c in columns) {
            assertEquals(1, Regex("AS $c\\b").findAll(sql).count(), "column $c")
        }
        // Exactly one MAX() so SQLite's bare-column rule picks the newest message's columns.
        assertEquals(1, Regex("MAX\\(").findAll(sql).count())
    }

    @Test
    fun countWrapsGroupedQuery() {
        val q = ConversationSqlBuilder.count(InboxTab.PERSONAL, null)
        assertTrue(q.sql.startsWith("SELECT COUNT(*) AS n FROM (SELECT m.conversationId FROM indexed_message m"))
        assertEquals(listOf<Any>("PERSONAL"), q.args)
    }
}
