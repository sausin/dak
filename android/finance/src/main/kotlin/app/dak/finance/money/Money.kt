package app.dak.finance.money

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An exact monetary amount: an integer count of minor units (paise, cents, fils, ...) plus an
 * ISO 4217 currency code. Never store amounts as [Double]; this type exists precisely so nothing
 * else in the finance module has to.
 */
@Serializable
data class Money(val amountMinor: Long, val currency: String) : Comparable<Money> {

    init {
        require(currency.isNotBlank()) { "currency must not be blank" }
    }

    val currencyUpper: String get() = currency.uppercase()

    /** The amount as a [BigDecimal] in major units (e.g. rupees, not paise). */
    fun toBigDecimal(): BigDecimal {
        val scale = CurrencyTable.minorUnitExponent(currencyUpper)
        return BigDecimal(amountMinor).movePointLeft(scale)
    }

    operator fun plus(other: Money): Money {
        require(other.currencyUpper == currencyUpper) {
            "cannot add $other to $this: currency mismatch"
        }
        return Money(amountMinor + other.amountMinor, currencyUpper)
    }

    operator fun minus(other: Money): Money {
        require(other.currencyUpper == currencyUpper) {
            "cannot subtract $other from $this: currency mismatch"
        }
        return Money(amountMinor - other.amountMinor, currencyUpper)
    }

    operator fun unaryMinus(): Money = Money(-amountMinor, currencyUpper)

    /** Absolute value, same currency. */
    fun abs(): Money = if (amountMinor < 0) -this else this

    override fun compareTo(other: Money): Int {
        require(other.currencyUpper == currencyUpper) {
            "cannot compare $other to $this: currency mismatch"
        }
        return amountMinor.compareTo(other.amountMinor)
    }

    /**
     * Formats the amount for display, e.g. "₹1,23,456.78" (INR, Indian digit grouping) or
     * "$1,234.50" (Western grouping). Negative amounts are prefixed with "-" before the symbol.
     */
    fun format(withSymbol: Boolean = true, grouping: Boolean = true): String {
        val exponent = CurrencyTable.minorUnitExponent(currencyUpper)
        val negative = amountMinor < 0
        val absMinor = kotlin.math.abs(amountMinor)
        val scale = CurrencyTable.minorUnitScale(currencyUpper)
        val whole = if (scale == 1L) absMinor else absMinor / scale
        val fraction = if (scale == 1L) 0L else absMinor % scale

        val wholeStr = whole.toString()
        val groupedWhole = if (grouping) {
            if (currencyUpper == "INR") groupIndian(wholeStr) else groupWestern(wholeStr)
        } else {
            wholeStr
        }

        val sb = StringBuilder()
        if (negative) sb.append('-')
        if (withSymbol) sb.append(CurrencyTable.symbolFor(currencyUpper))
        sb.append(groupedWhole)
        if (exponent > 0) {
            sb.append('.')
            sb.append(fraction.toString().padStart(exponent, '0'))
        }
        return sb.toString()
    }

    /** "≈" (approximately) formatted amount, used for indicative home-currency values. */
    fun formatIndicative(): String = "≈${format()}"

    override fun toString(): String = format()

    companion object {
        /** Zero amount in [currency]. */
        fun zero(currency: String): Money = Money(0L, currency.uppercase())

        /** Builds a [Money] from a major-unit decimal amount, e.g. `ofMajor(BigDecimal("42.5"), "USD")`. */
        fun ofMajor(amount: BigDecimal, currency: String): Money {
            val upper = currency.uppercase()
            val scale = CurrencyTable.minorUnitExponent(upper)
            val minor = amount.setScale(scale, RoundingMode.HALF_UP).movePointRight(scale).longValueExact()
            return Money(minor, upper)
        }

        /** Converts [amount] (in [from]'s home currency) to [to] using [rate] (units of `to` per 1 unit of `from`). */
        fun convert(amount: Money, to: String, rate: BigDecimal): Money {
            val converted = amount.toBigDecimal().multiply(rate)
            return ofMajor(converted, to)
        }

        private fun groupWestern(digits: String): String {
            if (digits.length <= 3) return digits
            val sb = StringBuilder()
            val firstGroupLen = digits.length % 3
            var idx = 0
            if (firstGroupLen != 0) {
                sb.append(digits, 0, firstGroupLen)
                idx = firstGroupLen
            }
            while (idx < digits.length) {
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(digits, idx, idx + 3)
                idx += 3
            }
            return sb.toString()
        }

        /** Indian numbering: last 3 digits, then groups of 2, e.g. 12345678 -> "1,23,45,678". */
        private fun groupIndian(digits: String): String {
            if (digits.length <= 3) return digits
            val lastThree = digits.substring(digits.length - 3)
            var rest = digits.substring(0, digits.length - 3)
            val groups = ArrayDeque<String>()
            while (rest.length > 2) {
                groups.addFirst(rest.substring(rest.length - 2))
                rest = rest.substring(0, rest.length - 2)
            }
            if (rest.isNotEmpty()) groups.addFirst(rest)
            return groups.joinToString(",") + "," + lastThree
        }
    }
}
