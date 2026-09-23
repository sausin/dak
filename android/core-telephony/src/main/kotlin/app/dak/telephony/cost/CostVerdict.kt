package app.dak.telephony.cost

/**
 * What sending an SMS to one destination is likely to cost, relative to a normal domestic message.
 * Ordered by precedence: when several apply, [DestinationCostClassifier] reports the first one here.
 */
enum class CostKind(val severity: CostSeverity) {
    /** An emergency number (112, 911…). Never blocked; the UI may add a note that SMS to it may not be supported. */
    EMERGENCY(CostSeverity.INFO),

    /** Premium-rate short code or number: each message can be billed at a much higher rate or subscribe you. */
    PREMIUM_RATE(CostSeverity.STRONG),

    /** Alphanumeric sender id (`VM-HDFCBK`): it cannot receive SMS, so a reply will not be delivered. */
    ALPHANUMERIC(CostSeverity.MILD),

    /** A short code whose tariff libphonenumber does not know (may be premium). */
    UNKNOWN_SHORT_CODE(CostSeverity.MILD),

    /** Destination country differs from the SIM's home country: international SMS rates apply. */
    INTERNATIONAL(CostSeverity.MILD),

    /** Sending while roaming abroad (SIM home country differs from the network country). */
    ROAMING(CostSeverity.MILD),

    /** A short code billed at the standard rate. */
    STANDARD_SHORT_CODE(CostSeverity.INFO),

    /** Toll-free short code or number. */
    TOLL_FREE(CostSeverity.INFO),

    /** An ordinary domestic number. */
    NORMAL(CostSeverity.NONE),
}

/** How loudly the UI should warn. Ordered from quietest to loudest. */
enum class CostSeverity {
    /** Nothing to say. */
    NONE,

    /** Informational only; never ask for confirmation. */
    INFO,

    /** Ask once for confirmation (with "don't ask again"). */
    MILD,

    /** Strong warning before sending; unattended (automation) sends are refused unless pre-approved. */
    STRONG,
}

/**
 * Result of classifying one destination.
 *
 * @property destination the destination exactly as classified (trimmed).
 * @property kind the most important cost property, see [CostKind] for precedence.
 * @property destinationRegion ISO 3166-1 alpha-2 (upper case) region of the destination when known, e.g. "AE" for an
 *   [CostKind.INTERNATIONAL] verdict.
 * @property roaming true when the SIM is roaming abroad, even when [kind] is something more important than
 *   [CostKind.ROAMING] (so the UI can mention both).
 */
data class CostVerdict(
    val destination: String,
    val kind: CostKind,
    val destinationRegion: String? = null,
    val roaming: Boolean = false,
) {
    val severity: CostSeverity get() = kind.severity

    /** True when an interactive send should ask the user to confirm first. */
    val needsConfirmation: Boolean get() = severity >= CostSeverity.MILD
}
