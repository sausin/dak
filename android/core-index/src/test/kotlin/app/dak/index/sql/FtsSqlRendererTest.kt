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
    fun hostileInputNeverReachesTheSqlTextAndOnlyYieldsWellFormedMatchStrings() {
        val hostile = listOf(
            "OR", "AND", "NOT", "NEAR", "NEAR/3", "a OR b", "x AND y", "-foo", "+bar", "^start", "col:value",
            "searchText:secret", "\"unbalanced", "a\"b\"c", "(((", ")) OR 1=1 --", "'; DROP TABLE indexed_message; --",
            "*", "a*b*", "%_\\", "\u0000nul", "\u202Ertl", "e\u0301", "\uFF2F\uFF32", "🙂 or 😀", "x".repeat(500),
        )
        for (input in hostile) {
            for (expr in listOf(term(input), TextExpr.Phrase(input), TextExpr.Or(term(input), term("upi")), TextExpr.Not(term(input)))) {
                val q = fts.toSql(expr) ?: continue
                // The SQL is built only from the predicate and fixed keywords; user text travels as bound arguments.
                assertTrue(q.sql.replace("F(?)", "").all { it in "()ANDORT " }, "sql for <$input>: ${q.sql}")
                assertEquals(q.sql.count { it == '?' }, q.args.size)
                for (arg in q.args) assertWellFormedMatch(arg as String, input)
            }
        }
    }

    /** Implicit-AND / OR sequence of `token`, `token*` and `"token token"` items; tokens carry no FTS syntax. */
    private fun assertWellFormedMatch(match: String, input: String) {
        val item = Regex("(\"[^\"*():^\\-+ ]+( [^\"*():^\\-+ ]+)*\"|[^\"*():^\\-+ ]+\\*)")
        val grammar = Regex("${item.pattern}(( OR)? ${item.pattern})*")
        assertTrue(grammar.matches(match), "match for <$input>: <$match>")
        // Upper case only ever appears as our own OR operator: a user's "OR" / "NEAR" is a lower-case term.
        assertTrue(match.replace(" OR ", " ").none { it.isUpperCase() }, "match for <$input>: <$match>")
    }

    @Test
    fun likeModeEscapesWildcardsFromTheUser() {
        val like = FtsSqlRenderer(FtsSqlRenderer.Mode.Like("c"))
        for (input in listOf("100%", "a_b", "back\\slash", "%_%")) {
            val q = like.toSql(term(input)) ?: continue
            val pattern = q.args.single() as String
            // `%` also separates tokens, but a `_` wildcard can only come from the user and must be escaped.
            val inner = pattern.removePrefix("%").removeSuffix("%")
            var i = 0
            while (i < inner.length) {
                if (inner[i] == '\\') { i += 2; continue }
                assertTrue(inner[i] != '_', "unescaped _ in <$pattern> for <$input>")
                i++
            }
        }
    }

    @Test
    fun devanagariTermsStayWhole() {
        val m = fts.matchStringOrNull(term("नमस्ते"))!!
        assertTrue(m.startsWith("नमस्ते"), m)
    }
}
