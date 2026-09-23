package app.dak.automation

import app.dak.automations.birthdays.OccasionKind
import app.dak.automations.birthdays.WishTag
import app.dak.automations.broadcast.BroadcastTag
import app.dak.automations.history.RunOutcome
import app.dak.automations.history.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which scheduled sends are unattended, and the send-time app-lock re-check ([ScheduledSendGuard]). */
class ScheduledSendOriginTest {

    private val wish = WishTag(ask = false, contactId = 5, kind = OccasionKind.BIRTHDAY, year = 2026)

    @Test
    fun `origins from tags`() {
        assertEquals(ScheduledSendOrigin.USER, ScheduledSendOrigin.of(null))
        assertEquals(ScheduledSendOrigin.BROADCAST, ScheduledSendOrigin.of(BroadcastTag("b1", 0).encode()))
        assertEquals(ScheduledSendOrigin.AUTO_REPLY, ScheduledSendOrigin.of(ScheduledSendScheduler.AUTO_REPLY_TAG))
        assertEquals(ScheduledSendOrigin.AUTO_FORWARD, ScheduledSendOrigin.of(ScheduledSendScheduler.AUTO_FORWARD_TAG))
        assertEquals(ScheduledSendOrigin.BIRTHDAY_AUTO, ScheduledSendOrigin.of(wish.encode()))
        assertEquals(ScheduledSendOrigin.BIRTHDAY_ASK, ScheduledSendOrigin.of(wish.copy(ask = true).encode()))
        assertEquals(ScheduledSendOrigin.BIRTHDAY_CONFIRMED, ScheduledSendOrigin.of(wish.copy(confirmed = true).encode()))
        assertEquals(ScheduledSendOrigin.OTHER_RULE, ScheduledSendOrigin.of("something-new"))
    }

    @Test
    fun `only sends nobody confirms are unattended`() {
        assertFalse(ScheduledSendOrigin.USER.unattended)
        assertFalse(ScheduledSendOrigin.BROADCAST.unattended)
        assertFalse(ScheduledSendOrigin.BIRTHDAY_ASK.unattended)
        assertFalse(ScheduledSendOrigin.BIRTHDAY_CONFIRMED.unattended)
        assertTrue(ScheduledSendOrigin.BIRTHDAY_AUTO.unattended)
        assertTrue(ScheduledSendOrigin.AUTO_REPLY.unattended)
        assertTrue(ScheduledSendOrigin.AUTO_FORWARD.unattended)
        assertTrue(ScheduledSendOrigin.OTHER_RULE.unattended)
    }

    @Test
    fun `queued auto-replies and forwards are cancelled once the lock is gone`() {
        assertTrue(ScheduledSendGuard.cancelForNoLock(ScheduledSendOrigin.AUTO_REPLY) { false })
        assertTrue(ScheduledSendGuard.cancelForNoLock(ScheduledSendOrigin.AUTO_FORWARD) { false })
        assertTrue(ScheduledSendGuard.cancelForNoLock(ScheduledSendOrigin.OTHER_RULE) { false })
        assertFalse(ScheduledSendGuard.cancelForNoLock(ScheduledSendOrigin.AUTO_REPLY) { true })
    }

    @Test
    fun `user sends never ask for the lock`() {
        var asked = false
        val probe = { asked = true; false }
        assertFalse(ScheduledSendGuard.cancelForNoLock(ScheduledSendOrigin.USER, probe))
        assertFalse(ScheduledSendGuard.cancelForNoLock(ScheduledSendOrigin.BROADCAST, probe))
        assertFalse(ScheduledSendGuard.cancelForNoLock(ScheduledSendOrigin.BIRTHDAY_CONFIRMED, probe))
        assertFalse(asked)
    }

    @Test
    fun `automatic birthday wishes are left to the birthday gate`() {
        // The gate turns them into a prompt instead of cancelling them outright.
        assertFalse(ScheduledSendGuard.cancelForNoLock(ScheduledSendOrigin.BIRTHDAY_AUTO) { false })
    }

    @Test
    fun `history row for a cancelled queued send`() {
        val reply = AutomationRunLog.rowForQueued(
            ScheduledSendOrigin.AUTO_REPLY, "auto-reply", "Auto-reply", " +911234567890 ", "Driving, will call back",
            RunOutcome.SKIPPED, SkipReason.NO_APP_LOCK, nowMillis = 42,
        )
        assertEquals("auto-reply", reply.ruleId)
        assertEquals("ScheduleReply", reply.actionKind)
        assertEquals("+911234567890", reply.destination)
        assertEquals("SKIPPED", reply.outcome)
        assertEquals(SkipReason.NO_APP_LOCK, reply.reason)
        assertEquals("Driving, will call back", reply.textPreview)

        val forward = AutomationRunLog.rowForQueued(
            ScheduledSendOrigin.AUTO_FORWARD, "auto-forward", "Auto-forward", "+15550100", "OTP 123456 for your bank",
            RunOutcome.SKIPPED, SkipReason.NO_APP_LOCK, nowMillis = 42,
        )
        assertEquals("ForwardSms", forward.actionKind)
        // Forwarded text may hold an OTP: not kept.
        assertNull(forward.textPreview)
    }
}
