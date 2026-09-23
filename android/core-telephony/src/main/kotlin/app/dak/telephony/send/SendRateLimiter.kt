package app.dak.telephony.send

/**
 * Spreads sends so we never exceed the platform's default SMS limit (30 messages per 30 minutes per app;
 * exceeding it makes the system show a confirmation dialog or fail with RESULT_ERROR_LIMIT_EXCEEDED).
 *
 * [reserve] books the earliest slot at or after `now` that keeps every window of [windowMillis] at or below
 * [maxPerWindow] reservations, and returns how long to wait until it. Slots are booked immediately so a burst of
 * bulk sends is spread deterministically instead of racing. In-memory only: after process death the platform's
 * own counter is also reset for us, and queued sends re-reserve when they run.
 */
class SendRateLimiter(
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val reservations = ArrayList<Long>()

    init {
        require(maxPerWindow > 0) { "maxPerWindow must be positive" }
        require(windowMillis > 0) { "windowMillis must be positive" }
    }

    /** Books a slot and returns the delay in milliseconds until it (0 = send now). */
    @Synchronized
    fun reserve(nowMillis: Long): Long {
        prune(nowMillis)
        val slot = if (reservations.size < maxPerWindow) {
            nowMillis
        } else {
            // The slot opens when the max-th most recent reservation leaves the window.
            maxOf(nowMillis, reservations[reservations.size - maxPerWindow] + windowMillis)
        }
        val index = reservations.binarySearch(slot).let { if (it < 0) -it - 1 else it }
        reservations.add(index, slot)
        return slot - nowMillis
    }

    /** Number of reservations in the window ending at [nowMillis], including future-booked ones. */
    @Synchronized
    fun pending(nowMillis: Long): Int {
        prune(nowMillis)
        return reservations.size
    }

    private fun prune(nowMillis: Long) {
        val cutoff = nowMillis - windowMillis
        while (reservations.isNotEmpty() && reservations[0] <= cutoff) reservations.removeAt(0)
    }

    companion object {
        const val DEFAULT_MAX_PER_WINDOW: Int = 30
        const val DEFAULT_WINDOW_MILLIS: Long = 30 * 60 * 1000L
    }
}
