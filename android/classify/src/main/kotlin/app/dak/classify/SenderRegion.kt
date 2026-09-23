package app.dak.classify

/**
 * The country whose SMS sender conventions apply to a message: normally the home country of the SIM it arrived on,
 * resolved by the app (`app.dak.telephony.region.RegionProvider`). Dak launches India-first, but nothing here
 * assumes India: an unknown region gets generic, country-neutral behaviour.
 *
 * What changes with the region:
 * - [dltSenderIds]: India's TRAI DLT sender headers (`VM-HDFCBK-S`) are only meaningful for Indian SIMs; elsewhere a
 *   header-shaped sender (`BT-MOBILE`) is just an alphanumeric name, and no `dlt-*` label or DLT-based trust applies.
 * - [shortCodesSuspicious]: Indian banks send from DLT headers, so a numeric short code posing as a bank is odd; in
 *   the US, UK and most other markets banks routinely use short codes, so they are not suspicious in themselves.
 * - [isLocalMobile]: the "a private person's number" heuristic (a 10-digit `6-9…` mobile in India; any full-length
 *   phone number elsewhere).
 *
 * @property countryIso ISO 3166-1 alpha-2 code, upper case, or null when unknown.
 */
public data class SenderRegion(val countryIso: String?) {

    /** India's DLT sender-header rules (header parsing for trust, traffic-type labels) apply. */
    public val dltSenderIds: Boolean get() = countryIso == INDIA_ISO

    /** A numeric short code sending bank-like content is itself a warning sign (true only where banks use DLT). */
    public val shortCodesSuspicious: Boolean get() = dltSenderIds

    /** True when this region's template-bundle entries tagged with [regions] should be used. */
    public fun matches(regions: List<String>): Boolean =
        regions.isEmpty() || countryIso == null || regions.any { it.equals(countryIso, ignoreCase = true) }

    /**
     * True if [address] looks like a private person's mobile number: in India a 10-digit number starting 6-9
     * (ignoring a `+91`/`0` prefix, see [SenderId.isIndianMobile]); elsewhere any full phone number (8+ digits,
     * optionally `+`-prefixed), since mobile ranges differ per country and this module has no numbering data.
     */
    public fun isLocalMobile(address: String): Boolean {
        if (dltSenderIds) return SenderId.isIndianMobile(address)
        return SenderId.classify(address) == SenderKind.PHONE_NUMBER && address.count { it.isDigit() } >= 8
    }

    public companion object {
        private const val INDIA_ISO = "IN"

        /** No region known: generic behaviour only (no DLT rules, no region filtering of the template bundle). */
        public val UNKNOWN: SenderRegion = SenderRegion(null)

        /** India (DLT rules on). */
        public val INDIA: SenderRegion = SenderRegion(INDIA_ISO)

        /** The region for [countryIso] (any case; blank or malformed codes give [UNKNOWN]). */
        public fun of(countryIso: String?): SenderRegion {
            val iso = countryIso?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            return when (iso) {
                null -> UNKNOWN
                INDIA_ISO -> INDIA
                else -> SenderRegion(iso)
            }
        }
    }
}
