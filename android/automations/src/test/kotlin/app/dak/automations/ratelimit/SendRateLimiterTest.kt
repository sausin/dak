package app.dak.automations.ratelimit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendRateLimiterTest {

    @Test
    fun `planSends spreads 45 sends so no 30-minute window ever exceeds 30`() {
        val now = 0L
        val windowMillis = 30 * 60 * 1000L
        val maxSends = 30
        val planned = SendRateLimiter.planSends(count = 45, now = now, maxSends = maxSends, windowMillis = windowMillis)

        assertEquals(45, planned.size)
        assertEquals(planned.sorted(), planned) // strictly non-decreasing
        assertEquals(planned.size, planned.toSet().size) // no two sends at the exact same instant

        // Every possible sliding window of length windowMillis contains at most maxSends planned sends.
        for (t in planned) {
            val windowStart = t - windowMillis
            val countInWindow = planned.count { it > windowStart && it <= t }
            assertTrue(countInWindow <= maxSends, "window ending at $t has $countInWindow sends")
        }

        // The first 30 go out essentially immediately; the rest wait for the window to clear.
        assertTrue(planned.take(30).all { it - now < 1000 })
        assertTrue(planned[30] >= now + windowMillis)
    }

    @Test
    fun `planSends accounts for prior history`() {
        val now = 0L
        val windowMillis = 30 * 60 * 1000L
        // 20 sends already happened just before `now`.
        val history = (0 until 20).map { now - it * 1000L }
        val planned = SendRateLimiter.planSends(count = 15, now = now, history = history, maxSends = 30, windowMillis = windowMillis)

        assertEquals(15, planned.size)
        // Only 10 more fit in the window alongside the 20 history entries; the rest must wait it out.
        val immediate = planned.count { it - now < 1000 }
        assertEquals(10, immediate)
        assertTrue(planned.last() >= now + windowMillis - 20_000L)
    }

    @Test
    fun `planSends of zero or negative count returns empty`() {
        assertTrue(SendRateLimiter.planSends(0, 0L).isEmpty())
        assertTrue(SendRateLimiter.planSends(-3, 0L).isEmpty())
    }

    @Test
    fun `tryAcquire enforces the rolling window statefully`() {
        val limiter = SendRateLimiter(maxSends = 2, windowMillis = 1000L)
        assertTrue(limiter.tryAcquire(0L))
        assertTrue(limiter.tryAcquire(100L))
        assertFalse(limiter.tryAcquire(200L)) // window full
        assertTrue(limiter.tryAcquire(1001L)) // first send has aged out
    }
}
