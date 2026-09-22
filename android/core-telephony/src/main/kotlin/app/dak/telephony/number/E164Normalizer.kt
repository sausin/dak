package app.dak.telephony.number

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Pure (JVM) E.164 normalisation with libphonenumber, given the home country of the SIM in use.
 *
 * Rules (see build plan, "Roaming and language"):
 * - Alphanumeric sender ids (`VM-HDFCBK`), e-mail addresses and USSD/service codes (`*`, `#`) are never touched.
 * - Short codes (fewer than [MIN_DIGITS] digits) are never prefixed.
 * - Numbers already in international form are canonicalised; national numbers get the SIM country's code.
 * - Only numbers libphonenumber validates for that country are rewritten; anything else is returned unchanged
 *   (the network then interprets it exactly as the user typed it).
 */
class E164Normalizer(private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()) {

    /** E.164 form of [address], or [address] unchanged when it must not or cannot be normalised. */
    fun normalize(address: String, countryIso: String?): String {
        val trimmed = address.trim()
        if (!isNormalisable(trimmed)) return address
        val region = countryIso?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?: UNKNOWN_REGION
        return try {
            val number = util.parse(trimmed, region)
            // Only numbers libphonenumber considers valid are rewritten: "possible" is too loose (a 10-digit
            // Indian mobile is a possible UAE number), and a wrong country code silently misroutes the message.
            if (util.isValidNumber(number)) util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164) else address
        } catch (e: NumberParseException) {
            address
        }
    }

    /**
     * Key for matching the same party across spellings: E.164 when possible; otherwise digits only for short
     * codes, and the upper-cased id (spaces removed) for alphanumeric senders.
     */
    fun matchKey(address: String, countryIso: String?): String {
        val normalized = normalize(address, countryIso)
        if (normalized.startsWith("+")) return normalized
        val trimmed = address.trim()
        if (trimmed.contains('@')) return trimmed.lowercase()
        if (trimmed.any { it.isLetter() }) return trimmed.filterNot { it.isWhitespace() }.uppercase()
        val digits = trimmed.filter { it.isDigit() || it == '*' || it == '#' || it == '+' }
        return digits.ifEmpty { trimmed }
    }

    companion object {
        /** Fewer digits than this is a short code and is never prefixed. */
        const val MIN_DIGITS: Int = 7

        private const val UNKNOWN_REGION = "ZZ"
        private const val DIALABLE_SEPARATORS = "+()-. /"

        /** True when [address] looks like a full phone number we may rewrite. */
        fun isNormalisable(address: String): Boolean {
            if (address.isEmpty()) return false
            if (address.any { it.isLetter() || it == '@' || it == '*' || it == '#' }) return false
            if (address.any { !it.isDigit() && DIALABLE_SEPARATORS.indexOf(it) < 0 }) return false
            if (address.indexOf('+', startIndex = 1) >= 0) return false
            return address.count { it.isDigit() } >= MIN_DIGITS
        }
    }
}
