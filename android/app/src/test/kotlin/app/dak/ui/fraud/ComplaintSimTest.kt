package app.dak.ui.fraud

import app.dak.core.model.NO_SUB_ID
import app.dak.core.model.SimInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SIM a TRAI 1909 complaint is sent from ([ComplaintSim]). */
class ComplaintSimTest {

    private val sim1 = SimInfo(subId = 1, slotIndex = 0, displayName = "Jio")
    private val sim2 = SimInfo(subId = 2, slotIndex = 1, displayName = "Airtel")

    @Test
    fun `uses the SIM that received the spam`() {
        val choice = ComplaintSim.choose(messageSubId = 2, sims = listOf(sim1, sim2), defaultSmsSubId = 1)
        assertEquals(2, choice.subId)
        assertEquals(sim2, choice.sim)
        assertTrue(choice.isReceivingSim)
    }

    @Test
    fun `falls back to the default SMS SIM when the receiving SIM is gone`() {
        val removed = sim2.copy(isActive = false)
        val choice = ComplaintSim.choose(messageSubId = 2, sims = listOf(sim1, removed), defaultSmsSubId = 1)
        assertEquals(1, choice.subId)
        assertFalse(choice.isReceivingSim)
        assertEquals(1, ComplaintSim.choose(messageSubId = 7, sims = listOf(sim1, sim2), defaultSmsSubId = 1).subId)
    }

    @Test
    fun `message without a SIM uses the default`() {
        val choice = ComplaintSim.choose(messageSubId = NO_SUB_ID, sims = listOf(sim1, sim2), defaultSmsSubId = 2)
        assertEquals(2, choice.subId)
        assertFalse(choice.isReceivingSim)
    }

    @Test
    fun `no default SMS SIM with one active SIM uses it`() {
        val choice = ComplaintSim.choose(messageSubId = 9, sims = listOf(sim1, sim2.copy(isActive = false)), defaultSmsSubId = NO_SUB_ID)
        assertEquals(1, choice.subId)
    }

    @Test
    fun `undecided when several SIMs and no default`() {
        val choice = ComplaintSim.choose(messageSubId = 9, sims = listOf(sim1, sim2), defaultSmsSubId = NO_SUB_ID)
        assertEquals(NO_SUB_ID, choice.subId)
        assertNull(choice.sim)
        val none = ComplaintSim.choose(messageSubId = 1, sims = emptyList(), defaultSmsSubId = 1)
        assertEquals(NO_SUB_ID, none.subId)
    }
}
