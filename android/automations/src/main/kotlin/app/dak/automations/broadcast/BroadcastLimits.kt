package app.dak.automations.broadcast

import app.dak.automations.ratelimit.SendRateLimiter

/**
 * Guardrails for broadcasts. The hard caps cannot be raised: constructor values above them are clamped down, so no
 * setting or caller can turn Dak into a bulk-SMS tool.
 *
 * @property maxRecipients per broadcast (and per list).
 * @property maxMessagesPerDay across all lists, in any rolling 24 hours.
 * @property maxScheduleAheadMillis how far ahead the single optional send time may be.
 */
public class BroadcastLimits(
    maxRecipients: Int = HARD_MAX_RECIPIENTS,
    maxMessagesPerDay: Int = HARD_MAX_MESSAGES_PER_DAY,
    public val maxScheduleAheadMillis: Long = DEFAULT_MAX_SCHEDULE_AHEAD_MILLIS,
) {
    public val maxRecipients: Int = maxRecipients.coerceIn(1, HARD_MAX_RECIPIENTS)
    public val maxMessagesPerDay: Int = maxMessagesPerDay.coerceIn(1, HARD_MAX_MESSAGES_PER_DAY)

    public companion object {
        public const val HARD_MAX_RECIPIENTS: Int = 50
        public const val HARD_MAX_MESSAGES_PER_DAY: Int = 100
        public const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
        public const val DEFAULT_MAX_SCHEDULE_AHEAD_MILLIS: Long = 30L * DAY_MILLIS
    }
}

/**
 * How a broadcast's copies are spread out: [batchSize] copies every [batchIntervalMillis] (10 per 10 minutes by
 * default, gentler on the operator than a burst), never more than [maxSends] in any [windowMillis] (Android's own
 * 30 per 30 minutes, [SendRateLimiter]). Copies in one batch share a send time, so one alarm covers the batch.
 */
public data class BroadcastPacing(
    val batchSize: Int = 10,
    val batchIntervalMillis: Long = 10L * 60 * 1000,
    val maxSends: Int = SendRateLimiter.DEFAULT_MAX_SENDS,
    val windowMillis: Long = SendRateLimiter.DEFAULT_WINDOW_MILLIS,
) {
    /**
     * Send times for [count] copies starting at [startAtMillis], respecting [history] (recent real sends): strictly
     * non-decreasing, batched, and never more than [maxSends] per sliding [windowMillis].
     */
    public fun schedule(count: Int, startAtMillis: Long, history: List<Long> = emptyList()): List<Long> {
        if (count <= 0) return emptyList()
        val size = batchSize.coerceAtLeast(1)
        val interval = batchIntervalMillis.coerceAtLeast(0L)
        val taken = history.sorted().toMutableList()
        val out = ArrayList<Long>(count)
        var previous = startAtMillis
        for (i in 0 until count) {
            val batchTime = maxOf(startAtMillis + (i / size) * interval, previous)
            val slot = SendRateLimiter.planSends(1, batchTime, taken, maxSends, windowMillis).first()
            taken += slot
            out += slot
            previous = slot
        }
        return out
    }
}

/** The rolling daily quota across all lists. */
public object BroadcastQuota {
    /** Copies (not cancelled) of broadcasts created in the 24 hours before [nowMillis]. */
    public fun usedInLastDay(records: Collection<BroadcastRecord>, nowMillis: Long): Int =
        records.filter { it.createdAt > nowMillis - BroadcastLimits.DAY_MILLIS && it.createdAt <= nowMillis }
            .sumOf { it.countedMessages }

    /** How many more broadcast copies may be sent now. */
    public fun remaining(records: Collection<BroadcastRecord>, nowMillis: Long, limits: BroadcastLimits = BroadcastLimits()): Int =
        (limits.maxMessagesPerDay - usedInLastDay(records, nowMillis)).coerceAtLeast(0)
}
