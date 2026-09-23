package app.dak.telephony.region

import java.util.Currency
import java.util.Locale

/** Where a [RegionProfile]'s country came from, most to least reliable. */
enum class RegionSource {
    /** The user's per-SIM home-country override, or the SIM's own country (its home country, not the network). */
    SIM,

    /** The country of the network the phone is registered on (can be a roaming network). */
    NETWORK,

    /** The device locale's country. */
    LOCALE,

    /** Nothing usable: generic behaviour only. */
    NONE,
}

/**
 * The country Dak tailors region-specific behaviour to: home currency, digit grouping, the "$" reading, India's
 * DLT sender rules and the TRAI / Chakshu report flows, which helplines to show. Dak launches India-first, with
 * India as the richest profile; every other country (and an unknown one) gets generic, country-neutral behaviour.
 * Resolve it with [RegionResolver]; inject it through [RegionProvider].
 *
 * @property countryIso ISO 3166-1 alpha-2, upper case; null when unknown ([RegionSource.NONE]).
 */
data class RegionProfile(val countryIso: String?, val source: RegionSource) {

    /** India: DLT sender headers, TRAI 1909 spam reports, Chakshu / cybercrime.gov.in and the India helplines. */
    val isIndia: Boolean get() = countryIso == INDIA

    /** India's TRAI DLT sender-header rules apply to messages on this SIM. */
    val dltSenderRules: Boolean get() = isIndia

    /**
     * The country's currency (ISO 4217), from the JDK's locale data (`Currency.getInstance(Locale("", iso))`); null
     * when the region is unknown or has no currency of its own (e.g. Antarctica).
     */
    val homeCurrency: String? get() = currencyOf(countryIso)

    /** Indian digit grouping (1,23,45,678) for amounts in the home currency: only where that is the rupee. */
    val usesIndianGrouping: Boolean get() = homeCurrency == "INR"

    /**
     * What a bare `$` in an SMS means here: the home currency where it is written `$` (USD in the US, CAD in Canada,
     * AUD, NZD, SGD, HKD, and the peso countries that also use the sign), else USD. Foreign amounts are always
     * displayed with their ISO code, so a wrong guess is visible rather than silent.
     */
    val dollarCurrency: String get() = homeCurrency?.takeIf { it in DOLLAR_SIGN_CURRENCIES } ?: "USD"

    companion object {
        const val INDIA = "IN"

        /** Nothing known. */
        val UNKNOWN = RegionProfile(null, RegionSource.NONE)

        /** Currencies written with a bare `$` at home. */
        val DOLLAR_SIGN_CURRENCIES: Set<String> = setOf(
            "USD", "CAD", "AUD", "NZD", "SGD", "HKD", "TWD", "MXN", "ARS", "CLP", "COP", "BSD", "BBD", "BZD", "BMD",
            "BND", "FJD", "GYD", "JMD", "KYD", "LRD", "NAD", "SBD", "SRD", "TTD", "XCD",
        )

        /** ISO 4217 currency of [countryIso], or null (unknown country, or no currency). */
        fun currencyOf(countryIso: String?): String? {
            val iso = countryIso ?: return null
            return try {
                Currency.getInstance(Locale("", iso))?.currencyCode?.takeIf { it != "XXX" }
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
