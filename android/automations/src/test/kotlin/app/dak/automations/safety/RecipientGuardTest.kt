package app.dak.automations.safety

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipientGuardTest {

    @Test
    fun `does not pause when the number is unchanged, even with different formatting`() {
        val snapshot = RecipientSnapshot("r1", "c1", "+91 98765 43210")
        assertFalse(shouldPause(snapshot, "9876543210"))
        assertFalse(shouldPause(snapshot, "0098765 43210"))
    }

    @Test
    fun `pauses when the number changed`() {
        val snapshot = RecipientSnapshot("r1", "c1", "9876543210")
        assertTrue(shouldPause(snapshot, "9123456780"))
    }

    @Test
    fun `pauses when the contact can no longer be resolved`() {
        val snapshot = RecipientSnapshot("r1", "c1", "9876543210")
        assertTrue(shouldPause(snapshot, null))
    }

    @Test
    fun `contact check passes while the contact still lists the number`() {
        val snapshot = RecipientSnapshot("r1", "0r12-ABC", "+91 98765 43210")
        assertFalse(shouldPauseForContact(snapshot, listOf("080 2345 6789", "098765-43210")))
    }

    @Test
    fun `contact check pauses when the contact is gone or lost the number`() {
        val snapshot = RecipientSnapshot("r1", "0r12-ABC", "9876543210")
        assertTrue(shouldPauseForContact(snapshot, null))
        assertTrue(shouldPauseForContact(snapshot, emptyList()))
        assertTrue(shouldPauseForContact(snapshot, listOf("9123456780")))
    }
}
