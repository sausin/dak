package app.dak.telephony.region

import java.util.Locale

/**
 * Picks the region from what the phone reports, in order of reliability: the SIM's home country (or the user's
 * override for it), then the registered network's country, then the device locale's country. Never assumes a
 * country: when none of them is a real ISO 3166-1 alpha-2 code the result is [RegionProfile.UNKNOWN]. Pure (JVM).
 */
object RegionResolver {

    private val isoCountries: Set<String> by lazy { Locale.getISOCountries().toSet() }

    /**
     * @param simCountryIso home country of the SIM (the default SMS SIM, or the SIM a message arrived on), any case.
     * @param networkCountryIso country of the network currently registered on, any case.
     * @param localeCountry the device locale's country (`Locale.getDefault().country`).
     */
    fun resolve(simCountryIso: String?, networkCountryIso: String?, localeCountry: String?): RegionProfile {
        normalize(simCountryIso)?.let { return RegionProfile(it, RegionSource.SIM) }
        normalize(networkCountryIso)?.let { return RegionProfile(it, RegionSource.NETWORK) }
        normalize(localeCountry)?.let { return RegionProfile(it, RegionSource.LOCALE) }
        return RegionProfile.UNKNOWN
    }

    /** [raw] as an upper-case ISO 3166-1 alpha-2 code, or null if it is blank, malformed or not a known country. */
    fun normalize(raw: String?): String? {
        val code = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null
        return code.takeIf { it in isoCountries }
    }
}
