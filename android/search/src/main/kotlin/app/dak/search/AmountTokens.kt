package app.dak.search

import java.math.BigDecimal
import java.math.RoundingMode

/** An amount typed into a query: [hundredths] of the major unit, and the currency when one was written. */
data class QueryAmount(val hundredths: Long, val currency: String? = null)

/**
 * Canonical amount tokens shared by the index and the query side, so every spelling of an amount
 * ("500,000. 00", "5,00,000.00", "Rs.5,00,000/-", "INR 500000", "500000") finds the same messages.
 *
 * At ingest `:core-index` appends, for each amount in a body, `amt<hundredths>` and (when the amount had a
 * currency) `amt<iso><hundredths>` to the FTS text column only (never to displayed text). `hundredths` is the
 * value in hundredths of the major unit, i.e. minor units for INR/USD: five lakh rupees is `amt50000000` /
 * `amtinr50000000`. Tokens are lower case because the FTS tokenizer folds case anyway.
 *
 * On the query side [QueryParser] turns an amount-looking free-text term into `literal OR "amt…"` (see
 * [expandTerm]), and the `amount:` filter accepts the same spellings plus k / L / lakh / cr / crore suffixes
 * ([parseFilterValue]).
 */
object AmountTokens {

    const val PREFIX = "amt"

    /** Longest digit run (integer part) accepted as an amount; longer runs are ids/phone numbers, not money. */
    private const val MAX_PLAIN_DIGITS = 9
    private const val MAX_GROUPED_DIGITS = 15

    private val TOKEN = Regex("^amt(?:[a-z]{3})?\\d{1,19}$")

    /** Currency words/symbols a query may carry, mapped to ISO 4217. Kept small: `:search` has no `:finance`. */
    private val CURRENCY_PREFIXES: List<Pair<String, String>> = listOf(
        "rs." to "INR", "rs" to "INR", "inr" to "INR", "₹" to "INR",
        "usd" to "USD", "us$" to "USD", "$" to "USD",
        "eur" to "EUR", "€" to "EUR", "gbp" to "GBP", "£" to "GBP",
        "aed" to "AED", "sar" to "SAR", "sgd" to "SGD", "cad" to "CAD", "aud" to "AUD",
        "jpy" to "JPY", "¥" to "JPY", "bdt" to "BDT", "pkr" to "PKR", "lkr" to "LKR", "npr" to "NPR",
    ).sortedByDescending { it.first.length }

    /** Grouped (Western or Indian), plain, with an optional 1-2 digit decimal part. Anchored, no nesting. */
    private val NUMBER = Regex("^(\\d{1,3}(?:,\\d{3}){1,5}|\\d{1,2}(?:,\\d{2}){1,6},\\d{3}|\\d{1,15})(?:\\.(\\d{1,2}))?$")

    private val SUFFIXES: List<Pair<String, BigDecimal>> = listOf(
        "crores" to BigDecimal(10_000_000), "crore" to BigDecimal(10_000_000), "cr" to BigDecimal(10_000_000),
        "lakhs" to BigDecimal(100_000), "lakh" to BigDecimal(100_000), "lacs" to BigDecimal(100_000),
        "lac" to BigDecimal(100_000), "l" to BigDecimal(100_000),
        "k" to BigDecimal(1_000), "m" to BigDecimal(1_000_000),
    ).sortedByDescending { it.first.length }

    /** `amt<hundredths>` or, with [currency], `amt<iso><hundredths>` (lower case). */
    fun token(hundredths: Long, currency: String? = null): String =
        PREFIX + (currency?.lowercase()?.takeIf { it.length == 3 && it.all { c -> c in 'a'..'z' } } ?: "") + hundredths

    /**
     * The FTS-only suffix to append to a message's search text for the given amounts (hundredths + optional ISO
     * currency): both token forms per amount, de-duplicated, space separated. Empty when there are none.
     */
    fun indexText(amounts: List<Pair<Long, String?>>): String {
        if (amounts.isEmpty()) return ""
        val tokens = LinkedHashSet<String>()
        for ((hundredths, currency) in amounts) {
            if (hundredths < 0) continue
            tokens += token(hundredths)
            if (currency != null) tokens += token(hundredths, currency)
        }
        return tokens.joinToString(" ")
    }

    /** True for a canonical amount token (`amt50000000`, `amtinr50000000`). */
    fun isToken(s: String): Boolean = TOKEN.matches(s.lowercase())

