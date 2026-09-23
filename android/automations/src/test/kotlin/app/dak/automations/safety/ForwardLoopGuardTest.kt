package app.dak.automations.safety

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
}
