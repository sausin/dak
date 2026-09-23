package app.dak.classify

/** A minimal view of a recent message, enough to decide OTP duplication. */
public data class RecentMessage(
    val address: String,
    val body: String,
    val timeMillis: Long,
)

/**
 * Decides whether a newly-arrived OTP message is a duplicate of one recently seen from the same
 * sender (collapsed via [SenderId.mergeKey]) with identical body text, within a time window.
 */
public class DuplicateOtpCollapser(private val windowMillis: Long) {

    public constructor(windowMinutes: Int = 5) : this(windowMinutes * 60_000L)

    /**
     * Returns the earlier [RecentMessage] that [candidateAddress]/[candidateBody] duplicates, if
     * any exists in [recent] within the window ending at [nowMillis]; null if this is a fresh OTP.
     */
    public fun findDuplicate(
        candidateAddress: String,
        candidateBody: String,
        nowMillis: Long,
        recent: List<RecentMessage>,
    ): RecentMessage? {
        val key = SenderId.mergeKey(candidateAddress)
        return recent.firstOrNull { msg ->
            SenderId.mergeKey(msg.address) == key &&
                msg.body == candidateBody &&
                (nowMillis - msg.timeMillis) in 0..windowMillis
        }
    }

    public fun isDuplicate(
        candidateAddress: String,
        candidateBody: String,
        nowMillis: Long,
        recent: List<RecentMessage>,
    ): Boolean = findDuplicate(candidateAddress, candidateBody, nowMillis, recent) != null
}
