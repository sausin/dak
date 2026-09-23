package app.dak.index.otp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtpSweepPlanTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun auto(key: String, at: Long) = OtpDeleteEntry(key, at, "auto-otp")
    private fun consumed(key: String, at: Long) = OtpDeleteEntry(key, at, "auto-consumed:com.bank.app")

    @Test
    fun encodeDecodeRoundTrip() {
        val entry = consumed("SMS:42", 1_700_000_000_000L)
        assertEquals(entry, OtpDeleteEntry.decode("SMS:42", entry.encodeValue()))
        assertTrue(entry.consumed)
        assertFalse(auto("SMS:1", 5).consumed)
    }

    @Test
    fun decodeRejectsMalformed() {
        assertNull(OtpDeleteEntry.decode("k", ""))
        assertNull(OtpDeleteEntry.decode("k", "|auto-otp"))
        assertNull(OtpDeleteEntry.decode("k", "123|"))
        assertNull(OtpDeleteEntry.decode("k", "abc|auto-otp"))
        assertNull(OtpDeleteEntry.decode("k", "123"))
    }

    @Test
    fun consumedIsNeverEarly() {
        val e = consumed("SMS:1", 10 * minute)
        assertTrue(OtpSweepPlan.due(listOf(e), 10 * minute - 1).isEmpty())
        assertEquals(listOf(e), OtpSweepPlan.due(listOf(e), 10 * minute))
        assertEquals(10 * minute + OtpSweepPlan.CONSUMED_LATE_MILLIS, OtpSweepPlan.nextRunAt(listOf(e)))
    }

    @Test
    fun autoDeletesBatchWithinTheHour() {
        val base = 24 * hour
        val entries = listOf(auto("SMS:1", base), auto("SMS:2", base + 20 * minute), auto("SMS:3", base + 50 * minute), auto("SMS:4", base + 3 * hour))
        val runAt = OtpSweepPlan.nextRunAt(entries)!!
        assertEquals(base + OtpSweepPlan.AUTO_LATE_MILLIS, runAt)
        // One wakeup takes the first three (all within their early window), leaves the one three hours later.
        assertEquals(listOf("SMS:1", "SMS:2", "SMS:3"), OtpSweepPlan.due(entries, runAt).map { it.key })
    }

    @Test
    fun mostUrgentDeadlineWins() {
        val entries = listOf(auto("SMS:1", 2 * hour), consumed("SMS:2", 30 * minute))
        assertEquals(30 * minute + OtpSweepPlan.CONSUMED_LATE_MILLIS, OtpSweepPlan.nextRunAt(entries))
        assertNull(OtpSweepPlan.nextRunAt(emptyList()))
    }

    @Test
    fun armedCoverage() {
        val now = 10 * hour
        assertFalse(OtpSweepPlan.armedCovers(0L, now + hour, now))
        assertTrue(OtpSweepPlan.armedCovers(now + minute, now + hour, now))
        assertFalse(OtpSweepPlan.armedCovers(now + 2 * hour, now + hour, now)) // armed too late: re-arm earlier
        assertTrue(OtpSweepPlan.armedCovers(now - minute, now + hour, now)) // overdue but still pending
        assertFalse(OtpSweepPlan.armedCovers(now - 2 * hour, now + hour, now)) // stale: the run was lost
    }
}
