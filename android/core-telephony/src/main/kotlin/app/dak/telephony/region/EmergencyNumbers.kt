package app.dak.telephony.region

import com.google.i18n.phonenumbers.ShortNumberInfo

/**
 * General emergency numbers for a region, confirmed against libphonenumber's emergency-number data rather than a
 * hand-kept table, so Dak never shows a number it cannot vouch for. Only general-purpose numbers are considered
 * (police/fire/ambulance in one): `911`, `999`, `000`, `111`, `112`. For an unknown region the answer is `112`, which
 * 3GPP requires every GSM/UMTS/LTE handset to treat as an emergency call. These are emergency numbers only, never
 * fraud or bank lines. Pure (JVM).
 */
object EmergencyNumbers {

    /** Candidates in display order: the national number first where one exists, 112 last. */
    private val CANDIDATES = listOf("911", "999", "000", "111", "112")

    private val shortInfo: ShortNumberInfo by lazy { ShortNumberInfo.getInstance() }

    /** Emergency numbers for [countryIso] (any case), national first; `["112"]` when the region is unknown. */
    fun forRegion(countryIso: String?): List<String> {
        val region = RegionResolver.normalize(countryIso) ?: return listOf(GSM_EMERGENCY)
        return CANDIDATES.filter { number ->
            runCatching { shortInfo.isEmergencyNumber(number, region) }.getOrDefault(false)
        }
    }

    /** The emergency number every GSM-family handset supports. */
    const val GSM_EMERGENCY = "112"
}
