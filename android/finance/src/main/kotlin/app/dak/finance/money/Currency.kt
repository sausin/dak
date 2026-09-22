package app.dak.finance.money

/**
 * A small, self-contained ISO 4217 table: minor-unit exponent and default display symbol per
 * currency. This module intentionally does not depend on `java.util.Currency` so behaviour is
 * identical across JVMs and Android API levels.
 */
object CurrencyTable {

    /** Currencies whose minor unit has zero decimal digits (e.g. Japanese yen). */
    private val zeroDecimal = setOf(
        "JPY", "KRW", "VND", "CLP", "ISK", "HUF", "TWD", "UGX", "VUV", "XAF", "XOF", "XPF",
        "GNF", "PYG", "RWF", "BIF", "DJF", "KMF", "MGA",
    )

    /** Currencies whose minor unit has three decimal digits (dinar-family). */
    private val threeDecimal = setOf("KWD", "BHD", "OMR", "JOD", "TND", "LYD", "IQD")

    /**
     * Number of digits after the decimal point for [currency]'s minor unit. Defaults to 2, the
     * ISO 4217 default, for any currency not explicitly listed.
     */
    fun minorUnitExponent(currency: String): Int = when (currency.uppercase()) {
        in zeroDecimal -> 0
        in threeDecimal -> 3
        else -> 2
    }

    /** 10^exponent, precomputed for convenience. */
    fun minorUnitScale(currency: String): Long {
        var scale = 1L
        repeat(minorUnitExponent(currency)) { scale *= 10 }
        return scale
    }

    /** Default display symbol for a currency code, falling back to the code itself. */
    fun symbolFor(currency: String): String = when (currency.uppercase()) {
        "INR" -> "₹"
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "JPY" -> "¥"
        "CNY" -> "¥"
        "KRW" -> "₩"
        else -> currency.uppercase()
    }

    /**
     * Known currency-symbol -> ISO code defaults, used by [MoneyParser] when a symbol (rather
     * than an ISO code) is written in an SMS. Ambiguous symbols such as `$` default to USD but
     * are configurable by passing a different [MoneyParser.SymbolResolver].
     */
    val defaultSymbolToCurrency: Map<String, String> = mapOf(
        "₹" to "INR",
        "Rs" to "INR",
        "Rs." to "INR",
        "INR" to "INR",
        "$" to "USD",
        "€" to "EUR",
        "£" to "GBP",
        "¥" to "JPY",
    )

    /** Currency codes this table recognises as a bare ISO alphabetic code. */
    val knownIsoCodes: Set<String> = setOf(
        "INR", "USD", "EUR", "GBP", "JPY", "AED", "AUD", "CAD", "CHF", "CNY", "SGD", "THB",
        "MYR", "HKD", "NZD", "SAR", "QAR", "KWD", "BHD", "OMR", "JOD", "ZAR", "SEK", "NOK",
        "DKK", "PLN", "RUB", "KRW", "IDR", "PHP", "VND", "TRY", "EGP", "NPR", "LKR", "BDT",
        "PKR", "MVR", "BTN",
    )
}
