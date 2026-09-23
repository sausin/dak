package app.dak.automation

import app.dak.R
import app.dak.automations.MessageEvent
import app.dak.automations.forwarding.ForwardingRecipient
import app.dak.automations.forwarding.ForwardingSource
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.history.RunOutcome
import app.dak.automations.history.SkipReason
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import app.dak.core.model.Category
import app.dak.core.model.OtpInfo
import app.dak.navigation.Routes
import app.dak.security.EffectiveLock
import app.dak.security.LockMethodChoice
import app.dak.ui.forwarding.AutomationHistoryViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboundGuardTest {

    private val forwarding = ForwardingSpec(
        id = "f",
        sources = listOf(ForwardingSource("m:HDFCBK", "HDFC Bank", "HDFCBK")),
        recipients = listOf(ForwardingRecipient("+919800000000", "Sharma CA", "key")),
        startMillis = 0,
        endMillis = 3_600_000,
    ).toRule(0) { "f" }!!

    private val generic = Rule(
        id = "g",
        name = "Night replies",
        trigger = Trigger.MessageReceived(),
        conditions = Condition.All(emptyList()),
        actions = listOf(ActionSpec.ScheduleReply("Busy, will call back", 1), ActionSpec.Label("x")),
        createdAt = 0,
        updatedAt = 0,
    )

    private val event = MessageEvent(
        messageKey = "sms:42",
        address = "VM-HDFCBK",
        body = "Your OTP is 482913. Do not share.",
        dateMillis = 0,
        subId = 1,
        category = Category.OTP,
        otp = OtpInfo(code = "482913"),
        conversationId = "m:HDFCBK",
    )

    @Test
    fun `reason for turning automations off follows how the lock went away`() {
        // Before the one-time upgrade check: rules predate the requirement.
        assertEquals(ForwardingHold.LOCK_NEEDED, OutboundGuardRules.holdFor(LockMethodChoice.OFF, deviceSecure = true, migrated = false))
        assertEquals(ForwardingHold.LOCK_OFF, OutboundGuardRules.holdFor(LockMethodChoice.OFF, deviceSecure = true, migrated = true))
        assertEquals(ForwardingHold.SCREEN_LOCK_REMOVED, OutboundGuardRules.holdFor(LockMethodChoice.DEVICE, deviceSecure = false, migrated = true))
        assertEquals(ForwardingHold.LOCK_NEEDED, OutboundGuardRules.holdFor(LockMethodChoice.APP_PIN, deviceSecure = true, migrated = true))
    }

    @Test
    fun `removing the PIN warns only when it leaves no lock and automations are on`() {
        assertTrue(OutboundGuardRules.removingPinDisablesOutbound(EffectiveLock.NONE, enabledOutbound = 2))
        assertFalse(OutboundGuardRules.removingPinDisablesOutbound(EffectiveLock.NONE, enabledOutbound = 0))
        assertFalse(OutboundGuardRules.removingPinDisablesOutbound(EffectiveLock.DEVICE, enabledOutbound = 2))
    }

    @Test
    fun `app lock holds are the ones that need app lock to come back`() {
        assertTrue(ForwardingHold.LOCK_OFF.needsAppLock)
        assertTrue(ForwardingHold.SCREEN_LOCK_REMOVED.needsAppLock)
        assertTrue(ForwardingHold.LOCK_NEEDED.needsAppLock)
        assertFalse(ForwardingHold.CONTACT_MISSING.needsAppLock)
        assertFalse(ForwardingHold.CONTACTS_ACCESS.needsAppLock)
    }

    @Test
    fun `forwarding rules are managed on the forwarding screen`() {
        assertEquals(Routes.FORWARDING, OutboundSecurityNotifier.routeFor(forwarding))
        assertEquals(Routes.AUTOMATIONS, OutboundSecurityNotifier.routeFor(generic))
    }

    @Test
    fun `run log row names the contact and masks the OTP`() {
        val action = forwarding.actions.first() as ActionSpec.ForwardSms
        val row = AutomationRunLog.rowFor(forwarding, action, event, RunOutcome.SENT, null, nowMillis = 1_000, zoneId = "Asia/Kolkata")
        assertEquals("f", row.ruleId)
        assertEquals(forwarding.name, row.ruleName)
        assertEquals("sms:42", row.messageKey)
        assertEquals("m:HDFCBK", row.conversationId)
        assertEquals("ForwardSms", row.actionKind)
        assertEquals("Sharma CA", row.destinationLabel)
        assertEquals("+919800000000", row.destination)
        assertEquals("SENT", row.outcome)
        assertFalse(row.textPreview!!.contains("482913"))
        assertTrue(row.textPreview!!.startsWith("Fwd from VM-HDFCBK"))
        val record = AutomationRunLog.toRecord(row.copy(id = 7))
        assertEquals(7L, record.id)
        assertEquals(RunOutcome.SENT, record.outcome)
        assertEquals("Sharma CA", record.destinationText)
    }

    @Test
    fun `auto-replies log the sender as destination and the reply text`() {
        val action = generic.actions.first()
        val row = AutomationRunLog.rowFor(generic, action, event, RunOutcome.SKIPPED, SkipReason.NO_APP_LOCK, nowMillis = 1_000)
        assertEquals("VM-HDFCBK", row.destination)
        assertNull(row.destinationLabel)
        assertEquals("Busy, will call back", row.textPreview)
        assertEquals(SkipReason.NO_APP_LOCK, row.reason)
        assertNull(AutomationRunLog.sentText(ActionSpec.LaunchIntent("https://x.y"), event, "UTC"))
    }

    @Test
    fun `every skip reason has a sentence`() {
        listOf(
            SkipReason.NOT_CONFIRMED,
            SkipReason.NO_APP_LOCK,
            SkipReason.CONTACT_REMOVED,
            SkipReason.NO_CONTACTS_ACCESS,
            SkipReason.POSSIBLE_SCAM,
            SkipReason.PREMIUM_LOCKED,
        ).forEach { assertTrue(AutomationHistoryViewModel.reasonText(it) != null, it) }
        assertEquals(R.string.fw_skip_no_app_lock, AutomationHistoryViewModel.reasonText(SkipReason.NO_APP_LOCK))
        assertNull(AutomationHistoryViewModel.reasonText("forward failed"))
        assertNull(AutomationHistoryViewModel.reasonText(null))
    }

    @Test
    fun `history route carries the rule id or none`() {
        assertEquals("automationhistory", Routes.automationHistory())
    }
}
