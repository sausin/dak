package app.dak.mms.pdu

import app.dak.mms.pdu.NotificationFloodGuard.Decision
import kotlin.test.assertEquals
import org.junit.Test

class NotificationFloodGuardTest {

    @Test
    fun oneSenderGetsItsBudgetThenTapToDownload() {
        val guard = NotificationFloodGuard(maxAutoPerSender = 3, maxAutoPerWindow = 10, maxStoredPerWindow = 50, windowMillis = 1_000)
        val decisions = (0 until 5).map { guard.decide("+15550001111", nowMillis = it.toLong()) }
        assertEquals(List(3) { Decision.AUTO_DOWNLOAD } + List(2) { Decision.MANUAL }, decisions)
        // Another sender is not penalised; the same sender (differently spelled case/whitespace) still is.
        assertEquals(Decision.AUTO_DOWNLOAD, guard.decide("+15550002222", 10))
        assertEquals(Decision.MANUAL, guard.decide("  +15550001111 ", 11))
    }

    @Test
    fun manySendersHitTheDeviceBudget() {
        val guard = NotificationFloodGuard(maxAutoPerSender = 3, maxAutoPerWindow = 5, maxStoredPerWindow = 50, windowMillis = 1_000)
        val decisions = (0 until 8).map { guard.decide("sender$it", it.toLong()) }
        assertEquals(5, decisions.count { it == Decision.AUTO_DOWNLOAD })
        assertEquals(3, decisions.count { it == Decision.MANUAL })
    }

    @Test
    fun floodIsDroppedAndTheWindowRecovers() {
        val guard = NotificationFloodGuard(maxAutoPerSender = 2, maxAutoPerWindow = 4, maxStoredPerWindow = 10, windowMillis = 1_000)
        val decisions = (0 until 1_000).map { guard.decide(null, it / 100L) }
        assertEquals(10, decisions.count { it != Decision.DROP })
        assertEquals(990, decisions.count { it == Decision.DROP })
        // After the window has passed, a legitimate notification downloads again.
        assertEquals(Decision.AUTO_DOWNLOAD, guard.decide("+15550003333", 5_000))
    }
}
