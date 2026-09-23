package app.dak.search

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmountSearchTest {

    private val now = ZonedDateTime.of(2026, 9, 23, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
    private fun parse(q: String) = QueryParser.parse(q, now, Locale.UK)

    @Test
    fun `every spelling of five lakh maps to one canonical value`() {
        for (term in listOf("500000", "5,00,000", "500,000", "500000.00", "5,00,000.00", "500,000.00", "₹5,00,000", "Rs.5,00,000/-")) {
            assertEquals(50_000_000L, AmountTokens.parseTerm(term)?.hundredths, term)
        }
        assertEquals("INR", AmountTokens.parseTerm("₹5,00,000.00")?.currency)
        assertEquals("INR", AmountTokens.parseTerm("inr500000")?.currency)
        assertNull(AmountTokens.parseTerm("500000")?.currency)
    }

    @Test
    fun `non amounts are not parsed as amounts`() {
        for (term in listOf("hdfc", "9876543210", "0123", "12/03/2026", "1,2,3", "5L", "abc500", "500.123", "")) {
            assertNull(AmountTokens.parseTerm(term), term)
        }
    }

    @Test
    fun `free text amount becomes literal OR plain digits OR token`() {
        val expr = parse("5,00,000").textExpr
        assertEquals(
            TextExpr.Or(TextExpr.Or(TextExpr.Term("5,00,000"), TextExpr.Term("500000")), TextExpr.Phrase("amt50000000")),
            expr,
        )
        assertEquals(TextExpr.Or(TextExpr.Term("500000"), TextExpr.Phrase("amt50000000")), parse("500000").textExpr)
        assertEquals(
            TextExpr.Or(TextExpr.Or(TextExpr.Term("₹5,00,000.00"), TextExpr.Term("500000")), TextExpr.Phrase("amtinr50000000")),
            parse("₹5,00,000.00").textExpr,
        )
    }

    @Test
    fun `amount term combines with other words and keeps round trip text`() {
        val q = parse("hdfc 5,00,000 from:HDFCBK")
        val expr = q.textExpr as TextExpr.And
        assertEquals(TextExpr.Term("hdfc"), expr.left)
        assertEquals("hdfc 5,00,000 from:HDFCBK", q.toQueryString())
        assertEquals(q, parse(q.toQueryString()))
    }

    @Test
    fun `negated amount stays a plain negated term`() {
        assertEquals(TextExpr.Not(TextExpr.Term("500000")), parse("-500000").textExpr)
    }

    @Test
    fun `fts builder keeps amount tokens intact and never prefixes them`() {
        val match = FtsMatch.build(parse("hdfc 500000").textExpr, prefixLastTerm = true)
        assertEquals("hdfc 500000* OR \"amt50000000\"", match)
        assertEquals("amtinr50000000", FtsMatch.sanitizeTerm("amtINR50000000"))
    }

    @Test
    fun `index text carries both token forms`() {
        assertEquals("amt50000000 amtinr50000000 amt12345", AmountTokens.indexText(listOf(50_000_000L to "INR", 12_345L to null, 50_000_000L to null)))
        assertEquals("", AmountTokens.indexText(emptyList()))
        assertTrue(AmountTokens.isToken("amtINR50000000"))
        assertTrue(AmountTokens.isToken("amt1"))
        assertTrue(!AmountTokens.isToken("amount"))
    }

    @Test
    fun `amount filter accepts every spelling`() {
        val exact = Filter.AmountRange(50_000_000L, 50_000_000L)
        for (v in listOf("5,00,000", "=5,00,000", "500000", "₹5,00,000.00", "500000.00", "5L", "5lakh", "5 lakh".replace(" ", ""), "0.05cr", "500k")) {
            assertEquals(listOf<Filter>(exact), parse("amount:$v").filters, v)
        }
    }

    @Test
    fun `amount filter suffixes for bounds and ranges`() {
        assertEquals(Filter.AmountRange(5_000_001L, null), parse("amount:>50k").filters.single())
        assertEquals(Filter.AmountRange(null, 49_999_999L), parse("amount:<5L").filters.single())
        assertEquals(Filter.AmountRange(10_000_000L, 1_000_000_000L), parse("amount:1L..1cr").filters.single())
        assertEquals(Filter.AmountRange(25_000_000L, 25_000_000L), parse("amount:2.5lakh").filters.single())
        assertEquals(Filter.AmountRange(150_000L, 150_000L), parse("amount:1.5k").filters.single())
        assertEquals(Filter.AmountRange(10_000L, 50_000L), parse("amount:500..100").filters.single())
    }

    @Test
    fun `invalid amount filter falls back to free text`() {
        val q = parse("amount:lots")
        assertTrue(q.filters.isEmpty())
        assertNotNull(q.textExpr)
    }

    @Test
    fun `amount filter round trips`() {
        for (v in listOf("amount:>50k", "amount:<1234.50", "amount:1L..2L", "amount:=99.99")) {
            val q = parse(v)
            assertEquals(q, parse(q.toQueryString()), v)
        }
    }

    @Test
    fun `query text for an amount parses back to it`() {
        assertEquals("500000", AmountTokens.queryText(50_000_000L))
        assertEquals("1234.50", AmountTokens.queryText(123_450L))
        assertEquals(123_450L, AmountTokens.parseTerm(AmountTokens.queryText(123_450L))?.hundredths)
    }
}
