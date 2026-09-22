package app.dak.automations.ratelimit

/**
 * Models Android's default outgoing-SMS throttle (30 messages per rolling 30-minute window on stock
 * AOSP; some OEMs differ, hence [maxSends]/[windowMillis] being configurable) so bulk/scheduled sends
 * spread themselves out instead of tripping it.
 */
public class SendRateLimiter(
    private val maxSends: Int = DEFAULT_MAX_SENDS,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val history = ArrayDeque<Long>()

    /** Stateful convenience: records and allows a send at [now] if the window has room, else refuses it. */
    @Synchronized
    public fun tryAcquire(now: Long): Boolean {
        prune(now)
        if (history.size >= maxSends) return false
        history.addLast(now)
        return true
    }

    @Synchronized
    private fun prune(now: Long) {
        while (history.isNotEmpty() && history.first() <= now - windowMillis) history.removeFirst()
    }

    public companion object {
        public const val DEFAULT_MAX_SENDS: Int = 30
        public const val DEFAULT_WINDOW_MILLIS: Long = 30 * 60 * 1000L

        /**
         * Pure planning: spreads [count] new sends across time, starting no earlier than [now], so that
         * no [windowMillis]-long sliding window (including [history], prior real sends) ever contains
         * more than [maxSends] of them. Returns exactly [count] strictly non-decreasing timestamps.
         */
        public fun planSends(
            count: Int,
            now: Long,
            history: List<Long> = emptyList(),
            maxSends: Int = DEFAULT_MAX_SENDS,
            windowMillis: Long = DEFAULT_WINDOW_MILLIS,
        ): List<Long> {
            if (count <= 0) return emptyList()
            val timestamps = history.sorted().toMutableList()
            val planned = ArrayList<Long>(count)
            var candidate = now
            repeat(count) {
                candidate = earliestSlot(candidate, timestamps, maxSends, windowMillis)
                timestamps += candidate
                planned += candidate
                candidate += 1 // next send is at least 1ms later, so timestamps stay strictly increasing
            }
            return planned
        }

        private fun earliestSlot(from: Long, timestamps: List<Long>, maxSends: Int, windowMillis: Long): Long {
            var candidate = from
            while (true) {
                val windowStart = candidate - windowMillis
                val inWindow = timestamps.filter { it > windowStart && it <= candidate }
                if (inWindow.size < maxSends) return candidate
                // Wait until the earliest send in the current window falls out of it.
                candidate = inWindow.min() + windowMillis + 1
            }
        }
    }
}
