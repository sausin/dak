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

    /** Default display symbols, falling back to the ISO code itself for anything not listed. */
    private val symbols: Map<String, String> = mapOf(
        "INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥", "CNY" to "¥",
        "KRW" to "₩", "THB" to "฿", "MYR" to "RM", "IDR" to "Rp", "PHP" to "₱", "VND" to "₫",
        "TRY" to "₺", "BDT" to "৳", "PKR" to "₨", "LKR" to "₨", "NPR" to "रु",
        // Gulf riyal/dinar currencies and ZAR deliberately fall back to their ISO code (no native
        // symbol here) since a bare glyph like "﷼" is itself ambiguous between SAR/QAR/OMR/YER.
    )

    /** Default display symbol for a currency code, falling back to the code itself. */
    fun symbolFor(currency: String): String = symbols[currency.uppercase()] ?: currency.uppercase()

    /**
     * Known currency-symbol/word -> ISO code defaults, used by [MoneyParser] when a symbol or
     * common local word (rather than a bare ISO code) is written in an SMS. Ambiguous symbols
     * such as `$` or `₨` default to the most common reading (USD, PKR respectively) but are
     * configurable by passing a different map.
     */
    val defaultSymbolToCurrency: Map<String, String> = mapOf(
        // India
        "₹" to "INR",
        "Rs" to "INR",
        "Rs." to "INR",
        "INR" to "INR",
        "रु" to "INR",
        "रू" to "INR",
        "रुपये" to "INR",
        "ரூ" to "INR",
        // Bangladesh
        "৳" to "BDT",
        "Tk" to "BDT",
        // Pakistan / Nepal / Sri Lanka all use the "₨" glyph - ambiguous without a code, default
        // to the most common SMS-classifier usage (Pakistan); override per-locale if needed.
        "₨" to "PKR",
        "රු" to "LKR",
        // Gulf
        "د.إ" to "AED",
        "ر.س" to "SAR",
        "﷼" to "SAR",
        // Americas / Oceania / Asia-Pacific dollars - bare "$" stays USD unless a code is given.
        "$" to "USD",
        "US$" to "USD",
        "C$" to "CAD",
        "A$" to "AUD",
        "S$" to "SGD",
        "HK$" to "HKD",
        // Europe
        "€" to "EUR",
        "£" to "GBP",
        // East/Southeast Asia
        "¥" to "JPY",
        "円" to "JPY",
        "元" to "CNY",
        "RMB" to "CNY",
        "₩" to "KRW",
        "฿" to "THB",
        "RM" to "MYR",
        "Rp" to "IDR",
        "₱" to "PHP",
        "₫" to "VND",
        "₺" to "TRY",
    )

    /** Currencies written with a bare `$` at home (a bare `$` in an SMS from that country means that currency). */
    private val dollarSignCurrencies = setOf(
        "USD", "CAD", "AUD", "NZD", "SGD", "HKD", "TWD", "MXN", "ARS", "CLP", "COP", "BSD", "BBD", "BZD", "BMD",
        "BND", "FJD", "GYD", "JMD", "KYD", "LRD", "NAD", "SBD", "SRD", "TTD", "XCD",
    )

    /** Currencies written with the `₨` glyph at home. */
    private val rupeeGlyphCurrencies = setOf("PKR", "NPR", "LKR", "MUR", "SCR")

    /**
     * [defaultSymbolToCurrency] adjusted to the reader's region: with [homeCurrency] `CAD`, a bare `$` means CAD
     * (likewise AUD, SGD, NZD, HKD...), and `₨` means NPR or LKR in Nepal or Sri Lanka. Everything unambiguous
     * (`₹`, `€`, `£`, `C$`, ISO codes) is unchanged, and with no home currency (unknown region) the defaults apply.
     */
    fun symbolMapFor(homeCurrency: String?): Map<String, String> {
        val home = homeCurrency?.trim()?.uppercase()?.takeIf { it.length == 3 } ?: return defaultSymbolToCurrency
        return when (home) {
            in dollarSignCurrencies -> defaultSymbolToCurrency + ("$" to home)
            in rupeeGlyphCurrencies -> defaultSymbolToCurrency + ("₨" to home)
            else -> defaultSymbolToCurrency
        }
    }

    /** Currency codes this table recognises as a bare ISO alphabetic code. */
    val knownIsoCodes: Set<String> = setOf(
        "INR", "USD", "EUR", "GBP", "JPY", "AED", "AUD", "CAD", "CHF", "CNY", "SGD", "THB",
        "MYR", "HKD", "NZD", "SAR", "QAR", "KWD", "BHD", "OMR", "JOD", "ZAR", "SEK", "NOK",
        "DKK", "PLN", "RUB", "KRW", "IDR", "PHP", "VND", "TRY", "EGP", "NPR", "LKR", "BDT",
        "PKR", "MVR", "BTN",
    )
}