    /**
     * Parses a free-text query term that looks like an amount: digits with optional Western/Indian grouping and
     * decimals, an optional currency prefix/suffix and trailing "/-" ("500000", "5,00,000", "₹5,00,000.00",
     * "Rs.5,00,000/-", "500000.00"). Plain digit runs longer than 9 digits (phone numbers, ids) and runs with a
     * leading zero (codes) are not amounts. No k/L/cr suffixes here: "5L" in free text is too ambiguous.
     */
    fun parseTerm(raw: String): QueryAmount? {
        var s = raw.trim().lowercase()
        if (s.isEmpty() || s.length > 40) return null
        s = s.removeSuffix("/-")
        val (currency, rest) = stripCurrency(s)
        val m = NUMBER.matchEntire(rest) ?: return null
        val whole = m.groupValues[1]
        val grouped = whole.contains(',')
        val digits = whole.replace(",", "")
        if (!grouped && currency == null && m.groups[2] == null) {
            if (digits.length > MAX_PLAIN_DIGITS || (digits.length > 1 && digits.startsWith('0'))) return null
        }
        if (digits.length > MAX_GROUPED_DIGITS) return null
        val value = toHundredths(BigDecimal(digits + (m.groups[2]?.value?.let { ".$it" } ?: ""))) ?: return null
        return QueryAmount(value, currency)
    }

    /**
     * Parses one `amount:` bound: everything [parseTerm] accepts, plus k (thousand), L / lakh / lac (1e5),
     * cr / crore (1e7) and m (million) suffixes on a plain or decimal number ("50k", "2.5L", "1cr"). Returns
     * hundredths, or null.
     */
    fun parseFilterValue(raw: String): Long? {
        val s = raw.trim().lowercase().removeSuffix("/-")
        if (s.isEmpty() || s.length > 40) return null
        parseTerm(s)?.let { return it.hundredths }
        val (_, rest) = stripCurrency(s)
        val suffix = SUFFIXES.firstOrNull { rest.endsWith(it.first) } ?: return null
        val number = rest.removeSuffix(suffix.first).trim()
        val m = NUMBER.matchEntire(number) ?: return null
        val value = BigDecimal(m.groupValues[1].replace(",", "") + (m.groups[2]?.value?.let { ".$it" } ?: ""))
        return toHundredths(value.multiply(suffix.second))
    }

    /**
     * Expands an amount-looking free-text term into `Term(literal) OR Term(plain digits) OR Phrase(token)`: the
     * literal still matches as typed, the plain-digit form finds bodies that wrote the amount ungrouped
     * ("500000"), and the canonical token finds every other spelling. Returns null when [term] is not an amount.
     * [SearchQuery.toQueryString] collapses the expansion back to the literal.
     */
    fun expandTerm(term: String): TextExpr? {
        val amount = parseTerm(term) ?: return null
        var expr: TextExpr = TextExpr.Term(term)
        if (amount.hundredths % 100 == 0L) {
            val plain = (amount.hundredths / 100).toString()
            if (plain != term) expr = TextExpr.Or(expr, TextExpr.Term(plain))
        }
        return TextExpr.Or(expr, TextExpr.Phrase(token(amount.hundredths, amount.currency)))
    }

    /** The literal term of an [expandTerm] expansion, or null when [expr] is not one. */
    internal fun literalOfExpansion(expr: TextExpr): String? {
        if (expr !is TextExpr.Or) return null
        val right = expr.right as? TextExpr.Phrase ?: return null
        if (!isToken(right.value)) return null
        var left = expr.left
        while (left is TextExpr.Or) left = left.left
        return (left as? TextExpr.Term)?.value
    }

    /** The query text to search for an amount of [hundredths] (e.g. "500000" or "1234.50"): see [parseTerm]. */
    fun queryText(hundredths: Long): String =
        if (hundredths % 100 == 0L) (hundredths / 100).toString()
        else BigDecimal.valueOf(hundredths, 2).toPlainString()

    private fun stripCurrency(s: String): Pair<String?, String> {
        for ((prefix, iso) in CURRENCY_PREFIXES) {
            if (s.startsWith(prefix) && s.length > prefix.length) return iso to s.substring(prefix.length).trimStart()
        }
        for ((suffix, iso) in CURRENCY_PREFIXES) {
            if (suffix.length == 3 && suffix.all { it in 'a'..'z' } && s.endsWith(suffix) && s.length > 3) {
                return iso to s.substring(0, s.length - 3).trimEnd()
            }
        }
        return null to s
    }

    private fun toHundredths(value: BigDecimal): Long? = try {
        value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    } catch (e: ArithmeticException) {
        null
    }
}
