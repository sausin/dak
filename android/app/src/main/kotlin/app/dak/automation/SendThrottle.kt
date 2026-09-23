package app.dak.automation

import app.dak.automations.ratelimit.SendRateLimiter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide reservation of outgoing automated sends against Android's 30-per-30-minutes SMS limit, built on
 * [SendRateLimiter.planSends]. Forwarding rules and scheduled sends ask for a slot; when the window is full the
 * returned time is in the future and the caller schedules the send for then instead of sending inline.
 */
@Singleton
class SendThrottle @Inject constructor() {
    private val history = ArrayList<Long>()

    /** Reserves one send and returns the earliest time (>= [now]) it may go out. */
    @Synchronized
    fun reserve(now: Long = System.currentTimeMillis()): Long {
        history.removeAll { it <= now - SendRateLimiter.DEFAULT_WINDOW_MILLIS }
        val slot = SendRateLimiter.planSends(count = 1, now = now, history = history).first()
        history += slot
        return slot
    }

    /** Reserves [count] sends at once (bulk), spread so no window exceeds the limit. */
    @Synchronized
    fun reserveBulk(count: Int, now: Long = System.currentTimeMillis()): List<Long> {
        history.removeAll { it <= now - SendRateLimiter.DEFAULT_WINDOW_MILLIS }
        val slots = SendRateLimiter.planSends(count = count, now = now, history = history)
        history += slots
        return slots
    }
}
