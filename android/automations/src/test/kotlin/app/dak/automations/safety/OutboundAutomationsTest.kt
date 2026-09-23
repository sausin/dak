package app.dak.automations.safety

import app.dak.automations.forwarding.ForwardingPolicy
import app.dak.automations.forwarding.ForwardingRecipient
import app.dak.automations.forwarding.ForwardingSource
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.RelayChannel
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import app.dak.automations.rule.activeWindow
import app.dak.automations.rule.sendsOffDevice
import app.dak.automations.rule.withRestartedWindow
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboundAutomationsTest {

    private fun rule(vararg actions: ActionSpec, enabled: Boolean = true, window: Condition.ActiveBetween? = null) = Rule(
        id = "r",
        name = "r",
        enabled = enabled,
        trigger = Trigger.MessageReceived(),
        conditions = Condition.All(listOfNotNull(window, Condition.BodyContains("x"))),
        actions = actions.toList(),
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `actions that send off the phone`() {
        val outbound = listOf(
            ActionSpec.ForwardSms("+911234567890"),
            ActionSpec.ScheduleReply("ok", 5),
            ActionSpec.Webhook("https://example.com/hook", secretRef = "s"),
            ActionSpec.RelayToWebClient(),
            ActionSpec.RelayRule("+911234567890", RelayChannel.SMS),
            ActionSpec.LaunchIntent("https://example.com"),
            ActionSpec.Unknown("teleport", JsonObject(emptyMap())),
        )
        val local = listOf(ActionSpec.Label("x"), ActionSpec.Archive, ActionSpec.Notify(), ActionSpec.Delete)
        outbound.forEach { assertTrue(it.sendsOffDevice(), "$it") }
        local.forEach { assertFalse(it.sendsOffDevice(), "$it") }
    }

    @Test
    fun `a rule sends off the phone when any action does`() {
        assertFalse(rule(ActionSpec.Archive, ActionSpec.Label("x")).sendsOffDevice())
        assertTrue(rule(ActionSpec.Archive, ActionSpec.ScheduleReply("ok", 1)).sendsOffDevice())
        assertFalse(rule().sendsOffDevice())
    }

    @Test
    fun `reminder is armed when outbound sending turns on`() {
        val fwd = rule(ActionSpec.ForwardSms("+911"))
        assertTrue(OutboundAutomations.remindAfterSave(null, fwd))
        assertTrue(OutboundAutomations.remindAfterSave(fwd.copy(enabled = false), fwd))
        assertTrue(OutboundAutomations.remindAfterSave(rule(ActionSpec.Archive), fwd))
        // A new recipient is a new destination.
        assertTrue(OutboundAutomations.remindAfterSave(fwd, rule(ActionSpec.ForwardSms("+922"))))
    }

    @Test
    fun `no reminder for unchanged, disabled or local-only rules`() {
        val fwd = rule(ActionSpec.ForwardSms("+911"))
        assertFalse(OutboundAutomations.remindAfterSave(fwd, fwd.copy(name = "renamed")))
        assertFalse(OutboundAutomations.remindAfterSave(null, fwd.copy(enabled = false)))
        assertFalse(OutboundAutomations.remindAfterSave(null, rule(ActionSpec.Label("x"))))
    }

    @Test
    fun `running longer re-arms the reminder, running shorter does not`() {
        val hour = ForwardingPolicy.DEFAULT_DURATION_MILLIS
        val short = rule(ActionSpec.ForwardSms("+911"), window = Condition.ActiveBetween(0, hour))
        val longer = rule(ActionSpec.ForwardSms("+911"), window = Condition.ActiveBetween(0, 5 * hour))
        val open = rule(ActionSpec.ForwardSms("+911"), window = Condition.ActiveBetween(0, null))
        assertTrue(OutboundAutomations.remindAfterSave(short, longer))
        assertTrue(OutboundAutomations.remindAfterSave(short, open))
        assertFalse(OutboundAutomations.remindAfterSave(longer, short))
        assertFalse(OutboundAutomations.remindAfterSave(open, short))
    }

    @Test
    fun `notice lists at most five names`() {
        val names = (1..8).map { "Rule $it" }
        val (shown, more) = OutboundAutomations.namesForNotice(names)
        assertEquals(names.take(5), shown)
        assertEquals(3, more)
        assertEquals(listOf("a") to 0, OutboundAutomations.namesForNotice(listOf("a")))
    }

    @Test
    fun `reminder delay is three hours and repeats daily`() {
        assertEquals(3L * 3_600_000, OutboundAutomations.REMINDER_DELAY_MILLIS)
        assertEquals(24L * 3_600_000, OutboundAutomations.REMINDER_REPEAT_MILLIS)
    }

    @Test
    fun `restarted window keeps the length and starts now`() {
        val hour = ForwardingPolicy.DEFAULT_DURATION_MILLIS
        assertEquals(1_000L to 1_000L + 3 * hour, ForwardingPolicy.restartedWindow(0, 3 * hour, 1_000))
        // Unknown length (end not after start): the default hour.
        assertEquals(500L to 500L + hour, ForwardingPolicy.restartedWindow(100, 100, 500))
        assertEquals(500L to null, ForwardingPolicy.restartedWindow(100, null, 500))
    }

    @Test
    fun `restarting a forwarding rule moves only its window`() {
        val hour = ForwardingPolicy.DEFAULT_DURATION_MILLIS
        val spec = ForwardingSpec(
            id = "f",
            sources = listOf(ForwardingSource("m:HDFCBK", "HDFC Bank", "HDFCBK")),
            recipients = listOf(ForwardingRecipient("+911234567890", "CA", "key")),
            startMillis = 0,
            endMillis = 2 * hour,
        )
        val ended = spec.toRule(0) { "f" }!!
        val now = 10 * hour
        val again = ended.withRestartedWindow(now)
        assertEquals(Condition.ActiveBetween(now, now + 2 * hour), again.activeWindow())
        assertEquals(ended.actions, again.actions)
        assertEquals(ended.meta, again.meta)
        val back = ForwardingSpec.fromRule(again)!!
        assertEquals(spec.recipients, back.recipients)
        assertEquals(now, back.startMillis)
        assertEquals(now + 2 * hour, back.endMillis)
        // Same length, so a long period stays long (and still needs the fingerprint check); a one-hour one stays short.
        assertTrue(ForwardingPolicy.hasLongSmsForward(again))
        val hourly = spec.copy(endMillis = hour).toRule(0) { "f" }!!.withRestartedWindow(now)
        assertFalse(ForwardingPolicy.hasLongSmsForward(hourly))
    }

    @Test
    fun `restarting a rule without a window changes nothing`() {
        val r = rule(ActionSpec.ForwardSms("+911"))
        assertEquals(r, r.withRestartedWindow(99))
        assertNull(r.activeWindow())
    }
}
