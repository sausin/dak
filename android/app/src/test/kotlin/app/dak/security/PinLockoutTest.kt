package app.dak.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinLockoutTest {
    private fun at(elapsed: Long, wall: Long = 1_700_000_000_000L + elapsed, boot: Int = 7) = ClockReading(wall, elapsed, boot)

    @Test
    fun `schedule is free for five failures then escalates and caps`() {
        (0..4).forEach { assertEquals(0L, LockoutSchedule.lockoutMillisAfter(it)) }
        assertEquals(30_000L, LockoutSchedule.lockoutMillisAfter(5))
        assertEquals(60_000L, LockoutSchedule.lockoutMillisAfter(6))
        assertEquals(300_000L, LockoutSchedule.lockoutMillisAfter(7))
        assertEquals(900_000L, LockoutSchedule.lockoutMillisAfter(8))
        assertEquals(1_800_000L, LockoutSchedule.lockoutMillisAfter(9))
        assertEquals(3_600_000L, LockoutSchedule.lockoutMillisAfter(10))
        assertEquals(3_600_000L, LockoutSchedule.lockoutMillisAfter(50))
    }

    @Test
    fun `fifth failure starts a 30 second lockout measured on the monotonic clock`() {
        var state = LockoutState.NONE
        repeat(4) { state = state.afterFailure(at(1_000L * it)) }
        assertEquals(0L, state.remainingMillis(at(5_000)))
        assertEquals(0, state.attemptsBeforeLockout)
        state = state.afterFailure(at(10_000))
        assertEquals(30_000L, state.remainingMillis(at(10_000)))
        assertEquals(20_000L, state.remainingMillis(at(20_000)))
        assertEquals(0L, state.remainingMillis(at(40_000)))
    }

    @Test
    fun `attempts before lockout counts down`() {
        var state = LockoutState.NONE
        assertEquals(4, state.attemptsBeforeLockout)
        state = state.afterFailure(at(0))
        assertEquals(3, state.attemptsBeforeLockout)
    }

    @Test
    fun `changing the wall clock within a boot does not shorten the lockout`() {
        var state = LockoutState(failures = 5)
        state = state.afterFailure(at(elapsed = 1_000, wall = 5_000_000))
        assertEquals(60_000L, state.lockoutMillis)
        // Wall clock jumped a day ahead, monotonic clock only 1 s.
        assertEquals(59_000L, state.remainingMillis(ClockReading(wallMillis = 5_000_000 + 86_400_000, elapsedMillis = 2_000, bootId = 7)))
    }

    @Test
    fun `after a reboot the wall clock is used and setting it back never extends the lockout`() {
        val state = LockoutState(failures = 6).afterFailure(at(elapsed = 50_000, wall = 10_000_000))
        assertEquals(300_000L, state.lockoutMillis)
        // Rebooted: elapsed restarted, boot id changed; 100 s of wall time passed.
        assertEquals(200_000L, state.remainingMillis(ClockReading(10_100_000, 3_000, 8)))
        // Wall clock set back before the lockout started: remaining is capped at the full lockout.
        assertEquals(300_000L, state.remainingMillis(ClockReading(9_000_000, 3_000, 8)))
    }

    @Test
    fun `state survives encoding`() {
        val state = LockoutState(failures = 5).afterFailure(at(12_345))
        assertEquals(state, LockoutState.decode(state.encode()))
        val clean = LockoutState(failures = 2)
        assertEquals(clean, LockoutState.decode(clean.encode()))
        assertEquals(LockoutState.NONE, LockoutState.decode(null))
        assertEquals(LockoutState.NONE, LockoutState.decode(""))
    }

    @Test
    fun `corrupt state fails closed`() {
        val decoded = LockoutState.decode("garbage")
        assertEquals(LockoutSchedule.FREE_ATTEMPTS, decoded.failures)
        assertNull(decoded.startedAt)
        // The very next failure locks.
        assertEquals(60_000L, decoded.afterFailure(at(0)).lockoutMillis)
    }
}
