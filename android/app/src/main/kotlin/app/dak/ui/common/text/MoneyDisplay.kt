package app.dak.ui.common.text

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.dak.finance.money.CurrencyTable
import app.dak.finance.money.Money
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Locale- and script-aware display formatting for [Money], on top of [Money.format]'s
 * locale-independent core arithmetic.
 *
 * Rules:
 * - Indian digit grouping (`1,23,456.78`) is used whenever the amount is in INR, *or* the
 *   viewer's own locale country is India - a traveller seeing a foreign-currency alert still
 *   reads it grouped the way they are used to.
 * - Any currency other than the viewer's own home currency is always shown with its ISO 4217
 *   code, not just a symbol - "$" alone is ambiguous between USD/CAD/AUD/SGD/HKD, which matters
 *   most exactly when travelling and seeing a foreign-currency card alert.
 * - [indicative] prefixes "≈" for values that are an approximate/derived conversion rather than
 *   the amount actually charged.
 * - Digits, grouping and decimal separators all come from the locale's [DecimalFormatSymbols], so
 *   a locale with native digits (Arabic-Indic for `ar-EG`, Devanagari for `hi-IN-u-nu-deva`) never
 *   shows ASCII digits between native separators; the grouping pattern (lakh/crore or thousands)
 *   is Dak's own and stays as above.
 */
object MoneyDisplay {

    /**
     * Formats [money] for display. [homeCurrency] is the viewer's own currency (e.g. from their
     * SIM/locale); when null it defaults to the currency implied by [locale]'s country. Indian
     * grouping is used when [money] is INR or [locale]'s country is India (`IN`).
     */
    fun format(
        money: Money,
        locale: Locale = Locale.getDefault(),
        homeCurrency: String? = null,
        indicative: Boolean = false,
    ): String {
        val currency = money.currencyUpper
        val home = (homeCurrency ?: currencyForLocale(locale)).uppercase()
        val useIndianGrouping = currency == "INR" || locale.country.equals("IN", ignoreCase = true)
        val exponent = CurrencyTable.minorUnitExponent(currency)
        val negative = money.amountMinor < 0
        val absMinor = kotlin.math.abs(money.amountMinor)
        val scale = CurrencyTable.minorUnitScale(currency)
        val whole = if (scale == 1L) absMinor else absMinor / scale
        val fraction = if (scale == 1L) 0L else absMinor % scale

        val symbols = DecimalFormatSymbols.getInstance(locale)
        val groupedWhole = if (useIndianGrouping) {
            groupIndian(whole.toString(), symbols.groupingSeparator)
        } else {
            groupWestern(whole.toString(), symbols.groupingSeparator)
        }

        val sb = StringBuilder()
        if (indicative) sb.append('≈') // "≈"
        if (negative) sb.append('-')
        // Foreign currency (not the viewer's own) always shows its ISO code, so "$" is never
        // ambiguous between USD/CAD/AUD/SGD/HKD/... - critical for foreign-currency card alerts
        // while travelling.
        if (currency == home) {
            sb.append(CurrencyTable.symbolFor(currency))
        } else {
            sb.append(currency)
            sb.append(' ')
        }
        sb.append(localizeDigits(groupedWhole, symbols.zeroDigit))
        if (exponent > 0) {
            sb.append(symbols.decimalSeparator)
            sb.append(localizeDigits(fraction.toString().padStart(exponent, '0'), symbols.zeroDigit))
        }
        return sb.toString()
    }

    /** ASCII digits → the locale's digits ([zero] is its digit zero; the ten digits are consecutive code points). */
    private fun localizeDigits(text: String, zero: Char): String =
        if (zero == '0') text else String(CharArray(text.length) { i -> text[i].let { c -> if (c in '0'..'9') zero + (c - '0') else c } })

    /** [format] with `indicative = true`, for derived/approximate home-currency conversions. */
    fun formatIndicative(money: Money, locale: Locale = Locale.getDefault(), homeCurrency: String? = null): String =
        format(money, locale, homeCurrency, indicative = true)

    /**
     * The ISO currency this [locale]'s country conventionally uses, best-effort. Never assumes a country: with no
     * usable country the result matches no currency, so every amount is shown with its ISO code.
     */
    private fun currencyForLocale(locale: Locale): String = try {
        java.util.Currency.getInstance(locale)?.currencyCode ?: NO_CURRENCY
    } catch (e: IllegalArgumentException) {
        NO_CURRENCY
    }

    /** ISO 4217's "no currency" code. */
    private const val NO_CURRENCY = "XXX"

    private fun groupWestern(digits: String, separator: Char): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        val firstGroupLen = digits.length % 3
        var idx = 0
        if (firstGroupLen != 0) {
            sb.append(digits, 0, firstGroupLen)
            idx = firstGroupLen
        }
        while (idx < digits.length) {
            if (sb.isNotEmpty()) sb.append(separator)
            sb.append(digits, idx, idx + 3)
            idx += 3
        }
        return sb.toString()
    }

    /** Indian numbering: last 3 digits, then groups of 2, e.g. 12345678 -> "1,23,45,678". */
    private fun groupIndian(digits: String, separator: Char): String {
        if (digits.length <= 3) return digits
        val lastThree = digits.substring(digits.length - 3)
        var rest = digits.substring(0, digits.length - 3)
        val groups = ArrayDeque<String>()
        while (rest.length > 2) {
            groups.addFirst(rest.substring(rest.length - 2))
            rest = rest.substring(0, rest.length - 2)
        }
        if (rest.isNotEmpty()) groups.addFirst(rest)
        return groups.joinToString(separator.toString()) + separator + lastThree
    }
}

/** Remembers the device/app locale for [MoneyDisplay] calls from Compose UI. */
@Composable
fun rememberDisplayLocale(): Locale {
    val context: Context = LocalContext.current
    return context.resources.configuration.locales[0] ?: Locale.getDefault()
}
