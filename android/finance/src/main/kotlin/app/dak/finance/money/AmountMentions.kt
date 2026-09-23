package app.dak.finance.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount mentioned somewhere in a message body, for search indexing: every amount, not only the one the
 * transaction parser picked. [currency] is null for a bare but clearly amount-formatted number ("5,00,000.00").
 *
 * @property major the value in major units exactly as written (after lakh/crore expansion).
 * @property range char range into the original text.
 */
data class AmountMention(val major: BigDecimal, val currency: String?, val range: IntRange) {
    /**
     * The value in hundredths of the major unit (= minor units for 2-decimal currencies such as INR/USD), rounded
     * half-up. This is the currency-independent form search tokens use, so "500000", "5,00,000.00" and
     * "Rs.5,00,000/-" all share one value. Null when it does not fit a Long.
     */
    val hundredths: Long?
        get() = try {
            major.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
}

/** Finds every amount in a body for search indexing (see [MoneyParser.findAllForSearch]). */
internal object AmountMentions {

    /** Only this many leading chars are scanned (search indexing must stay cheap on huge MMS text parts). */
    const val MAX_SCAN_CHARS = 4_000

    /**
     * A bare number that is unmistakably written as an amount: Western (1,234,567) or Indian (12,34,567) digit
     * grouping, optionally with a 2-digit decimal part, or a plain digit run with a 2-digit decimal part
     * ("500000.00"). A plain digit run without decimals (phone numbers, OTPs, years) is never a mention. The
     * decimal part may carry a stray blank around the point ("500,000. 00"). Bounded, non-nested quantifiers.
     */
    private val bareAmount = Regex(
        "(?<![\\p{L}\\d.,])" +
            "(\\d{1,3}(?:,\\d{3}){1,6}|\\d{1,2}(?:,\\d{2}){1,5},\\d{3}|\\d{1,13}(?=[ \\t]{0,2}\\.))" +
            "((?:[ \\t]{0,2}\\.[ \\t]{0,2})\\d{2})?" +
            "(?![\\d])(?![.,]\\d)",
    )

    fun find(text: String, symbolMap: Map<String, String>): List<AmountMention> {
        val bounded = if (text.length > MAX_SCAN_CHARS) text.substring(0, MAX_SCAN_CHARS) else text
        val withCurrency = MoneyParser.findAll(bounded, symbolMap).map {
            AmountMention(it.money.toBigDecimal(), it.money.currencyUpper, it.range)
        }
        val out = ArrayList<AmountMention>(withCurrency)
        val digits = DigitNormalizer.normalizeDigits(bounded)
        for (match in bareAmount.findAll(digits)) {
            if (withCurrency.any { it.range.first <= match.range.last && match.range.first <= it.range.last }) continue
            val whole = match.groupValues[1]
            val decimal = match.groups[2]?.value?.filter { it.isDigit() }
            // A plain digit run only qualifies with a decimal part ("500000.00"); grouping alone qualifies otherwise.
            if (!whole.contains(',') && decimal == null) continue
            val value = try {
                BigDecimal(whole.replace(",", "") + if (decimal != null) ".$decimal" else "")
            } catch (e: NumberFormatException) {
                continue
            }
            out += AmountMention(value, null, match.range)
        }
        return out.sortedBy { it.range.first }
    }
}
