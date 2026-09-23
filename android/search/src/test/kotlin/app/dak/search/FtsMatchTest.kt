package app.dak.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsMatchTest {

    @Test
    fun `null expr yields null`() {
        assertNull(FtsMatch.build(null))
    }

    @Test
    fun `single term`() {
        assertEquals("swiggy", FtsMatch.build(TextExpr.Term("swiggy")))
    }

    @Test
    fun `and chain is implicit space`() {
        val expr = TextExpr.And(TextExpr.Term("swiggy"), TextExpr.Term("order"))
        assertEquals("swiggy order", FtsMatch.build(expr))
    }

    @Test
    fun `or uses literal keyword`() {
        val expr = TextExpr.Or(TextExpr.Term("swiggy"), TextExpr.Term("zomato"))
        assertEquals("swiggy OR zomato", FtsMatch.build(expr))
    }

    @Test
    fun `not follows a positive term`() {
        val expr = TextExpr.And(TextExpr.Term("order"), TextExpr.Not(TextExpr.Term("spam")))
        assertEquals("order -spam", FtsMatch.build(expr))
    }

    @Test
    fun `leading negation is reordered behind a positive anchor`() {
        val expr = TextExpr.And(TextExpr.Not(TextExpr.Term("spam")), TextExpr.Term("order"))
        assertEquals("order -spam", FtsMatch.build(expr))
        // Only the first positive atom moves; the rest keep their order.
        val longer = TextExpr.And(TextExpr.And(TextExpr.Not(TextExpr.Term("spam")), TextExpr.Term("order")), TextExpr.Term("food"))
        assertEquals("order -spam food", FtsMatch.build(longer))
    }

    @Test
    fun `all-negated expression has no positive anchor and is not representable`() {
        assertNull(FtsMatch.build(TextExpr.Not(TextExpr.Term("spam"))))
    }

    @Test
    fun `phrase is quoted`() {
        assertEquals("\"order confirmed\"", FtsMatch.build(TextExpr.Phrase("order confirmed")))
    }

    @Test
    fun `special characters are stripped from terms`() {
        assertEquals("abc123", FtsMatch.build(TextExpr.Term("abc*123\"(:)-")))
    }

    @Test
    fun `terms are lowercased and diacritics stripped for latin`() {
        assertEquals("cafe", FtsMatch.build(TextExpr.Term("Café")))
    }

    @Test
    fun `indic combining marks preserved`() {
        val hindi = "नमस्ते" // has combining vowel signs / virama
        assertEquals(TextNormalizer.normalize(hindi), FtsMatch.build(TextExpr.Term(hindi)))
    }

    @Test
    fun `prefix matching appends star to last positive term`() {
        val expr = TextExpr.And(TextExpr.Term("swig"), TextExpr.Term("ord"))
        assertEquals("swig ord*", FtsMatch.build(expr, prefixLastTerm = true))
    }

    @Test
    fun `prefix star skips amount tokens and negated terms`() {
        val expr = TextExpr.And(TextExpr.Term("salary"), TextExpr.Or(TextExpr.Term("5000"), TextExpr.Phrase("amt500000")))
        // The canonical token is never prefixed ("amt500000*" would also match ten times the amount).
        assertEquals("salary 5000* OR \"amt500000\"", FtsMatch.build(expr, prefixLastTerm = true))
        val negatedLast = TextExpr.And(TextExpr.Term("order"), TextExpr.Not(TextExpr.Term("spam")))
        assertEquals("order* -spam", FtsMatch.build(negatedLast, prefixLastTerm = true))
    }

    @Test
    fun `terms that sanitize to nothing are dropped, and nothing left means no expression`() {
        assertEquals("order", FtsMatch.build(TextExpr.And(TextExpr.Term("order"), TextExpr.Term("()*"))))
        assertNull(FtsMatch.build(TextExpr.Term("\"*:\"")))
        assertNull(FtsMatch.build(TextExpr.Phrase("\"\"")))
    }
}
