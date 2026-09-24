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
    fun theLimitIsExact() {
        val atLimit = "y".repeat(NotificationText.MAX_BODY_CHARS)
        assertEquals(atLimit, NotificationText.body(atLimit))
        val over = atLimit + "z"
        assertEquals(atLimit + "…", NotificationText.body(over))
    }

    @Test
    fun everyExplicitControlIsStrippedEvenPastTheCutAndNothingElse() {
        val controls = ('\u202A'..'\u202E') + ('\u2066'..'\u2069')
        for (c in controls) assertEquals("U+%04X".format(c.code), "ab", NotificationText.body("a${c}b"))
        assertEquals("", NotificationText.body(controls.joinToString("")))
        // Implicit marks (LRM, RLM, ALM) and neighbours of the ranges are ordinary text.
        for (c in listOf('\u200E', '\u200F', '\u061C', '\u2029', '\u202F', '\u2065', '\u206A')) {
            assertEquals("U+%04X".format(c.code), "a${c}b", NotificationText.body("a${c}b"))
        }
        // A huge body made of controls and text still ends up bounded.
        val hostile = "\u202E1".repeat(NotificationText.MAX_BODY_CHARS)
        val shown = NotificationText.body(hostile)
        assertTrue(shown.length <= NotificationText.MAX_BODY_CHARS + 1)
        assertTrue(shown.none { it in controls })
    }

    @Test
    fun explicitBidiControlsAreStripped() {
        // "Credited Rs 10" followed by an RLO that would visually reverse the digits that follow.
        val spoof = "Credited Rs 10‮00000‬ to A/c 1234 ⁧isolated⁩"
        assertEquals("Credited Rs 1000000 to A/c 1234 isolated", NotificationText.body(spoof))
    }
}
