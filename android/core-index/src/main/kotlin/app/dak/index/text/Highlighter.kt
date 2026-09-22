package app.dak.index.text

import app.dak.search.TextExpr
import app.dak.search.TextNormalizer

/**
 * Computes highlight ranges for search hits on the *original* message text.
 *
 * The FTS index matches on [TextNormalizer]-normalized text, whose offsets differ from the original body
 * (case folding, NFKC, stripped diacritics). This class normalizes the body code point by code point while keeping
 * a map back to original offsets, then finds each positive query token at a word start (prefix semantics, matching
 * how [app.dak.index.sql.FtsSqlRenderer] builds prefix queries).
 */
object Highlighter {

    /** Highlight ranges (inclusive, in original [text] char offsets), sorted and merged. */
    fun ranges(text: String, expr: TextExpr?): List<IntRange> {
        if (expr == null || text.isEmpty()) return emptyList()
        val tokens = positiveTokens(expr)
        if (tokens.isEmpty()) return emptyList()
        return ranges(text, tokens)
    }

    /** Highlight ranges for already-normalized [tokens]. */
    fun ranges(text: String, tokens: Collection<String>): List<IntRange> {
        if (text.isEmpty() || tokens.isEmpty()) return emptyList()
        val mapped = MappedText.of(text)
        val found = ArrayList<IntRange>()
        for (token in tokens) {
            if (token.isEmpty()) continue
            var from = 0
            while (from <= mapped.normalized.length - token.length) {
                val at = mapped.normalized.indexOf(token, from)
                if (at < 0) break
                if (at == 0 || !isWordChar(mapped.normalized[at - 1])) {
                    found += mapped.originalStart(at)..mapped.originalEndInclusive(at + token.length - 1)
                }
                from = at + 1
            }
        }
        return merge(found)
    }

    /** Normalized tokens of every term and phrase that is not under a negation. */
    fun positiveTokens(expr: TextExpr): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(e: TextExpr, negated: Boolean) {
            when (e) {
                is TextExpr.Term -> if (!negated) out += tokenize(e.value)
                is TextExpr.Phrase -> if (!negated) out += tokenize(e.value)
                is TextExpr.And -> { walk(e.left, negated); walk(e.right, negated) }
                is TextExpr.Or -> { walk(e.left, negated); walk(e.right, negated) }
                is TextExpr.Not -> walk(e.expr, !negated)
            }
        }
        walk(expr, false)
        return out.toList()
    }

    /** Splits normalized text into word tokens the same way the FTS query builder does. */
    fun tokenize(raw: String): List<String> {
        val normalized = TextNormalizer.normalize(raw)
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        for (c in normalized) {
            if (isWordChar(c)) current.append(c) else if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** Letters, digits and combining marks (Indic vowel signs, viramas) belong to words. */
    fun isWordChar(c: Char): Boolean {
        if (c.isLetterOrDigit()) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            Character.isSurrogate(c)
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return ranges
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val r = sorted[i]
            cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last) else { out += cur; r }
        }
        out += cur
        return out
    }

    /** Normalized text with, for each normalized char, the original code point's char range. */
    private class MappedText(val normalized: String, private val starts: IntArray, private val ends: IntArray) {
        fun originalStart(normalizedIndex: Int): Int = starts[normalizedIndex]
        fun originalEndInclusive(normalizedIndex: Int): Int = ends[normalizedIndex]

        companion object {
            fun of(text: String): MappedText {
                val sb = StringBuilder(text.length)
                val starts = ArrayList<Int>(text.length)
                val ends = ArrayList<Int>(text.length)
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i)
                    val count = Character.charCount(cp)
                    val piece = TextNormalizer.normalize(String(Character.toChars(cp)))
                    for (c in piece) {
                        sb.append(c)
                        starts += i
                        ends += i + count - 1
                    }
                    i += count
                }
                return MappedText(sb.toString(), starts.toIntArray(), ends.toIntArray())
            }
        }
    }
}
