package app.dak.finance.money

import java.math.BigDecimal

/**
 * A [Money] found in free text, together with the character range it was found at so callers
 * (such as [app.dak.finance.parser.TransactionParser]) can reason about which amount in an SMS
 * body an occurrence refers to (e.g. the transaction amount vs. the available balance).
 */
data class MoneyOccurrence(val money: Money, val range: IntRange, val rawText: String)

/**
 * Parses amounts of money as written in Indian bank/card/UPI/wallet SMS text: currency symbols
 * or ISO codes as a prefix or suffix, Indian (lakh/crore) or Western digit grouping, a trailing
 * "/-", and a decimal point or comma disambiguated by context. Never guesses a currency for a
 * bare number with no symbol or code nearby.
 */
object MoneyParser {

    private val numberFragment = """\d(?:[\d,.]*\d)?"""

    private val currencyTokens: List<String> =
        (CurrencyTable.defaultSymbolToCurrency.keys + CurrencyTable.knownIsoCodes)
            .distinct()
            .sortedByDescending { it.length }

    private val currencyAlt = currencyTokens.joinToString("|") { Regex.escape(it) }

    /**
     * A currency token (symbol or ISO code), not glued to surrounding letters (so "INR" inside a
     * longer word never matches) - but a following digit is fine and expected ("Rs.500", "$42.10").
     */
    private val currencyCapture = "(?<![A-Za-z])($currencyAlt)(?![A-Za-z])"

    /** Matches an optional currency token, a number, an optional trailing currency token, an optional "/-". */
    private val pattern = Regex(
        "(?:$currencyCapture\\s*)?($numberFragment)(?:\\s*$currencyCapture)?(/-)?",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Returns every amount found in [text] that has an explicit currency symbol or ISO code
     * attached (bare numbers are never treated as money). [symbolMap] resolves a symbol such as
     * `$` to an ISO code and can be overridden by callers who want e.g. `$` to mean SGD.
     */
    fun findAll(
        text: String,
        symbolMap: Map<String, String> = CurrencyTable.defaultSymbolToCurrency,
    ): List<MoneyOccurrence> {
        val results = mutableListOf<MoneyOccurrence>()
        for (match in pattern.findAll(text)) {
            val prefix = match.groups[1]?.value
            val numberRaw = match.groups[2]?.value ?: continue
            val suffix = match.groups[3]?.value
            val currencyToken = prefix ?: suffix ?: continue
            val currency = resolveCurrency(currencyToken, symbolMap) ?: continue
            val money = try {
                parseAmount(numberRaw, currency)
            } catch (e: ArithmeticException) {
                null
            } ?: continue
            results += MoneyOccurrence(money, match.range, match.value)
        }
        return results
    }

    /** Returns the first amount with a currency indicator found in [text], or null if none. */
    fun parse(
        text: String,
        symbolMap: Map<String, String> = CurrencyTable.defaultSymbolToCurrency,
    ): Money? = findAll(text, symbolMap).firstOrNull()?.money

    private fun resolveCurrency(token: String, symbolMap: Map<String, String>): String? {
        val trimmed = token.trim()
        symbolMap.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.let { return it.value }
        val upper = trimmed.uppercase().removeSuffix(".")
        if (upper in CurrencyTable.knownIsoCodes) return upper
        return null
    }

    /**
     * Parses a raw number fragment (digits plus `,`/`.` separators, e.g. "1,234.50", "12,34,567",
     * "9,50") into a [Money] of [currency], disambiguating the decimal separator by context:
     * - both `,` and `.` present: whichever occurs last is the decimal separator.
     * - only one kind present, once: if the digits after it match the currency's minor-unit
     *   exponent, it is a decimal separator (handles "EUR 9,50"); if there are exactly 3 digits
     *   after it and that does not match the exponent, it is a thousands separator (handles
     *   "$1,234" for a 2-decimal currency); otherwise it is treated as decimal.
     * - only one kind present, more than once: thousands separator (grouping), e.g. "12,34,567".
     */
    fun parseAmount(raw: String, currency: String): Money {
        val exponent = CurrencyTable.minorUnitExponent(currency.uppercase())
        val cleaned = normalizeSeparators(raw, exponent)
        return Money.ofMajor(BigDecimal(cleaned), currency)
    }

    private fun normalizeSeparators(raw: String, exponent: Int): String {
        val commaCount = raw.count { it == ',' }
        val dotCount = raw.count { it == '.' }
        return when {
            commaCount > 0 && dotCount > 0 -> {
                val lastComma = raw.lastIndexOf(',')
                val lastDot = raw.lastIndexOf('.')
                val decimalIsComma = lastComma > lastDot
                val thousandChar = if (decimalIsComma) '.' else ','
                val decimalChar = if (decimalIsComma) ',' else '.'
                raw.replace(thousandChar.toString(), "").replace(decimalChar, '.')
            }
            commaCount > 1 -> raw.replace(",", "")
            commaCount == 1 -> {
                val lastComma = raw.lastIndexOf(',')
                val digitsAfter = raw.length - lastComma - 1
                if (digitsAfter == 3 && digitsAfter != exponent) raw.replace(",", "") else raw.replace(',', '.')
            }
            dotCount > 1 -> raw.replace(".", "")
            dotCount == 1 -> {
                val lastDot = raw.lastIndexOf('.')
                val digitsAfter = raw.length - lastDot - 1
                if (digitsAfter == 3 && digitsAfter != exponent) raw.replace(".", "") else raw
            }
            else -> raw
        }
    }
}
