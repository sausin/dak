package app.dak.automations.broadcast

/**
 * Marks a scheduled send as one copy of a broadcast, stored in the scheduled send's `ruleId` column:
 * `broadcast:<broadcastId>:<recipientIndex>`. The executor uses it to record the sent message key on the
 * [BroadcastRecord]; automation rules never produce this tag (a rule id is a UUID, never `broadcast:`-prefixed).
 */
public data class BroadcastTag(val broadcastId: String, val recipientIndex: Int) {

    public fun encode(): String = "$PREFIX$broadcastId:$recipientIndex"

    public companion object {
        private const val PREFIX = "broadcast:"

        /** True when [ruleId] is a broadcast tag. */
        public fun isBroadcast(ruleId: String?): Boolean = ruleId != null && ruleId.startsWith(PREFIX)

        /** Parses [ruleId], or null when it is not a broadcast tag. */
        public fun decode(ruleId: String?): BroadcastTag? {
            if (!isBroadcast(ruleId)) return null
            val rest = ruleId!!.removePrefix(PREFIX)
            val cut = rest.lastIndexOf(':')
            if (cut <= 0) return null
            val index = rest.substring(cut + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return BroadcastTag(rest.substring(0, cut), index)
        }
    }
}

/**
 * The "Use broadcasts with care" terms. Bump [VERSION] whenever the sheet's wording changes materially: users who
 * accepted an older version are asked again.
 */
public object BroadcastTerms {
    public const val VERSION: Int = 1

    /** True when the user must (re-)accept before using broadcasts. */
    public fun needsAcceptance(acceptedVersion: Int?): Boolean = acceptedVersion == null || acceptedVersion < VERSION
}
