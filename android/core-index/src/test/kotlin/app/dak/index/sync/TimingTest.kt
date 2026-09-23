package app.dak.index.sync

import app.dak.core.model.Category
import app.dak.index.BinPolicy
import app.dak.index.DefaultBinPolicy
import app.dak.index.bin.BinRetention
import app.dak.index.bin.DeletedBy
import app.dak.index.otp.OtpTiming
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimingTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val night = NightWindow()

    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 9, 22, h, m, 0, 0, zone)

    @Test
    fun nightWindowBounds() {
        assertFalse(night.contains(at(0, 59)))
        assertTrue(night.contains(at(1, 0)))
        assertTrue(night.contains(at(4, 59)))
        assertFalse(night.contains(at(5, 0)))
    }

    @Test
    fun delayUntilOpen() {
        assertEquals(Duration.ZERO, night.delayUntilOpen(at(2)))
        assertEquals(Duration.ofMinutes(30), night.delayUntilOpen(at(0, 30)))
        assertEquals(Duration.ofHours(3), night.delayUntilOpen(at(22)))
        assertEquals(Duration.ofHours(20), night.delayUntilOpen(at(5)))
    }

    @Test
    fun nextStartAlwaysAfterNow() {
        assertEquals(Duration.ofHours(23), night.delayUntilNextStart(at(2)))
        assertEquals(Duration.ofHours(1), night.delayUntilNextStart(at(0)))
    }

    @Test
    fun dstGapIsHandled() {
        // Europe/London springs forward at 01:00 -> 02:00 on 2026-03-29, so 01:00 does not exist that night.
        val london = ZoneId.of("Europe/London")
        val before = ZonedDateTime.of(2026, 3, 28, 23, 0, 0, 0, london)
        val delay = night.delayUntilOpen(before)
        assertTrue(delay > Duration.ZERO && delay <= Duration.ofHours(3), delay.toString())
    }

    @Test
    fun backfillCursorAdvances() {
        assertEquals(501L, BackfillCursor.next(previous = 1000, oldestInBatch = 500))
        // Whole batch shares the timestamp just below the cursor: step past it instead of looping.
        assertEquals(999L, BackfillCursor.next(previous = 1000, oldestInBatch = 999))
    }

    @Test
    fun otpTiming() {
        assertEquals(OtpTiming.MIN_CONSUMED_DELETE_MILLIS, OtpTiming.clampConsumedDelay(60_000))
        assertEquals(20 * 60_000L, OtpTiming.clampConsumedDelay(20 * 60_000L))
        assertEquals(0L, OtpTiming.remainingDelay(arrivedAtMillis = 0, lifetimeMillis = 10, nowMillis = 100))
        assertEquals(90L, OtpTiming.remainingDelay(arrivedAtMillis = 0, lifetimeMillis = 100, nowMillis = 10))
    }

    @Test
    fun binRetention() {
        assertEquals(Long.MAX_VALUE, BinRetention.purgeAt(1000, null))
        assertEquals(1500L, BinRetention.purgeAt(1000, 500))
        assertEquals(Long.MAX_VALUE, BinRetention.purgeAt(Long.MAX_VALUE - 1, 500))
    }

    @Test
    fun defaultBinPolicy() = runTest {
        assertEquals(BinPolicy.DEFAULT_OTP_RETENTION_MILLIS, DefaultBinPolicy.retentionMillis(Category.OTP))
        assertEquals(BinPolicy.DEFAULT_RETENTION_MILLIS, DefaultBinPolicy.retentionMillis(Category.PERSONAL))
    }

    @Test
    fun deletedByRoundTrips() {
        val all = listOf(DeletedBy.Manual, DeletedBy.AutoOtp, DeletedBy.AutoRule("Promos"), DeletedBy.AutoConsumed("com.bank.app"))
        for (d in all) assertEquals(d, DeletedBy.decode(d.encoded))
        assertEquals("auto-rule:Promos", DeletedBy.AutoRule("Promos").encoded)
        assertEquals(DeletedBy.Manual, DeletedBy.decode("garbage"))
    }
}
