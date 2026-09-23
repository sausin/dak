package app.dak.index.sql

import app.dak.core.model.Category
import app.dak.index.SearchSort
import app.dak.search.Filter
import app.dak.search.Folder
import app.dak.search.SearchQuery
import app.dak.search.TextExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchSqlBuilderTest {

    private val builder = SearchSqlBuilder()
    private val none = SearchSqlBuilder.Resolved()

    private fun placeholders(sql: String) = sql.count { it == '?' }

    @Test
    fun textOnlyUsesSingleFtsSubqueryAndGroupsByConversation() {
        val q = SearchQuery(TextExpr.And(TextExpr.Term("amazon"), TextExpr.Term("refund")))
        val sql = builder.messages(q, SearchSort.RECENT, none, limit = 20, offset = 40)
        assertTrue(sql.sql.contains("m.rowid IN (SELECT rowid FROM message_fts WHERE message_fts MATCH ?)"))
        assertTrue(sql.sql.contains("GROUP BY m.conversationId"))
        assertTrue(sql.sql.contains("MAX(m.dateMillis) AS sortValue"))
        assertTrue(sql.sql.endsWith("LIMIT ? OFFSET ?"))
        assertEquals(listOf<Any>("amazon* refund*", 20L, 40L), sql.args)
        assertEquals(placeholders(sql.sql), sql.args.size)
    }

    @Test
    fun structuredFiltersBecomeColumnPredicates() {
        val q = SearchQuery(
            null,
            listOf(
                Filter.CategoryIs(Category.TRANSACTION),
                Filter.AmountRange(50001, null),
                Filter.DateRange(1000L, 2000L),
                Filter.HasOtp,
                Filter.HasLink,
                Filter.HasAttachment,
                Filter.IsUnread,
                Filter.IsStarred,
                Filter.InFolder(Folder.ARCHIVE),
            ),
        )
        val sql = builder.messages(q, SearchSort.AMOUNT, none, 10, 0)
        with(sql.sql) {
            assertTrue(contains("(m.category = ?)"))
            assertTrue(contains("m.amountMinor IS NOT NULL AND m.amountMinor >= ?"))
            assertTrue(contains("m.dateMillis >= ? AND m.dateMillis < ?"))
            assertTrue(contains("(m.otpCode IS NOT NULL)"))
            assertTrue(contains("(m.hasLink = 1)"))
            assertTrue(contains("(m.hasAttachment = 1)"))
            assertTrue(contains("m.read = 0 AND m.box = ?"))
            assertTrue(contains("m.starred = 1 OR COALESCE(p.starred, 0) = 1"))
            assertTrue(contains("COALESCE(p.archived, 0) = 1 OR m.archived = 1"))
            assertTrue(contains("MAX(COALESCE(m.amountMinor, -1)) AS sortValue"))
            assertFalse(contains("MATCH"))
        }
        assertEquals(listOf<Any>("TRANSACTION", 50001L, 1000L, 2000L, "INBOX", 10L, 0L), sql.args)
        assertEquals(placeholders(sql.sql), sql.args.size)
    }

    @Test
    fun negatedFilterIsNullSafe() {
        val q = SearchQuery(null, listOf(Filter.Not(Filter.AmountRange(null, 9999))))
        val sql = builder.messages(q, SearchSort.RECENT, none, 10, 0)
        assertTrue(sql.sql.contains("NOT COALESCE((m.amountMinor IS NOT NULL AND m.amountMinor <= ?), 0)"))
    }

    @Test
    fun fromUsesResolvedMergeKeysAndLikes() {
        val q = SearchQuery(null, listOf(Filter.From("hdfc")))
        val resolved = SearchSqlBuilder.Resolved(fromMergeKeys = mapOf("hdfc" to listOf("HDFCBK")))
        val sql = builder.messages(q, SearchSort.RECENT, resolved, 10, 0)
        assertTrue(sql.sql.contains("m.mergeKey IN (?, ?)"))
        assertTrue(sql.sql.contains("m.address LIKE ? ESCAPE '\\'"))
        assertTrue(sql.sql.contains("g.displayName LIKE ? ESCAPE '\\'"))
        assertEquals(listOf<Any>("HDFC", "HDFCBK", "%hdfc%", "%hdfc%", "%hdfc%", 10L, 0L), sql.args)
    }

    @Test
    fun unresolvedSimMatchesNothing() {
        val q = SearchQuery(null, listOf(Filter.Sim("3")))
        val sql = builder.messages(q, SearchSort.RECENT, none, 10, 0)
        assertTrue(sql.sql.contains("WHERE (0)"))
        val resolved = SearchSqlBuilder.Resolved(simSubIds = mapOf("3" to listOf(7, 9)))
        val sql2 = builder.messages(q, SearchSort.RECENT, resolved, 10, 0)
        assertTrue(sql2.sql.contains("m.subId IN (?, ?)"))
        assertEquals(listOf<Any>(7L, 9L, 10L, 0L), sql2.args)
    }

    @Test
    fun relevanceOrdersByMatchCount() {
        val q = SearchQuery(TextExpr.Term("otp"))
        val sql = builder.messages(q, SearchSort.RELEVANCE, none, 10, 0)
        assertTrue(sql.sql.contains("ORDER BY matchCount DESC, sortValue DESC"))
    }

    @Test
    fun binSearchUsesLikeAndOnlyApplicableFilters() {
        val q = SearchQuery(
            TextExpr.Term("Café"),
            listOf(Filter.InFolder(Folder.BIN), Filter.CategoryIs(Category.OTP), Filter.HasLink),
        )
        assertTrue(builder.isBinSearch(q))
        val sql = builder.bin(q, none, 25, 0)
        assertTrue(sql.sql.startsWith("SELECT b.id AS id FROM bin_entry b WHERE"))
        assertTrue(sql.sql.contains("b.searchText LIKE ? ESCAPE '\\'"))
        assertTrue(sql.sql.contains("b.category = ?"))
        assertFalse(sql.sql.contains("hasLink"))
        assertEquals(listOf<Any>("%cafe%", "OTP", 25L, 0L), sql.args)
    }

    @Test
    fun emptyQueryDetected() {
        assertTrue(builder.isEmpty(SearchQuery.EMPTY))
        assertFalse(builder.isEmpty(SearchQuery(TextExpr.Term("a"))))
    }

    @Test
    fun likeEscaping() {
        assertEquals("%50\\%\\_off\\\\%", likeContains("50%_off\\"))
    }
}
