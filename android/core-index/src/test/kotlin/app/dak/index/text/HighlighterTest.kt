package app.dak.index.text

import app.dak.search.TextExpr
import kotlin.test.Test
import kotlin.test.assertEquals

class HighlighterTest {

    private fun highlighted(text: String, expr: TextExpr): List<String> =
        Highlighter.ranges(text, expr).map { text.substring(it.first, it.last + 1) }

    @Test
    fun caseAndDiacriticInsensitivePrefixAtWordStart() {
        val text = "Your CAFÉ order from Café Coffee Day; decafe not matched"
        assertEquals(listOf("CAFÉ", "Café"), highlighted(text, TextExpr.Term("cafe")))
    }

    @Test
    fun decomposedAccentsMapBackToOriginalOffsets() {
        val text = "Café open" // "Café" with a combining acute accent
        val ranges = Highlighter.ranges(text, TextExpr.Term("cafe"))
        assertEquals(listOf(0..3), ranges)
    }

    @Test
    fun negatedTermsAreNotHighlightedAndOverlapsMerge() {
        val text = "HDFC Bank: Rs 500 debited"
        val expr = TextExpr.And(TextExpr.Or(TextExpr.Term("hdfc"), TextExpr.Term("hd")), TextExpr.Not(TextExpr.Term("bank")))
        assertEquals(listOf("HDFC"), highlighted(text, expr))
    }

    @Test
    fun phraseTokensHighlightedIndividually() {
        val text = "Refund initiated for order"
        assertEquals(listOf("Refund", "initiated"), highlighted(text, TextExpr.Phrase("refund initiated")))
    }

    @Test
    fun fullWidthFormsFold() {
        val text = "ＯＴＰ 1234"
        assertEquals(listOf("ＯＴＰ"), highlighted(text, TextExpr.Term("otp")))
    }

    @Test
    fun devanagariWordsWithMatrasMatch() {
        val text = "आपका ओटीपी 1234 है"
        assertEquals(listOf("ओटीपी"), highlighted(text, TextExpr.Term("ओटीपी")))
    }
}
