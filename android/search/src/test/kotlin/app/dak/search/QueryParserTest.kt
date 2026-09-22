package app.dak.search

import app.dak.core.model.Category
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryParserTest {
    // Fixed "now": Tuesday 2026-09-22 12:00 IST, matching today's date in the harness.
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 9, 22, 12, 0, 0, 0, zone)

    private fun startOfDay(y: Int, m: Int, d: Int) =
        java.time.LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `plain free text becomes AND chain of terms`() {
        val q = QueryParser.parse("swiggy order", now)
        assertEquals(TextExpr.And(TextExpr.Term("swiggy"), TextExpr.Term("order")), q.textExpr)
        assertTrue(q.filters.isEmpty())
    }

    @Test
    fun `OR joins two terms`() {
        val q = QueryParser.parse("swiggy OR zomato", now)
        assertEquals(TextExpr.Or(TextExpr.Term("swiggy"), TextExpr.Term("zomato")), q.textExpr)
    }

    @Test
    fun `quoted phrase preserved`() {
        val q = QueryParser.parse("\"order confirmed\"", now)
        assertEquals(TextExpr.Phrase("order confirmed"), q.textExpr)
    }

    @Test
    fun `negated free text term`() {
        val q = QueryParser.parse("-spam", now)
        assertEquals(TextExpr.Not(TextExpr.Term("spam")), q.textExpr)
    }

    @Test
    fun `from operator with plain and quoted value`() {
        assertEquals(listOf(Filter.From("hdfc")), QueryParser.parse("from:hdfc", now).filters)
        assertEquals(listOf(Filter.From("Amit Kumar")), QueryParser.parse("from:\"Amit Kumar\"", now).filters)
    }

    @Test
    fun `negated from operator`() {
        assertEquals(listOf(Filter.Not(Filter.From("spammer"))), QueryParser.parse("-from:spammer", now).filters)
    }

    @Test
    fun `category operator maps all synonyms`() {
        assertEquals(Category.PERSONAL, categoryOf("category:personal"))
        assertEquals(Category.TRANSACTION, categoryOf("category:transaction"))
        assertEquals(Category.TRANSACTION, categoryOf("category:transactions"))
        assertEquals(Category.OTP, categoryOf("category:otp"))
        assertEquals(Category.PROMOTION, categoryOf("category:promo"))
        assertEquals(Category.PROMOTION, categoryOf("category:promotion"))
        assertEquals(Category.PROMOTION, categoryOf("category:promotions"))
        assertEquals(Category.SPAM, categoryOf("category:spam"))
    }

    private fun categoryOf(input: String): Category =
        (QueryParser.parse(input, now).filters.single() as Filter.CategoryIs).category

    @Test
    fun `unknown category becomes free text`() {
        val q = QueryParser.parse("category:nonsense", now)
        assertTrue(q.filters.isEmpty())
        assertEquals(TextExpr.Term("category:nonsense"), q.textExpr)
    }

    @Test
    fun `sim operator accepts slot number or name`() {
        assertEquals(listOf(Filter.Sim("1")), QueryParser.parse("sim:1", now).filters)
        assertEquals(listOf(Filter.Sim("Jio")), QueryParser.parse("sim:Jio", now).filters)
    }

    @Test
    fun `has operators`() {
        assertEquals(listOf(Filter.HasAttachment), QueryParser.parse("has:attachment", now).filters)
        assertEquals(listOf(Filter.HasLink), QueryParser.parse("has:link", now).filters)
        assertEquals(listOf(Filter.HasOtp), QueryParser.parse("has:otp", now).filters)
    }

    @Test
    fun `unknown has value becomes free text`() {
        val q = QueryParser.parse("has:cats", now)
        assertTrue(q.filters.isEmpty())
    }

    @Test
    fun `amount greater and less than`() {
        val gt = QueryParser.parse("amount:>500", now).filters.single() as Filter.AmountRange
        assertEquals(50001L, gt.minMinor)
        assertNull(gt.maxMinor)

        val lt = QueryParser.parse("amount:<100", now).filters.single() as Filter.AmountRange
        assertNull(lt.minMinor)
        assertEquals(9999L, lt.maxMinor)
    }

    @Test
    fun `amount range and equals`() {
        val range = QueryParser.parse("amount:100..500", now).filters.single() as Filter.AmountRange
        assertEquals(10000L, range.minMinor)
        assertEquals(50000L, range.maxMinor)

        val eq = QueryParser.parse("amount:=250", now).filters.single() as Filter.AmountRange
        assertEquals(25000L, eq.minMinor)
        assertEquals(25000L, eq.maxMinor)
    }

    @Test
    fun `amount handles decimals`() {
        val gt = QueryParser.parse("amount:>99.5", now).filters.single() as Filter.AmountRange
        assertEquals(9951L, gt.minMinor)
    }

    @Test
    fun `before and after with iso and dd-mm-yyyy dates`() {
        val before = QueryParser.parse("before:2026-01-15", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 1, 15), before.endMillis)
        assertNull(before.startMillis)

        val after = QueryParser.parse("after:15/01/2026", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 1, 15), after.startMillis)
    }

    @Test
    fun `before yesterday and today`() {
        val yesterday = QueryParser.parse("before:yesterday", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 9, 21), yesterday.endMillis)

        val today = QueryParser.parse("after:today", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 9, 22), today.startMillis)
    }

    @Test
    fun `during today and yesterday`() {
        val today = QueryParser.parse("during:today", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 9, 22), today.startMillis)
        assertEquals(startOfDay(2026, 9, 23), today.endMillis)

        val yesterday = QueryParser.parse("during:yesterday", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 9, 21), yesterday.startMillis)
        assertEquals(startOfDay(2026, 9, 22), yesterday.endMillis)
    }

    @Test
    fun `during this and last week`() {
        // now is Tuesday 2026-09-22; ISO week start (Monday) is 2026-09-21.
        val thisWeek = QueryParser.parse("during:\"this week\"", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 9, 21), thisWeek.startMillis)
        assertEquals(startOfDay(2026, 9, 28), thisWeek.endMillis)

        val lastWeek = QueryParser.parse("during:\"last week\"", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 9, 14), lastWeek.startMillis)
        assertEquals(startOfDay(2026, 9, 21), lastWeek.endMillis)
    }

    @Test
    fun `during this and last month`() {
        val thisMonth = QueryParser.parse("during:\"this month\"", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 9, 1), thisMonth.startMillis)
        assertEquals(startOfDay(2026, 10, 1), thisMonth.endMillis)

        val lastMonth = QueryParser.parse("during:\"last month\"", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 8, 1), lastMonth.startMillis)
        assertEquals(startOfDay(2026, 9, 1), lastMonth.endMillis)
    }

    @Test
    fun `during last N days`() {
        val d = QueryParser.parse("during:\"last 7 days\"", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 9, 15), d.startMillis)
        assertEquals(startOfDay(2026, 9, 23), d.endMillis)
    }

    @Test
    fun `during month name and year`() {
        val march = QueryParser.parse("during:march", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 3, 1), march.startMillis)
        assertEquals(startOfDay(2026, 4, 1), march.endMillis)

        val year = QueryParser.parse("during:2026", now).filters.single() as Filter.DateRange
        assertEquals(startOfDay(2026, 1, 1), year.startMillis)
        assertEquals(startOfDay(2027, 1, 1), year.endMillis)
    }

    @Test
    fun `unresolvable during becomes free text`() {
        val q = QueryParser.parse("during:blorp", now)
        assertTrue(q.filters.isEmpty())
        assertEquals(TextExpr.Term("during:blorp"), q.textExpr)
    }

    @Test
    fun `in and is operators`() {
        assertEquals(listOf(Filter.InFolder(Folder.ARCHIVE)), QueryParser.parse("in:archive", now).filters)
        assertEquals(listOf(Filter.InFolder(Folder.BIN)), QueryParser.parse("in:bin", now).filters)
        assertEquals(listOf(Filter.InFolder(Folder.INBOX)), QueryParser.parse("in:inbox", now).filters)
        assertEquals(listOf(Filter.IsStarred), QueryParser.parse("is:starred", now).filters)
        assertEquals(listOf(Filter.IsUnread), QueryParser.parse("is:unread", now).filters)
        assertEquals(listOf(Filter.IsRead), QueryParser.parse("is:read", now).filters)
    }

    @Test
    fun `mixed filters and free text and OR`() {
        val q = QueryParser.parse("from:hdfc category:transaction swiggy OR zomato amount:>500", now)
        assertEquals(
            setOf(Filter.From("hdfc"), Filter.CategoryIs(Category.TRANSACTION)),
            q.filters.filterNot { it is Filter.AmountRange }.toSet(),
        )
        assertTrue(q.filters.any { it is Filter.AmountRange })
        assertEquals(TextExpr.Or(TextExpr.Term("swiggy"), TextExpr.Term("zomato")), q.textExpr)
    }

    @Test
    fun `extra and irregular whitespace tolerated`() {
        val q = QueryParser.parse("   from:hdfc     swiggy   ", now)
        assertEquals(listOf(Filter.From("hdfc")), q.filters)
        assertEquals(TextExpr.Term("swiggy"), q.textExpr)
    }

    @Test
    fun `empty input never throws`() {
        val q = QueryParser.parse("", now)
        assertNull(q.textExpr)
        assertTrue(q.filters.isEmpty())
    }

    @Test
    fun `garbage input never throws`() {
        for (input in listOf("::::", "amount:", "from:", "-", "\"", "OR OR OR", "amount:>>>5")) {
            QueryParser.parse(input, now) // must not throw
        }
    }

    @Test
    fun `unknown operator key becomes free text`() {
        val q = QueryParser.parse("subject:hello", now)
        assertTrue(q.filters.isEmpty())
        assertEquals(TextExpr.Term("subject:hello"), q.textExpr)
    }
}
