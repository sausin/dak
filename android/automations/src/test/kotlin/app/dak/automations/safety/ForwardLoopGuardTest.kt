package app.dak.automations.safety

import app.dak.automations.MessageEvent
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.rule.ActionSpec
import app.dak.core.model.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForwardLoopGuardTest {

    @Test
    fun `phone numbers compare on their last ten digits`() {
        assertTrue(Addresses.same("+91 98765 43210", "09876543210"))
        assertTrue(Addresses.same("9876543210", "+919876543210"))
        assertFalse(Addresses.same("+919876543210", "+919876543211"))
        assertTrue(Addresses.same("vm-hdfcbk", "VM-HDFCBK"))
        assertFalse(Addresses.same("VM-HDFCBK", "9876543210"))
        assertNull(Addresses.phoneDigits("56767"))
    }

    @Test
    fun `template prefix is the literal lead`() {
        assertEquals("Fwd from", ForwardLoopGuard.templatePrefix("Fwd from {sender}: {body}"))
        assertNull(ForwardLoopGuard.templatePrefix("{body}"))
        assertNull(ForwardLoopGuard.templatePrefix("> {body}"))
    }

    private fun event(body: String, address: String = "VM-HDFCBK") = MessageEvent(
        messageKey = "sms:1", address = address, body = body, dateMillis = 1L, subId = 1, category = Category.TRANSACTION,
    )

    private fun forward(template: String) = ActionSpec.ForwardSms(to = "+919876543210", template = template)

    @Test
    fun `a forward made with the rule's own template prefix is not forwarded again`() {
        val default = forward(ForwardingSpec.DEFAULT_TEMPLATE)
        assertTrue(ForwardLoopGuard.shouldSkip(default, event("Fwd from HDFCBK: Rs 5,000 debited")))
        assertTrue(ForwardLoopGuard.shouldSkip(default, event("  fwd FROM HDFCBK: Rs 5,000 debited")))
        assertFalse(ForwardLoopGuard.shouldSkip(default, event("Rs 5,000 debited")))
        // Any template's own literal lead is its marker, in any script.
        val custom = forward("आगे भेजा {sender}: {body}")
        assertTrue(ForwardLoopGuard.shouldSkip(custom, event("आगे भेजा HDFCBK: Rs 5,000 debited")))
        assertFalse(ForwardLoopGuard.shouldSkip(custom, event("Rs 5,000 debited")))
    }

    @Test
    fun `a message from the recipient is never forwarded back to them`() {
        val action = forward("{body}")
        assertTrue(ForwardLoopGuard.shouldSkip(action, event("hello", address = "09876543210")))
        assertFalse(ForwardLoopGuard.shouldSkip(action, event("hello")))
        assertFalse(ForwardLoopGuard.shouldSkip(ActionSpec.Archive, event("Fwd from X: y")))
    }

    @Test
    fun `the english default marker is recognised for every rule, even one without a prefix`() {
        // Rules stored before the prefix was translated, and Dak users in English, forward with "Fwd from".
        assertEquals("Fwd from", ForwardLoopGuard.DEFAULT_MARKER)
        assertTrue(ForwardLoopGuard.shouldSkip(forward("{body}"), event("Fwd from HDFCBK: Rs 5,000 debited")))
        assertTrue(ForwardLoopGuard.shouldSkip(forward("आगे भेजा {sender}: {body}"), event("Fwd from HDFCBK: Rs 5,000 debited")))
        assertFalse(ForwardLoopGuard.shouldSkip(forward("{body}"), event("Forwarding charges waived this month")))
    }

    @Test
    fun `registered translations of the default template are recognised too`() {
        try {
            ForwardLoopGuard.registerDefaultTemplates(listOf("आगे भेजा {sender}: {body}", "Weitergeleitet von {sender}: {body}", "{body}"))
            val english = forward(ForwardingSpec.DEFAULT_TEMPLATE)
            assertTrue(ForwardLoopGuard.shouldSkip(english, event("आगे भेजा HDFCBK: Rs 5,000 debited")))
            assertTrue(ForwardLoopGuard.shouldSkip(english, event("weitergeleitet von HDFCBK: Rs 5,000")))
            assertEquals(setOf("Fwd from", "आगे भेजा", "Weitergeleitet von"), ForwardLoopGuard.markersFor(ForwardingSpec.DEFAULT_TEMPLATE))
        } finally {
            ForwardLoopGuard.registerDefaultTemplates(emptyList())
        }
        assertFalse(ForwardLoopGuard.shouldSkip(forward("{body}"), event("आगे भेजा HDFCBK: Rs 5,000 debited")))
    }

    @Test
    fun `a translated default template is used only when it keeps the body and a marker`() {
        assertEquals("आगे भेजा {sender}: {body}", ForwardingSpec.defaultTemplate(" आगे भेजा {sender}: {body} "))
        assertEquals(ForwardingSpec.DEFAULT_TEMPLATE, ForwardingSpec.defaultTemplate("आगे भेजा {sender}"))
        assertEquals(ForwardingSpec.DEFAULT_TEMPLATE, ForwardingSpec.defaultTemplate("{sender}: {body}"))
        assertEquals(ForwardingSpec.DEFAULT_TEMPLATE, ForwardingSpec.defaultTemplate(""))
        assertEquals(ForwardingSpec.DEFAULT_TEMPLATE, ForwardingSpec.defaultTemplate(null))
        assertEquals(ForwardingSpec.DEFAULT_TEMPLATE, ForwardingSpec.defaultTemplate(ForwardingSpec.DEFAULT_TEMPLATE))
    }
}
