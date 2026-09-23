package app.dak.safety.helplines

/**
 * Which bundled helplines apply where. The bundle lists verified entries per country (India's today); a country
 * without entries gets none, never another country's lines, and the Report fraud screen falls back to certain
 * emergency numbers (`app.dak.telephony.region.EmergencyNumbers`) plus the user's own bank number.
 */
object RegionalHelplines {

    /** Helplines (and card-block lines) of [countryIso] (any case), in bundle order; empty when unknown or unlisted. */
    fun forCountry(payload: HelplinePayload, countryIso: String?): List<Helpline> {
        val iso = countryIso?.trim()?.takeIf { it.length == 2 } ?: return emptyList()
        return (payload.helplines + payload.bankCardBlock).filter { it.country.equals(iso, ignoreCase = true) }
    }

    /** True when [helplines] already include an emergency number (so no generic fallback is needed). */
    fun hasEmergency(helplines: List<Helpline>): Boolean = helplines.any { it.category == HelplineCategory.EMERGENCY }
}
