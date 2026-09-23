package app.dak.telephony.send

/**
 * Spreads sends so we stay under the platform's per-app SMS limit (`SmsUsageMonitor`: by default 30 messages per
 * check period; exceeding it makes the system show a confirmation dialog or fail with RESULT_ERROR_LIMIT_EXCEEDED).
 * The window used here (30 per 30 minutes) is deliberately at least as strict as the platform default; verify the
 * current `sms_outgoing_check_interval_ms` / `sms_outgoing_check_max_count` values per OEM before loosening it.
 *
 * [reserve] books the earliest slot at or after `now` that keeps every window of [windowMillis] at or below
 * [maxPerWindow] reservations, and returns how long to wait until it. Slots are booked immediately so a burst of
 * bulk sends is spread deterministically instead of racing.
 *
 * [reserveEmergency] never waits: a text to an emergency number goes out at once, whatever is queued. It is still
 * recorded, so ordinary sends that follow keep the platform counter in check.
 *
 * The platform's counter lives in the phone process and is **not** reset when Dak's process dies, so reservations
 * are handed to [store] on every change and reloaded at construction: a restart does not reopen a full window.
 */
class SendRateLimiter(
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val store: Store? = null,
) {
    /** Persistence for reservation times (epoch millis). Implementations must not throw. */
    interface Store {
        fun load(): List<Long>
        fun save(reservations: List<Long>)
    }

    private val reservations = ArrayList<Long>()

    init {
        require(maxPerWindow > 0) { "maxPerWindow must be positive" }
        require(windowMillis > 0) { "windowMillis must be positive" }
        store?.load()?.sorted()?.let(reservations::addAll)
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
        insert(slot)
        return slot - nowMillis
    }

    /** Records an emergency send at [nowMillis] without ever delaying it; always returns 0. */
    @Synchronized
    fun reserveEmergency(nowMillis: Long): Long {
        prune(nowMillis)
        insert(nowMillis)
        return 0L
    }

    /** Number of reservations in the window ending at [nowMillis], including future-booked ones. */
    @Synchronized
    fun pending(nowMillis: Long): Int {
        prune(nowMillis)
        return reservations.size
    }

    private fun insert(slot: Long) {
        val index = reservations.binarySearch(slot).let { if (it < 0) -it - 1 else it }
        reservations.add(index, slot)
        store?.save(ArrayList(reservations))
    }

    private fun prune(nowMillis: Long) {
        val cutoff = nowMillis - windowMillis
        while (reservations.isNotEmpty() && reservations[0] <= cutoff) reservations.removeAt(0)
        // A clock moved backwards leaves reservations far in the future: never let them block sends for longer than
        // a window.
        while (reservations.isNotEmpty() && reservations.last() > nowMillis + MAX_FUTURE_WINDOWS * windowMillis) {
            reservations.removeAt(reservations.lastIndex)
        }
    }

    companion object {
        const val DEFAULT_MAX_PER_WINDOW: Int = 30
        const val DEFAULT_WINDOW_MILLIS: Long = 30 * 60 * 1000L

        /** Bulk sends may book this many windows ahead; anything further out is a clock artefact. */
        private const val MAX_FUTURE_WINDOWS = 1_000L
    }
}
