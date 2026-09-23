package app.dak.telephony.send

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

/** Edge cases of [SendRateLimiter] beyond the basic spreading in SendLogicTest / EmergencySendTest. */
class SendRateLimiterTest {

    private class MemoryStore(var saved: List<Long> = emptyList()) : SendRateLimiter.Store {
        var saves = 0
        override fun load(): List<Long> = saved
        override fun save(reservations: List<Long>) {
            saves++
            saved = reservations
        }
    }

    @Test
    fun invalidLimitsAreRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { SendRateLimiter(maxPerWindow = 0) }
        assertFailsWith<IllegalArgumentException> { SendRateLimiter(windowMillis = 0) }
    }

    @Test
    fun everyReservationIsPersistedInOrder() {
        val store = MemoryStore()
        val limiter = SendRateLimiter(maxPerWindow = 2, windowMillis = 100, store = store)
        limiter.reserve(50)
        limiter.reserveEmergency(10)
        limiter.reserve(20)
        assertEquals(3, store.saves)
        // Sorted, including the future slot of the third send: the window already holds 10 and 50, so it waits until
        // 10 leaves the window at 110.
        assertEquals(listOf(10L, 50L, 110L), store.saved)
    }

    @Test
    fun anUnsortedStoreIsSortedOnLoad() {
        val store = MemoryStore(listOf(90L, 10L, 50L))
        val limiter = SendRateLimiter(maxPerWindow = 3, windowMillis = 100, store = store)
        // Window full; the slot opens when the oldest (10) leaves, not the first listed (90).
        assertEquals(110L - 60L, limiter.reserve(60))
    }

    @Test
    fun expiredPersistedReservationsDoNotBlock() {
        val store = MemoryStore(listOf(0L, 1L, 2L))
        val limiter = SendRateLimiter(maxPerWindow = 3, windowMillis = 100, store = store)
        assertEquals(0L, limiter.reserve(500))
        assertEquals(1, limiter.pending(500))
        assertEquals(listOf(500L), store.saved)
    }

    @Test
    fun reservationsExactlyOneWindowOldHaveLeftIt() {
        val limiter = SendRateLimiter(maxPerWindow = 1, windowMillis = 100)
        assertEquals(0L, limiter.reserve(0))
        assertEquals(1L, limiter.reserve(99))
        // The second send was booked at 100; at 200 both have left the window.
        assertEquals(0L, limiter.reserve(200))
    }

    @Test
    fun aClockThatJumpedBackwardsCannotBlockSendsForever() {
        // Reservations persisted under a clock far in the future (e.g. set to 2099 then corrected).
        val farFuture = 10_000_000L
        val store = MemoryStore(List(3) { farFuture })
        val limiter = SendRateLimiter(maxPerWindow = 3, windowMillis = 100, store = store)
        assertEquals(0L, limiter.reserve(0), "reservations more than 1000 windows ahead are dropped")
        assertEquals(1, limiter.pending(0))
    }

    @Test
    fun aSmallBackwardsStepStillCountsRecentSends() {
        val limiter = SendRateLimiter(maxPerWindow = 2, windowMillis = 1_000)
        limiter.reserve(5_000)
        limiter.reserve(5_000)
        // The clock moved back 3 s (NITZ correction): those two sends still count against the window.
        val delay = limiter.reserve(2_000)
        assertTrue(delay > 0, "delay $delay")
        assertEquals(6_000L, 2_000L + delay)
    }

    @Test
    fun emergencySendsNeverWaitButCountForLaterSends() {
        val limiter = SendRateLimiter(maxPerWindow = 2, windowMillis = 100)
        assertEquals(0L, limiter.reserveEmergency(0))
        assertEquals(0L, limiter.reserveEmergency(0))
        assertEquals(0L, limiter.reserveEmergency(0), "even past the limit")
        assertEquals(3, limiter.pending(0))
        // Two of the three emergency sends must leave the window before an ordinary send fits in.
        assertEquals(100L, limiter.reserve(0))
    }
}
