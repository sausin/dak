package app.dak.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {

    @Test
    fun shortBodiesAreUnchanged() {
        assertEquals("Your code is 123456", NotificationText.body("Your code is 123456"))
        // Implicit RTL text (Arabic) keeps its characters; only explicit controls go.
        assertEquals("رمز التحقق 4821", NotificationText.body("رمز التحقق 4821"))
    }

    @Test
    fun hugeBodiesAreCut() {
        val shown = NotificationText.body("x".repeat(1_000_000))
        assertEquals(NotificationText.MAX_BODY_CHARS + 1, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun cutNeverSplitsASurrogatePair() {
        val raw = "a".repeat(NotificationText.MAX_BODY_CHARS - 1) + "😀" + "tail"
        val shown = NotificationText.body(raw)
        assertFalse(Character.isHighSurrogate(shown[shown.length - 2]))
    }

    @Test
    fun explicitBidiControlsAreStripped() {
        // "Credited Rs 10" followed by an RLO that would visually reverse the digits that follow.
        val spoof = "Credited Rs 10‮00000‬ to A/c 1234 ⁧isolated⁩"
        assertEquals("Credited Rs 1000000 to A/c 1234 isolated", NotificationText.body(spoof))
    }
}
