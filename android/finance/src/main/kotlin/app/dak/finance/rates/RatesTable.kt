package app.dak.finance.rates

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * A daily table of mid-market exchange rates against one [base] currency, as published by a
 * source such as the ECB. Bundled as JSON with the OTA template update; see
 * `src/main/resources/app/dak/finance/sample-rates.json` for the shape.
 */
@Serializable
data class RatesTable(
    val base: String,
    /** Date the rates were fetched for, as an ISO-8601 date string (e.g. "2026-09-21"). */
    val date: String,
    /** ISO currency code -> units of that currency per 1 unit of [base]. */
    val rates: Map<String, String>,
) {
    private fun rateOf(currency: String): BigDecimal? {
        val upper = currency.uppercase()
        if (upper == base.uppercase()) return BigDecimal.ONE
        return rates[upper]?.let { BigDecimal(it) }
    }

    /**
     * Units of [to] per 1 unit of [from], derived by triangulating through [base] when neither
     * currency is the base. Returns null if either currency is missing from the table.
     */
    fun rate(from: String, to: String): BigDecimal? {
        if (from.equals(to, ignoreCase = true)) return BigDecimal.ONE
        val fromRate = rateOf(from) ?: return null // base per `from`... actually base->from
        val toRate = rateOf(to) ?: return null
        // rates map is base->currency, i.e. 1 base = fromRate `from`. So 1 `from` = toRate/fromRate `to`.
        return toRate.divide(fromRate, MathContext(12))
    }

    companion object {
        /** A tiny built-in fallback table (approximate, static) used only when no fetched table is available. */
        fun fallback(): RatesTable = RatesTable(
            base = "USD",
            date = "1970-01-01",
            rates = mapOf(
                "USD" to "1",
                "INR" to "83",
                "EUR" to "0.92",
                "GBP" to "0.79",
                "AED" to "3.6725",
                "SGD" to "1.34",
                "THB" to "35.5",
                "JPY" to "150",
            ),
        )
    }
}

/** Rounds [value] to [scale] decimal places using standard half-up rounding, for display purposes. */
fun BigDecimal.roundedTo(scale: Int): BigDecimal = this.setScale(scale, RoundingMode.HALF_UP)
