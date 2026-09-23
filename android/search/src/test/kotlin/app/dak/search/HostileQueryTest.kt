package app.dak.search

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Whatever the user types (or pastes), the parser never throws and the FTS `MATCH` expression it leads to is valid
 * standard-syntax FTS4: SQLite rejects a malformed MATCH with an exception, which would take the search screen down.
 */
class HostileQueryTest {

    private val now = ZonedDateTime.of(2026, 9, 23, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    private fun parse(q: String) = QueryParser.parse(q, now, Locale.UK)

    /**
     * The shapes [FtsMatch.build] may produce: space-separated atoms, the first positive; an atom is a bare term (no
     * FTS syntax characters, never an upper-case operator keyword), a non-empty quoted phrase with no inner quote,
     * either optionally followed by one `*`; `-` only directly before a non-first atom; `OR` only between two atoms.
     */
    private fun assertValidFts(match: String, context: String) {
        fun bad(why: String): Nothing = fail("invalid FTS ($why): <$match> from <$context>")
        if (match.isBlank()) bad("blank")
        if (match != match.trim()) bad("untrimmed")
        if (match.count { it == '"' } % 2 != 0) bad("unbalanced quote")
        // Split into atoms, keeping phrases whole.
        val atoms = ArrayList<String>()
        var i = 0
        while (i < match.length) {
            // Runs of blanks are just separators (a term may keep an inner blank from `x"  "y`: harmless, the FTS
            // tokenizer splits it, and operator words are always lower-cased by then).
            if (match[i] == ' ') {
                i++
                continue
            }
            val start = i
            if (match[i] == '-' && i + 1 < match.length && match[i + 1] == '"' || match[i] == '"') {
                i = match.indexOf('"', match.indexOf('"', i) + 1).takeIf { it >= 0 }?.plus(1) ?: bad("open phrase")
                if (i < match.length && match[i] == '*') i++
            } else {
                while (i < match.length && match[i] != ' ') i++
            }
            atoms += match.substring(start, i)
        }
        for ((index, raw) in atoms.withIndex()) {
            if (raw == "OR") {
                if (index == 0 || index == atoms.lastIndex) bad("dangling OR")
                if (atoms[index - 1] == "OR" || atoms[index + 1].startsWith("-")) bad("OR next to OR or NOT")
                continue
            }
            val negated = raw.startsWith("-")
            if (negated && (index == 0 || atoms[index - 1] == "OR")) bad("NOT without a positive left side")
            var atom = if (negated) raw.substring(1) else raw
            if (atom.endsWith("*")) atom = atom.dropLast(1)
            if (atom.isEmpty()) bad("empty atom")
            if (atom.startsWith("\"")) {
                if (!atom.endsWith("\"") || atom.length < 3) bad("bad phrase $atom")
                if ('"' in atom.substring(1, atom.length - 1)) bad("quote inside phrase")
            } else {
                if (atom.any { it in "\"*:()^-" }) bad("syntax character in term $atom")
                if (atom in setOf("AND", "OR", "NOT", "NEAR") || atom.startsWith("NEAR/")) bad("operator keyword as a term")
            }
        }
        if (atoms.first().startsWith("-")) bad("starts with NOT")
    }

    private val hostile = listOf(
        "\"", "\"\"", "\"\"\"", "\" OR \"", "OR", "OR OR", "-", "--", "- -", "-OR", "OR -x", "a OR", "OR a", "a OR OR b",
        "*", "a*", "*a", "a**", "\"a*\"", "(", ")", "(a OR b)", "a:b", "subject:secret", "body:x", "NEAR", "a NEAR b",
        "a NEAR/3 b", "NEAR/2", "AND", "a AND b", "NOT", "a NOT b", "^a", "a^", "'", "a'b", "\\", "\\\"", "\u0000",
        "a\u0000b", "‍", "‌‍", "﻿", " ", "a OR b", "a　OR　b", "ＯＲ", "（a）",
        "a¨OR¨b", "⑴", "㈠", "ﬁ", "İ", "ǅ", "K", "😀", "\uD83D", "\uDE00", "a\"b\"c", "\"a\" \"b\"",
        "\"unterminated phrase", "-\"neg phrase\"", "\"-\"", "\"*\"", "\":\"", "\"()\"", "from:", "from:\"", "amount:",
        "amount:>", "amount:..", "amount:1..", "amount:>99999999999999999999", "amount:<-5", "amount:=1e9",
        "before:", "before:99999999999999999999", "after:9999-99-99", "during:\"last 99999999999999999999 days\"",
        "during:\"last 999999999999 days\"", "during:99999", "during:0000", "during:..", "during:1..x", "is:", "in:",
        "category:", "has:", "sim:", "x:y:z", ":", "::", "a:", ":a", "-from:", "-:", "5,00,000", "₹5,00,000.00/-",
        "-5,00,000", "Rs.", "amt123", "amt1*", "OR 500", "500 OR", "\"500\"", "-500", "500*",
    )

    @Test
    fun `hand-picked hostile queries never throw and always give valid FTS`() {
        for (q in hostile) {
            val parsed = try {
                parse(q)
            } catch (e: Exception) {
                fail("parse threw ${e::class.simpleName} on <$q>", e)
            }
            for (prefix in listOf(false, true)) {
                FtsMatch.build(parsed.textExpr, prefixLastTerm = prefix)?.let { assertValidFts(it, q) }
            }
            // The chips' query text parses again without throwing.
            parse(parsed.toQueryString())
        }
    }

    @Test
    fun `random queries never throw and always give valid FTS`() {
        val r = Random(2026_09_23)
        val alphabet = "aZ09 \"\"*:()-^'.,/\\_@#&+=~<>|!?₹$  ‍Øßİ"
        val pieces = listOf(
            "OR", "AND", "NOT", "NEAR", "NEAR/2", "from:", "amount:", "before:", "after:", "during:", "is:", "in:", "has:",
            "category:", "sim:", "\"", "-", "*", "5,00,000", "₹500", "500.00", "last 7 days", "today", "otp", "OR -",
        )
        repeat(20_000) {
            val sb = StringBuilder()
            repeat(r.nextInt(1, 12)) {
                if (r.nextInt(3) == 0) sb.append(pieces[r.nextInt(pieces.size)])
                else repeat(r.nextInt(1, 6)) { sb.append(alphabet[r.nextInt(alphabet.length)]) }
                if (r.nextBoolean()) sb.append(' ')
            }
            val q = sb.toString()
            val parsed = try {
                parse(q)
            } catch (e: Exception) {
                fail("parse threw ${e::class.simpleName} on <$q>", e)
            }
            FtsMatch.build(parsed.textExpr, prefixLastTerm = r.nextBoolean())?.let { assertValidFts(it, q) }
        }
    }

    @Test
    fun `sanitizeTerm removes every FTS syntax character`() {
        assertEquals("abc", FtsMatch.sanitizeTerm("\"a*b:c\""))
        assertEquals("near/3", FtsMatch.sanitizeTerm("NEAR/3")) // lower case: a term, not the operator
        assertEquals("or", FtsMatch.sanitizeTerm("OR"))
        assertEquals("or", FtsMatch.sanitizeTerm("ＯＲ")) // full-width folds to ASCII, then lower case
        assertEquals("1", FtsMatch.sanitizeTerm("⑴")) // NFKC "(1)", parentheses stripped
        assertEquals("", FtsMatch.sanitizeTerm("-()^*:\""))
    }

    /**
     * Bug: `a OR -b` built the MATCH expression "a OR b": standard FTS4 syntax cannot say "or not", and the negation
     * was dropped, so the search returned exactly the messages the user excluded.
     */
    @Test
    fun `a negated OR operand is never turned into a positive match`() {
        val match = FtsMatch.build(parse("swiggy OR -zomato").textExpr)
        assertEquals("swiggy -zomato", match)
        val expr = TextExpr.Or(TextExpr.Term("a"), TextExpr.Not(TextExpr.Phrase("b c")))
        assertEquals("a -\"b c\"", FtsMatch.build(expr))
    }

    /**
     * Bug: `during:"last N days"` with a huge N threw NumberFormatException / DateTimeException out of
     * [QueryParser.parse], which promises never to throw (the search box took the screen down).
     */
    @Test
    fun `absurd relative ranges are ignored instead of crashing`() {
        for (q in listOf("during:\"last 99999999999999999999 days\"", "during:\"last 999999999999 days\"")) {
            val parsed = parse(q)
            // Like any operator the parser cannot use, it stays searchable as free text.
            assertTrue(parsed.filters.isEmpty(), "$q -> $parsed")
            assertTrue(parsed.textExpr != null, "$q -> $parsed")
        }
        // A merely large N still works.
        val year = parse("during:\"last 365 days\"").filters.single() as Filter.DateRange
        assertEquals(366L * 24 * 60 * 60 * 1000, year.endMillis!! - year.startMillis!!)
    }

    @Test
    fun `amount filter bounds never overflow`() {
        for (q in listOf("amount:>99999999999999999999", "amount:<99999999999999999999", "amount:1e9", "amount:>-5")) {
            val filters = parse(q).filters
            assertTrue(filters.isEmpty(), "$q -> $filters")
        }
        // A crore-scale bound is fine; one past Long range is dropped, not wrapped negative.
        val crore = parse("amount:>1cr").filters.single() as Filter.AmountRange
        assertEquals(1_000_000_000L + 1, crore.minMinor)
        assertNull(crore.maxMinor)
        assertTrue(parse("amount:>999999999999999cr").filters.isEmpty())
        val widest = parse("amount:<999,999,999,999,999.99").filters.single() as Filter.AmountRange
        assertEquals(99_999_999_999_999_998L, widest.maxMinor)
    }
}
