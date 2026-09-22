package app.dak.index.sql

import app.dak.search.TextExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FtsSqlRendererTest {

    private val fts = FtsSqlRenderer(FtsSqlRenderer.Mode.Fts("F(?)"))

    private fun term(s: String) = TextExpr.Term(s)

    @Test
    fun termsGetPrefixAndNormalization() {
        assertEquals("cafe*", fts.matchStringOrNull(term("Café")))
        // Single-character terms are matched exactly, not as a prefix.
        assertEquals("\"a\"", fts.matchStringOrNull(term("a")))
    }

    @Test
    fun punctuationSplitsIntoPhrase() {
        assertEquals("\"e mail\"", fts.matchStringOrNull(term("e-mail")))
        assertEquals("\"rs 500\"", fts.matchStringOrNull(TextExpr.Phrase("Rs. 500")))
    }

    @Test
    fun ftsSyntaxCharactersCannotLeak() {
        assertEquals("\"foo bar\"", fts.matchStringOrNull(term("foo\"*:(bar)")))
        assertNull(fts.toSql(term("***")))
    }

    @Test
    fun andOfLeavesMergesIntoOneMatch() {
        val q = fts.toSql(TextExpr.And(term("amazon"), TextExpr.Phrase("refund initiated")))!!
        assertEquals("F(?)", q.sql)
        assertEquals(listOf<Any>("amazon* \"refund initiated\""), q.args)
    }

    @Test
    fun orOfSingleTermsMergesButOrOfAndGroupsDoesNot() {
        assertEquals("swiggy* OR zomato*", fts.matchStringOrNull(TextExpr.Or(term("swiggy"), term("zomato"))))
        // (a OR b) AND c is expressible in standard syntax because OR binds tighter.
        assertEquals(
            "swiggy* OR zomato* refund*",
            fts.matchStringOrNull(TextExpr.And(TextExpr.Or(term("swiggy"), term("zomato")), term("refund"))),
        )
        // (a b) OR c is not: rendered as SQL OR of two MATCH subqueries.
        val q = fts.toSql(TextExpr.Or(TextExpr.And(term("hdfc"), term("debited")), term("upi")))!!
        assertEquals("(F(?)) OR (F(?))", q.sql)
        assertEquals(listOf<Any>("hdfc* debited*", "upi*"), q.args)
    }

    @Test
    fun negationBecomesSqlNot() {
        val q = fts.toSql(TextExpr.And(term("otp"), TextExpr.Not(term("amazon"))))!!
        assertEquals("(F(?)) AND (NOT (F(?)))", q.sql)
        assertEquals(listOf<Any>("otp*", "amazon*"), q.args)
        val onlyNegative = fts.toSql(TextExpr.Not(term("promo")))!!
        assertEquals("NOT (F(?))", onlyNegative.sql)
    }

    @Test
    fun likeModeRendersEveryLeafAsLike() {
        val like = FtsSqlRenderer(FtsSqlRenderer.Mode.Like("b.searchText"))
        val q = like.toSql(TextExpr.And(term("Amazon"), TextExpr.Phrase("50% off")))!!
        assertEquals("(b.searchText LIKE ? ESCAPE '\\') AND (b.searchText LIKE ? ESCAPE '\\')", q.sql)
        assertEquals(listOf<Any>("%amazon%", "%50%off%"), q.args)
    }

    @Test
    fun devanagariTermsStayWhole() {
        val m = fts.matchStringOrNull(term("नमस्ते"))!!
        assertTrue(m.startsWith("नमस्ते"), m)
    }
}
