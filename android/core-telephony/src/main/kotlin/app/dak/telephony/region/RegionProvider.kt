package app.dak.telephony.region

/**
 * Supplies the [RegionProfile] in effect. Inject this rather than reading a country directly, so tests can fake it
 * and nothing in the app hard-codes one. Implemented by [TelephonyRegionProvider] (bound in `TelephonyBindingsModule`).
 */
interface RegionProvider {
    /** The region of the default SMS SIM (then the network, then the locale). Cheap: cached briefly. */
    fun current(): RegionProfile

    /**
     * The region for messages on SIM [subId]: that SIM's home country when known (an Indian SIM in a dual-SIM phone
     * keeps India's sender rules even when the default SMS SIM is British), else [current].
     */
    fun forSubId(subId: Int): RegionProfile = current()
}

/** A fixed region, for tests and previews. */
class FixedRegionProvider(private val profile: RegionProfile) : RegionProvider {
    override fun current(): RegionProfile = profile
}
