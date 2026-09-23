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

    // Digits plus every grouping/decimal separator we recognise: ASCII comma/dot, the Swiss
    // apostrophe, NBSP/narrow-NBSP/thin-space (typical between a rupee sign and an Indian-grouped
    // amount, or as a European thousands separator). Must start and end on a digit.
    private val numberFragment = "\\d(?:[\\d,.'   ]*\\d)?"

    // Whitespace allowed between a currency token and the amount: ASCII whitespace plus NBSP and
    // narrow NBSP, which many banks use instead of a plain space ("₹ 500").
    private const val CURRENCY_GAP = "[\\s  ]*"

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

    /**
     * A decimal part written with a stray space around the point ("500,000. 00", "500,000 .00"). Exactly two
     * digits, and at least one blank on one side of the point (without a blank the point is part of
     * [numberFragment] already). Bounded quantifiers only, so it cannot backtrack.
     */
    private const val SPACED_DECIMAL = "((?:[ \\t]{1,2}\\.[ \\t]{0,2}|\\.[ \\t]{1,2})\\d{2}(?!\\d))"

    /**
     * Indian number words after the amount ("Rs 5 lakh", "₹2.5 crore"). "Cr" alone is deliberately not a
     * multiplier: in bank SMS "Rs.500 Cr" means *credited*.
     */
    private const val MULTIPLIER = "(?:[\\s\u00A0\u202F]{0,3}(lakhs?|lacs?|crores?)(?![A-Za-z]))"

    /**
     * Matches an optional currency token, a number, an optional spaced decimal part, an optional lakh/crore word,
     * an optional trailing currency token and an optional "/-". Groups: 1 prefix currency, 2 number, 3 spaced
     * decimal, 4 multiplier word, 5 suffix currency, 6 "/-".
     */
    private val pattern = Regex(
        "(?:$currencyCapture$CURRENCY_GAP)?($numberFragment)$SPACED_DECIMAL?$MULTIPLIER?" +
            "(?:$CURRENCY_GAP$currencyCapture)?(/-)?",
        RegexOption.IGNORE_CASE,
    )

    /** A fragment that already ends in a one/two-digit decimal part ("1,234.50", "9,50"). */
    private val endsInDecimal = Regex("[.,]\\d{1,2}$")

    /**
     * Returns every amount found in [text] that has an explicit currency symbol or ISO code
     * attached (bare numbers are never treated as money). [symbolMap] resolves a symbol such as
     * `$` to an ISO code and can be overridden by callers who want e.g. `$` to mean SGD.
     */
    fun findAll(
        text: String,
        symbolMap: Map<String, String> = CurrencyTable.defaultSymbolToCurrency,
    ): List<MoneyOccurrence> {
        // Normalise non-ASCII decimal digits first (1:1 per character, so match ranges into the
        // original text stay valid) so amounts written in any Indic/Arabic digit script parse.
        val text = DigitNormalizer.normalizeDigits(text)
        val results = mutableListOf<MoneyOccurrence>()
        for (match in pattern.findAll(text)) {
            val prefix = match.groups[1]?.value
            val numberRaw = match.groups[2]?.value ?: continue
            val suffix = match.groups[5]?.value
            val currencyToken = prefix ?: suffix ?: continue
            val currency = resolveCurrency(currencyToken, symbolMap) ?: continue
            val number = withSpacedDecimal(numberRaw, match.groups[3]?.value)
            val money = try {
                applyMultiplier(parseAmount(number, currency), match.groups[4]?.value)
            } catch (e: ArithmeticException) {
                null
            } catch (e: NumberFormatException) {
                null
            } ?: continue
            results += MoneyOccurrence(money, match.range, match.value)
        }
        return results
    }

    /**
     * Joins a spaced decimal part ("500,000" + ". 00") onto the number, unless the number already has a decimal
     * part of its own (then the trailing ". 12" is something else, e.g. the next sentence).
     */
    private fun withSpacedDecimal(numberRaw: String, spacedDecimal: String?): String {
        if (spacedDecimal == null || endsInDecimal.containsMatchIn(numberRaw)) return numberRaw
        // A trailing "." or "," is always the decimal separator here; drop the other kind as grouping.
        val digits = spacedDecimal.filter { it.isDigit() }
        return numberRaw + "." + digits
    }

    private fun applyMultiplier(money: Money, word: String?): Money {
        if (word == null) return money
        val factor = when (word.lowercase().first()) {
            'l' -> LAKH
            else -> CRORE
        }
        return Money.ofMajor(money.toBigDecimal().multiply(factor), money.currency)
    }

    private val LAKH = BigDecimal(100_000)
    private val CRORE = BigDecimal(10_000_000)

    /**
     * Every amount mentioned in [text], for search indexing: each currency-bearing amount [findAll] finds, plus
     * bare numbers that are unmistakably formatted as amounts (digit grouping such as "5,00,000" / "500,000", or
     * a two-digit decimal part such as "500000.00"). Plain digit runs (phone numbers, OTPs, years) are never
     * included. Only the first [AmountMentions.MAX_SCAN_CHARS] chars are scanned. Sorted by position.
     */
    fun findAllForSearch(
        text: String,
        symbolMap: Map<String, String> = CurrencyTable.defaultSymbolToCurrency,
    ): List<AmountMention> = AmountMentions.find(text, symbolMap)

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

    private fun normalizeSeparators(rawInput: String, exponent: Int): String {
        // Apostrophe (Swiss grouping, "1'234.56"), NBSP/narrow-NBSP/thin-space (Indian or European
        // grouping written with a non-breaking gap, "1 234,56") are always thousands
        // separators, never decimal - strip them unconditionally before the comma/dot heuristics.
        val raw = rawInput.replace("'", "").replace(" ", "").replace(" ", "").replace(" ", "")
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
