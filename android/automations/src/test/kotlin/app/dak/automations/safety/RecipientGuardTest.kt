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
}
