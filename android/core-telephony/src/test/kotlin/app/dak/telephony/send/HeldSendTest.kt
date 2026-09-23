package app.dak.telephony.send

import app.dak.telephony.role.RoleChange
import app.dak.telephony.role.RoleTransitions
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class HeldSendTest {

    @Test
    fun roundTripsAnyBody() {
        val held = HeldSend(
            id = "b3c1",
            addresses = listOf("+919876543210", "57575", "a:b;c"),
            body = "Line 1\nLine 2 with 12:34; semicolons, émojis 😀 and \u0000 control",
            subId = 2,
            threadId = 42L,
            requestDeliveryReport = true,
            heldAtMillis = 1_700_000_000_000L,
        )
        assertEquals(held, HeldSend.decode(held.encode()))
        val minimal = held.copy(addresses = emptyList(), body = "", threadId = null, requestDeliveryReport = false, subId = -1)
        assertEquals(minimal, HeldSend.decode(minimal.encode()))
    }

    @Test
    fun malformedInputIsRejectedNotThrown() {
        val good = HeldSend("x", listOf("1"), "hi", 1, null, false, 5L).encode()
        assertNull(HeldSend.decode(null))
        assertNull(HeldSend.decode(""))
        assertNull(HeldSend.decode(good.dropLast(1)))
        assertNull(HeldSend.decode(good + "1:x"))
        assertNull(HeldSend.decode("1:2" + good.drop(3))) // unknown version
        assertNull(HeldSend.decode("1:11:x9999999999:"))
        assertNull(HeldSend.decode("1:11:x7:1000000"))
        assertNull(HeldSend.decode("99999999999:x"))
    }

    @Test
    fun roleTransitions() {
        assertEquals(RoleChange.NONE, RoleTransitions.between(wasDefault = true, isDefault = true))
        assertEquals(RoleChange.NONE, RoleTransitions.between(wasDefault = false, isDefault = false))
        assertEquals(RoleChange.LOST, RoleTransitions.between(wasDefault = true, isDefault = false))
        assertEquals(RoleChange.REGAINED, RoleTransitions.between(wasDefault = false, isDefault = true))
    }
}
