package app.dak.telephony.cost

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.ShortNumberInfo

/**
 * Pure (JVM) SMS cost classification with libphonenumber's `ShortNumberInfo` / `PhoneNumberUtil` metadata — the
 * standard, maintained source for short-code tariffs (premium / standard / toll-free) and number types.
 *
 * Rules:
 * - Short codes are judged in the SIM's **home** region (the home network's SMSC routes them), falling back to the
 *   network region when the SIM country is unknown.
 * - Full numbers whose country calling code differs from the home region's are [CostKind.INTERNATIONAL]
 *   (so +1 US ↔ Canada, sharing a calling code, is not flagged).
 * - Roaming counts only when abroad: `isRoaming` with the network country equal to the home country is domestic
 *   (national) roaming, which Indian operators no longer bill differently for SMS.
 * - Anything the metadata cannot judge is returned as [CostKind.NORMAL] rather than guessed, except short
 *   digit strings, which get the mild [CostKind.UNKNOWN_SHORT_CODE].
 *
 * Thread-safe; construction is cheap (libphonenumber metadata is loaded lazily and shared).
 */
class DestinationCostClassifier {
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }
    private val shortInfo: ShortNumberInfo by lazy { ShortNumberInfo.getInstance() }

    /**
     * Classifies sending one SMS to [destination].
     *
     * @param simCountryIso ISO 3166-1 alpha-2 home country of the sending SIM (any case), or null.
     * @param networkCountryIso ISO country of the network currently serving that SIM, or null.
     * @param isRoaming the platform's roaming flag for that SIM.
     */
    fun classify(destination: String, simCountryIso: String?, networkCountryIso: String?, isRoaming: Boolean): CostVerdict {
        val address = destination.trim()
        val home = region(simCountryIso) ?: region(networkCountryIso)
        val network = region(networkCountryIso)
        val roamingAbroad = isRoaming && (home == null || network == null || home != network)

        fun verdict(kind: CostKind, destRegion: String? = null): CostVerdict {
            val finalKind = if (roamingAbroad && kind.ordinal > CostKind.ROAMING.ordinal) CostKind.ROAMING else kind
            return CostVerdict(address, finalKind, destRegion, roamingAbroad)
        }

        if (address.isEmpty()) return verdict(CostKind.NORMAL)
        if (address.contains('@')) return verdict(CostKind.NORMAL) // e-mail recipient (MMS)
        if (address.any { it.isLetter() }) return CostVerdict(address, CostKind.ALPHANUMERIC, null, roamingAbroad)
        if (address.any { it == '*' || it == '#' }) return verdict(CostKind.NORMAL) // service codes are not SMS targets

        val digits = address.count { it.isDigit() }
        if (digits == 0) return verdict(CostKind.NORMAL)
        val international = address.startsWith("+") || address.filter { it.isDigit() }.startsWith(INTL_PREFIX_00)

        // Before the international check: Australia's 000 starts with the "00" international prefix.
        if (!address.startsWith("+") && isEmergency(address, home, network)) return CostVerdict(address, CostKind.EMERGENCY, home, roamingAbroad)

        val parseRegion = home ?: UNKNOWN_REGION
        val number: PhoneNumber = try {
            util.parse(address, parseRegion)
        } catch (e: NumberParseException) {
            return if (!international && digits < SHORT_CODE_MAX_DIGITS) verdict(CostKind.UNKNOWN_SHORT_CODE) else verdict(CostKind.NORMAL)
        }

        // Short codes: only meaningful in national form, judged in the home region.
        if (!international && home != null && digits < FULL_NUMBER_MIN_DIGITS && !util.isValidNumber(number)) {
            return verdict(shortCodeKind(number, home))
        }
        if (!international && home == null && digits < FULL_NUMBER_MIN_DIGITS) return verdict(CostKind.UNKNOWN_SHORT_CODE)

        val type = util.getNumberType(number)
        if (type == PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE) return verdict(CostKind.PREMIUM_RATE, util.getRegionCodeForNumber(number))

        val destRegion = util.getRegionCodeForNumber(number)?.takeIf { it.length == 2 && it != UNKNOWN_REGION }
        if (home != null && number.countryCode != util.getCountryCodeForRegion(home)) {
            return verdict(CostKind.INTERNATIONAL, destRegion)
        }
        if (type == PhoneNumberUtil.PhoneNumberType.TOLL_FREE) return verdict(CostKind.TOLL_FREE, destRegion)
        return verdict(CostKind.NORMAL, destRegion)
    }

    private fun shortCodeKind(number: PhoneNumber, home: String): CostKind =
        when (shortInfo.getExpectedCostForRegion(number, home)) {
            ShortNumberInfo.ShortNumberCost.PREMIUM_RATE -> CostKind.PREMIUM_RATE
            ShortNumberInfo.ShortNumberCost.STANDARD_RATE -> CostKind.STANDARD_SHORT_CODE
            ShortNumberInfo.ShortNumberCost.TOLL_FREE -> CostKind.TOLL_FREE
            else -> CostKind.UNKNOWN_SHORT_CODE
        }

    private fun isEmergency(address: String, home: String?, network: String?): Boolean =
        listOfNotNull(home, network).distinct().any { region ->
            runCatching { shortInfo.isEmergencyNumber(address, region) }.getOrDefault(false)
        }

    private fun region(iso: String?): String? =
        iso?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }

    private companion object {
        const val UNKNOWN_REGION = "ZZ"
        const val INTL_PREFIX_00 = "00"
        /** Numbers with fewer digits than this are treated as short codes (matches E164Normalizer.MIN_DIGITS). */
        const val FULL_NUMBER_MIN_DIGITS = 7
        /** Unparseable digit strings shorter than this are treated as unknown short codes. */
        const val SHORT_CODE_MAX_DIGITS = 7
    }
}
