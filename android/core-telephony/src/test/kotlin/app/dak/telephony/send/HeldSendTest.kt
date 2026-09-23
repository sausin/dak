package app.dak.telephony.send

import app.dak.telephony.role.RoleChange
import app.dak.telephony.role.RoleTransitions
import kotlin.random.Random
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
    fun everyTruncationIsRejected() {
        val encoded = HeldSend("id-1", listOf("+15551234", "57575"), "body: 12:34", 3, 9L, true, 77L).encode()
        for (n in 0 until encoded.length) assertNull(HeldSend.decode(encoded.substring(0, n)), "prefix $n")
    }

    @Test
    fun randomInputNeverThrows() {
        val random = Random(7)
        val alphabet = "0123456789:-x1"
        repeat(5_000) {
            val s = String(CharArray(random.nextInt(0, 60)) { alphabet[random.nextInt(alphabet.length)] })
            HeldSend.decode(s) // must return, never throw
        }
    }

    @Test
    fun addressCountIsBounded() {
        val atLimit = HeldSend("x", List(HeldSend.MAX_ADDRESSES) { "$it" }, "b", 1, null, false, 1L)
        assertEquals(atLimit, HeldSend.decode(atLimit.encode()))
        val overLimit = atLimit.copy(addresses = atLimit.addresses + "extra")
        assertNull(HeldSend.decode(overLimit.encode()))
        assertNull(HeldSend.decode("1:11:x2:-1"), "negative count")
    }

    @Test
    fun lengthsCountUtf16UnitsSoSurrogatesAndSeparatorsSurvive() {
        val held = HeldSend("😀", listOf("1:2", ""), "😀😀 4:x 0:", -1, -5L, false, Long.MIN_VALUE)
        assertEquals(held, HeldSend.decode(held.encode()))
    }

    @Test
    fun anUnreadableNumberFieldRejectsTheEntry() {
        fun encoded(subId: String, heldAt: String) = "1:1" + "1:x" + "1:1" + "1:1" + "2:hi" + "${subId.length}:$subId" + "0:" + "1:0" + "${heldAt.length}:$heldAt"
        assertEquals(HeldSend("x", listOf("1"), "hi", 1, null, false, 5L), HeldSend.decode(encoded("1", "5")))
        assertNull(HeldSend.decode(encoded("x", "5")))
        assertNull(HeldSend.decode(encoded("1", "5.0")))
        assertNull(HeldSend.decode(encoded("99999999999", "5")), "subId overflows Int")
    }

    @Test
    fun roleTransitions() {
        assertEquals(RoleChange.NONE, RoleTransitions.between(wasDefault = true, isDefault = true))
        assertEquals(RoleChange.NONE, RoleTransitions.between(wasDefault = false, isDefault = false))
        assertEquals(RoleChange.LOST, RoleTransitions.between(wasDefault = true, isDefault = false))
        assertEquals(RoleChange.REGAINED, RoleTransitions.between(wasDefault = false, isDefault = true))
    }
}
