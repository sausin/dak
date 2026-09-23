package app.dak.automation

import app.dak.index.repo.ScheduledSendStatus
import app.dak.telephony.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scheduled send that falls due while Dak is not the default SMS app stays PENDING and is retried later; it is
 * never marked SENT on the strength of the sender's "held" answer ([ScheduledSendRoleGate]).
 */
class ScheduledSendRoleGateTest {

    private val now = 1_750_000_000_000L

    @Test
    fun `default SMS app sends now`() {
        assertNull(ScheduledSendRoleGate.waitUntil(isDefaultSmsApp = { true }, nowMillis = now))
    }

    @Test
    fun `without the role the send waits and is looked at again later`() {
        val retryAt = ScheduledSendRoleGate.waitUntil(isDefaultSmsApp = { false }, nowMillis = now)
        assertEquals(now + ScheduledSendRoleGate.RECHECK_MILLIS, retryAt)
        // Strictly in the future, so rearmPending cannot re-fire the alarm immediately in a loop.
        assertNotNull(retryAt)
        assertTrue(retryAt > now)
    }

    @Test
    fun `a held send is not reported as sent`() {
        // Simulates the executor's decision for one due row across two runs: first without the role, then with it.
        var status = ScheduledSendStatus.PENDING
        var sendAt = now
        var sends = 0
        fun run(isDefault: Boolean, at: Long) {
            if (status != ScheduledSendStatus.PENDING || sendAt > at) return
            val retryAt = ScheduledSendRoleGate.waitUntil({ isDefault }, at)
            if (retryAt != null) {
                sendAt = retryAt
                return
            }
            sends++
            status = ScheduledSendRoleGate.statusAfter(SendResult.Queued(emptyList()))
        }
        run(isDefault = false, at = now)
        assertEquals(ScheduledSendStatus.PENDING, status)
        assertEquals(0, sends)
        // Not due again before the recheck time.
        run(isDefault = true, at = now + 1)
        assertEquals(0, sends)
        run(isDefault = true, at = now + ScheduledSendRoleGate.RECHECK_MILLIS)
        assertEquals(ScheduledSendStatus.SENT, status)
        assertEquals(1, sends)
    }

    @Test
    fun `status after the sender answers`() {
        assertEquals(ScheduledSendStatus.SENT, ScheduledSendRoleGate.statusAfter(SendResult.Queued(emptyList())))
        assertEquals(ScheduledSendStatus.FAILED, ScheduledSendRoleGate.statusAfter(SendResult.Failed("x")))
    }

    @Test
    fun `an emergency text never waits for the role`() {
        assertNull(ScheduledSendRoleGate.waitUntil(isDefaultSmsApp = { false }, nowMillis = 0L, toEmergency = { true }))
    }
}
