package app.dak.telephony.cost

/**
 * Whether a text goes to an emergency number (text-to-112/911 and national equivalents). Such a send must never wait
 * behind the app's rate limiter, a bulk/broadcast queue or a cost dialog. Pure (JVM).
 */
object EmergencyDestinations {
    private val classifier: DestinationCostClassifier by lazy { DestinationCostClassifier() }

    /** Numbers every GSM/UMTS/LTE handset treats as emergency numbers whatever the SIM or network (3GPP TS 22.101). */
    private val UNIVERSAL = setOf("112", "911")

    /**
     * True for an emergency destination: 112 / 911 anywhere, or a number libphonenumber lists as an emergency number
     * in the SIM's home region or the serving network's region (e.g. 999, 000, 100/101/102/108 where applicable).
     */
    fun isEmergency(address: String, homeCountryIso: String?, networkCountryIso: String?): Boolean {
        val compact = address.trim().filterNot { it == ' ' || it == '-' }
        if (compact in UNIVERSAL) return true
        return runCatching {
            classifier.classify(compact, homeCountryIso, networkCountryIso, isRoaming = false).kind == CostKind.EMERGENCY
        }.getOrDefault(false)
    }
}
