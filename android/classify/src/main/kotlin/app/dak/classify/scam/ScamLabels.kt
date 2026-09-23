package app.dak.classify.scam

/**
 * How a [ScamVerdict] is stored in a message's label set (the index's existing `labels` column; no schema change):
 *
 * - [LIKELY] (`scam:likely-fake-credit`) or [SUSPICIOUS] (`scam:suspicious`): the level;
 * - `scam-reason:<code>` for every [ScamReason];
 * - `scam-claims:<institution>` for [ScamVerdict.claimedInstitution];
 * - [DISMISSED] (`scam:user-dismissed`): the user said "Not a scam"; no level label is then stored and the message
 *   is never re-flagged.
 *
 * Messages labelled [LIKELY] never create ledger entries or move balances ([excludedFromLedger]).
 */
public object ScamLabels {
    public const val LIKELY: String = "scam:likely-fake-credit"
    public const val SUSPICIOUS: String = "scam:suspicious"
    public const val DISMISSED: String = "scam:user-dismissed"
    public const val REASON_PREFIX: String = "scam-reason:"
    public const val CLAIMS_PREFIX: String = "scam-claims:"

    /** Labels for [verdict] (empty for [ScamLevel.NONE]). */
    public fun toLabels(verdict: ScamVerdict): Set<String> {
        val level = when (verdict.level) {
            ScamLevel.LIKELY_SCAM -> LIKELY
            ScamLevel.SUSPICIOUS -> SUSPICIOUS
            ScamLevel.NONE -> return emptySet()
        }
        return buildSet {
            add(level)
            verdict.reasons.forEach { add(REASON_PREFIX + it.code) }
            verdict.claimedInstitution?.let { add(CLAIMS_PREFIX + it) }
        }
    }

    /** The verdict stored in [labels], or null when none is stored or the user dismissed it. */
    public fun fromLabels(labels: Set<String>): ScamVerdict? {
        if (DISMISSED in labels) return null
        val level = when {
            LIKELY in labels -> ScamLevel.LIKELY_SCAM
            SUSPICIOUS in labels -> ScamLevel.SUSPICIOUS
            else -> return null
        }
        val reasons = labels.filter { it.startsWith(REASON_PREFIX) }
            .mapNotNull { ScamReason.fromCode(it.removePrefix(REASON_PREFIX)) }
            .sortedByDescending { it.weight }
        val claimed = labels.firstOrNull { it.startsWith(CLAIMS_PREFIX) }?.removePrefix(CLAIMS_PREFIX)
        return ScamVerdict(level, reasons, claimed)
    }

    /** True when a message with [labels] must stay out of the ledger (a likely fake credit, not dismissed). */
    public fun excludedFromLedger(labels: Set<String>): Boolean = LIKELY in labels && DISMISSED !in labels

    /** True when [labels] carry a warning to show (not dismissed). */
    public fun isFlagged(labels: Set<String>): Boolean = fromLabels(labels) != null

    /** True when [labels] carry any scam label (level, reason, claim or dismissal). */
    public fun isScamLabel(label: String): Boolean =
        label == LIKELY || label == SUSPICIOUS || label == DISMISSED || label.startsWith(REASON_PREFIX) || label.startsWith(CLAIMS_PREFIX)
}
