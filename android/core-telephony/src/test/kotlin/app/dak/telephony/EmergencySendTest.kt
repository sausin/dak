package app.dak.telephony

import app.dak.telephony.cost.EmergencyDestinations
import app.dak.telephony.send.SendRateLimiter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmergencySendTest {

    @Test
    fun emergencySendNeverWaitsEvenWhenABroadcastFilledTheWindow() {
        val limiter = SendRateLimiter(maxPerWindow = 30, windowMillis = 30 * 60_000L)
        val now = 1_000_000L
        // A 200-recipient broadcast books every slot for the next ~3 hours.
        repeat(200) { limiter.reserve(now) }
        assertTrue(limiter.reserve(now) > 0)
        // Text-to-112 goes out immediately...
        assertEquals(0L, limiter.reserveEmergency(now))
        assertEquals(0L, limiter.reserveEmergency(now + 1))
        // ...and still counts, so ordinary sends after it keep the platform counter in check.
        assertTrue(limiter.reserve(now + 2) > 0)
    }

    @Test
    fun reservationsSurviveARestart() {
        var saved: List<Long> = emptyList()
        val store = object : SendRateLimiter.Store {
            override fun load(): List<Long> = saved
            override fun save(reservations: List<Long>) { saved = reservations }
        }
        val first = SendRateLimiter(maxPerWindow = 2, windowMillis = 1_000, store = store)
        first.reserve(0)
        first.reserve(0)
        // The platform's counter lives in the phone process: a new Dak process must not reopen the window.
        val afterRestart = SendRateLimiter(maxPerWindow = 2, windowMillis = 1_000, store = store)
        assertEquals(1_000L, afterRestart.reserve(10) + 10)
    }

    @Test
    fun emergencyNumbersAreRecognised() {
        assertTrue(EmergencyDestinations.isEmergency("112", null, null))
        assertTrue(EmergencyDestinations.isEmergency(" 911 ", "IN", "IN"))
        assertTrue(EmergencyDestinations.isEmergency("999", "GB", "GB"))
        assertTrue(EmergencyDestinations.isEmergency("000", "AU", null))
        assertTrue(EmergencyDestinations.isEmergency("112", "in", "fr"))
        assertFalse(EmergencyDestinations.isEmergency("+919876543210", "IN", "IN"))
        assertFalse(EmergencyDestinations.isEmergency("57575", "US", "US"))
        assertFalse(EmergencyDestinations.isEmergency("HDFCBK", "IN", "IN"))
        assertFalse(EmergencyDestinations.isEmergency("", null, null))
    }
}
