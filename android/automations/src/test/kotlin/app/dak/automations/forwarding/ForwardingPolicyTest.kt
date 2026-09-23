package app.dak.automations.forwarding

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForwardingPolicyTest {

    private val hour = ForwardingPolicy.DEFAULT_DURATION_MILLIS
    private val now = 1_750_000_000_000L

    private fun spec(start: Long = now, end: Long? = now + hour, id: String? = "r1") = ForwardingSpec(
        id = id,
        sources = listOf(ForwardingSource("m:HDFCBK", "HDFC Bank", mergeKey = "HDFCBK")),
        recipients = listOf(ForwardingRecipient("+919876543210", "Sharma CA", contactKey = "k")),
        startMillis = start,
        endMillis = end,
    )

    @Test
    fun `default window is exactly one hour from now and not long`() {
        val (start, end) = ForwardingPolicy.defaultWindow(now)
        assertEquals(now, start)
        assertEquals(now + hour, end)
        assertFalse(ForwardingPolicy.isLongPeriod(start, end))
    }

    @Test
    fun `open ended and over an hour are long, with a minute of slack`() {
        assertTrue(ForwardingPolicy.isLongPeriod(now, null))
        assertFalse(ForwardingPolicy.isLongPeriod(now, now + hour + 60_000))
        assertTrue(ForwardingPolicy.isLongPeriod(now, now + hour + 60_001))
        assertTrue(ForwardingPolicy.isLongPeriod(now, now + 24 * hour))
        assertFalse(ForwardingPolicy.isLongPeriod(now, now + 5 * 60_000))
    }

    @Test
    fun `moving the end later or making it open ended extends an existing rule`() {
        val original = spec()
        assertTrue(ForwardingPolicy.extends(original, original.copy(endMillis = now + hour + 1)))
        assertTrue(ForwardingPolicy.extends(original, original.copy(endMillis = null)))
        assertFalse(ForwardingPolicy.extends(original, original.copy(endMillis = now + hour)))
        assertFalse(ForwardingPolicy.extends(original, original.copy(endMillis = now + 10)))
        // New rules and rules that were already open ended never "extend".
        assertFalse(ForwardingPolicy.extends(null, original))
        assertFalse(ForwardingPolicy.extends(spec(id = null), original.copy(endMillis = null)))
        assertFalse(ForwardingPolicy.extends(spec(end = null), original.copy(endMillis = null)))
    }

    @Test
    fun `long sms forward covers forwarding rules and window-less automation forwards`() {
        assertFalse(ForwardingPolicy.hasLongSmsForward(spec().toRule(now) { "x" }!!))
        assertTrue(ForwardingPolicy.hasLongSmsForward(spec(end = now + 3 * hour).toRule(now) { "x" }!!))
        assertTrue(ForwardingPolicy.hasLongSmsForward(spec(end = null).toRule(now) { "x" }!!))

        fun plain(vararg actions: ActionSpec) =
            Rule("p", "p", trigger = Trigger.MessageReceived(), actions = actions.toList(), createdAt = 0, updatedAt = 0)
        assertTrue(ForwardingPolicy.hasLongSmsForward(plain(ActionSpec.ForwardSms("+919876543210"))))
        assertFalse(ForwardingPolicy.hasLongSmsForward(plain(ActionSpec.Archive)))
    }
}
