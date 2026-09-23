package app.dak.automations.forwarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipientRiskTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_750_000_000_000L

    private fun assess(
        number: String = "+91 98765 43210",
        updated: Long? = now - 400 * day,
        history: Boolean? = true,
        country: String? = "in",
    ) = RecipientRiskHeuristic.assess(RecipientFacts(number, updated, history, country), now)

    @Test
    fun `a long known Indian mobile you have texted is not flagged`() {
        assertTrue(assess().isEmpty())
    }

    @Test
    fun `recently added or edited contacts are flagged`() {
        assertEquals(setOf(RecipientRisk.RECENTLY_CHANGED_CONTACT), assess(updated = now - 10 * 60_000))
        assertEquals(setOf(RecipientRisk.RECENTLY_CHANGED_CONTACT), assess(updated = now - 6 * day))
        assertTrue(assess(updated = now - 8 * day).isEmpty())
        // A timestamp far in the future (clock tampering / bad sync) is suspicious too; unknown is not.
        assertEquals(setOf(RecipientRisk.RECENTLY_CHANGED_CONTACT), assess(updated = now + 3 * day))
        assertTrue(assess(updated = null).isEmpty())
        assertTrue(assess(updated = 0L).isEmpty())
    }

    @Test
    fun `no message history is flagged, unknown history is not`() {
        assertEquals(setOf(RecipientRisk.NO_MESSAGE_HISTORY), assess(history = false))
        assertTrue(assess(history = null).isEmpty())
    }

    @Test
    fun `all signals combine`() {
        assertEquals(
            setOf(RecipientRisk.RECENTLY_CHANGED_CONTACT, RecipientRisk.NO_MESSAGE_HISTORY, RecipientRisk.INTERNATIONAL_NUMBER),
            assess(number = "+44 7700 900123", updated = now - day, history = false),
        )
    }

    @Test
    fun `Indian mobile formats are accepted`() {
        listOf("9876543210", "+919876543210", "+91 98765-43210", "0091 98765 43210", "919876543210", "09876543210", "6123456789")
            .forEach { assertNull(RecipientRiskHeuristic.numberRisk(it, "IN"), it) }
        // No SIM country: treated as India.
        assertNull(RecipientRiskHeuristic.numberRisk("9876543210", null))
    }

    @Test
    fun `foreign, landline, toll free, short and odd numbers are flagged on an Indian SIM`() {
        assertEquals(RecipientRisk.INTERNATIONAL_NUMBER, RecipientRiskHeuristic.numberRisk("+44 7700 900123", "in"))
        assertEquals(RecipientRisk.INTERNATIONAL_NUMBER, RecipientRiskHeuristic.numberRisk("00971501234567", "in"))
        listOf("022 2345 6789", "+91 22 2345 6789", "1800 123 4567", "5676791", "12345", "98765432", "98765432101", "5876543210", "VM-HDFCBK")
            .forEach { assertEquals(RecipientRisk.UNUSUAL_NUMBER, RecipientRiskHeuristic.numberRisk(it, "IN"), it) }
    }

    @Test
    fun `other home countries only flag malformed numbers`() {
        assertNull(RecipientRiskHeuristic.numberRisk("+44 7700 900123", "gb"))
        assertNull(RecipientRiskHeuristic.numberRisk("+91 98765 43210", "gb"))
        assertEquals(RecipientRisk.UNUSUAL_NUMBER, RecipientRiskHeuristic.numberRisk("12345", "gb"))
        assertEquals(RecipientRisk.UNUSUAL_NUMBER, RecipientRiskHeuristic.numberRisk("+1234567890123456", "us"))
    }
}
