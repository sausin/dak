package app.dak.safety

import org.junit.Test
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FraudReportTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    // 2026-09-21 10:15 IST
    private val date = 1_758_429_900_000L + 365L * 24 * 3600 * 1000

    @Test
    fun traiComplaintUsesTextSenderDate() {
        val body = FraudReport.traiComplaintBody("Win Rs 5000!\nClick bit.ly/x", "VM-ABCDEF", date, timeZone = ist)
        assertEquals("Win Rs 5000! Click bit.ly/x,VM-ABCDEF,21/09/26", body)
    }

    @Test
    fun indianNumbersAreReducedToTenDigits() {
        assertEquals("9812345678", FraudReport.complaintSender("+91 98123 45678"))
        assertEquals("9812345678", FraudReport.complaintSender("09812345678"))
        assertEquals("9812345678", FraudReport.complaintSender("9812345678"))
        assertEquals("AD-SPAMMR", FraudReport.complaintSender(" AD-SPAMMR "))
        assertEquals("+971501234567".filter { it.isDigit() }, FraudReport.complaintSender("+971501234567"))
    }

    @Test
    fun bundleFormatIsHonouredAndBadFormatsFallBack() {
        val custom = FraudReport.traiComplaintBody("hi", "9812345678", date, "{sender} {date:yyyy-MM-dd}: {text}", ist)
        assertEquals("9812345678 2026-09-21: hi", custom)
        val fallback = FraudReport.traiComplaintBody("hi", "9812345678", date, "no placeholders", ist)
        assertEquals("hi,9812345678,21/09/26", fallback)
    }

    @Test
    fun longTextIsCutAndPlaceholdersInTextAreNotExpanded() {
        val long = "x".repeat(1000)
        val body = FraudReport.traiComplaintBody(long, "VM-A", date, timeZone = ist)
        assertTrue(body.startsWith("x".repeat(FraudReport.MAX_TEXT_CHARS) + ","))
        val tricky = FraudReport.traiComplaintBody("pay {sender} now", "VM-A", date, timeZone = ist)
        assertEquals("pay {sender} now,VM-A,21/09/26", tricky)
    }

    @Test
    fun detailsTextListsEverything() {
        val labels = FraudReport.DetailLabels("Sender", "Received", "SIM", "Message")
        val text = FraudReport.detailsText("Your KYC expired", "VK-BANKXX", date, "SIM 2", labels, ist)
        assertEquals("Sender: VK-BANKXX\nReceived: 21/09/2026 10:15\nSIM: SIM 2\nMessage:\nYour KYC expired", text)
        val noSim = FraudReport.detailsText("t", "s", date, null, labels, ist)
        assertTrue("SIM" !in noSim)
    }
}
