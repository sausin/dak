package app.dak.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledHeadsUpLeadTest {
    @Test
    fun `defaults to fifteen minutes and lives in automations`() {
        assertEquals("15", DakSettings.scheduledHeadsUp.default)
        assertEquals(15, ScheduledHeadsUpLead.minutesOf(DakSettings.scheduledHeadsUp.default))
        assertEquals(SettingsGroup.AUTOMATIONS, DakSettings.scheduledHeadsUp.group)
        assertTrue(DakSettings.scheduledHeadsUp in DakSettings.all)
    }

    @Test
    fun `offers off, 5, 15 and 60 minutes`() {
        assertEquals(
            listOf("off", "5", "15", "60"),
            (DakSettings.scheduledHeadsUp.control as ControlType.SingleChoice).options.map { it.value },
        )
        assertNull(ScheduledHeadsUpLead.minutesOf("off"))
        assertEquals(5, ScheduledHeadsUpLead.minutesOf("5"))
        assertEquals(60, ScheduledHeadsUpLead.minutesOf("60"))
    }

    @Test
    fun `unknown or missing values fall back to the default lead`() {
        assertEquals(15, ScheduledHeadsUpLead.minutesOf(null))
        assertEquals(15, ScheduledHeadsUpLead.minutesOf("7"))
        assertNull(DakSettings.scheduledHeadsUp.deserialize("7"))
        assertEquals("60", DakSettings.scheduledHeadsUp.deserialize("60"))
    }

    @Test
    fun `found by searching for birthday or reminder`() {
        assertTrue("birthday" in DakSettings.scheduledHeadsUp.keywords)
        assertTrue("reminder" in DakSettings.scheduledHeadsUp.keywords)
    }
}
