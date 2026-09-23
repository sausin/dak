package app.dak.automations.birthdays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WishTemplatesTest {

    @Test
    fun `renders first name, name and age`() {
        assertEquals(
            "Happy birthday, Anita! You are 36.",
            WishTemplates.render("Happy birthday, {firstName}! You are {age}.", "Anita Rao", "Anita", 36),
        )
        assertEquals("Dear Anita Rao", WishTemplates.render("Dear {name}", "Anita Rao", null))
    }

    @Test
    fun `falls back to the first word, skipping titles`() {
        assertEquals("Hi Anita!", WishTemplates.render("Hi {firstName}!", "Dr. Anita Rao", " "))
        assertEquals("Ravi", WishTemplates.firstWord("Ravi"))
    }

    @Test
    fun `empty age collapses cleanly and unknown placeholders pass through`() {
        assertEquals("Turning! {emoji}", WishTemplates.render("Turning {age}! {emoji}", "A", "A", null))
    }

    @Test
    fun `hindi default keeps devanagari intact`() {
        val hi = WishTemplates.birthdayDefaults.first { it.language == "hi" }.text
        assertEquals(
            "जन्मदिन की हार्दिक शुभकामनाएँ, रवि! आपका आने वाला साल खुशियों से भरा हो।",
            WishTemplates.render(hi, "रवि कुमार", "रवि"),
        )
    }

    @Test
    fun `wish tags round trip and reject junk`() {
        val tag = WishTag(ask = true, contactId = 42, kind = OccasionKind.BIRTHDAY, year = 2026)
        assertEquals(tag, WishTag.decode(tag.encode()))
        assertEquals("42:BIRTHDAY:2026", tag.dedupeKey)
        assertNull(WishTag.decode(null))
        assertNull(WishTag.decode("rule-123"))
        assertNull(WishTag.decode("birthday:maybe:42:BIRTHDAY:2026"))
        assertNull(WishTag.decode("birthday:auto:x:BIRTHDAY:2026"))
    }

    @Test
    fun `wish tags tell unattended wishes from confirmed ones`() {
        val auto = WishTag(ask = false, contactId = 7, kind = OccasionKind.ANNIVERSARY, year = 2027)
        assertEquals("birthday:auto:7:ANNIVERSARY:2027", auto.encode())
        assertTrue(auto.unattended)
        val confirmed = auto.copy(confirmed = true)
        assertEquals("birthday:sent:7:ANNIVERSARY:2027", confirmed.encode())
        assertEquals(confirmed, WishTag.decode(confirmed.encode()))
        assertFalse(confirmed.unattended)
        assertEquals(auto.dedupeKey, confirmed.dedupeKey)
        assertFalse(WishTag(ask = true, contactId = 7, kind = OccasionKind.BIRTHDAY, year = 2027).unattended)
        // Tags written before "sent" existed still decode.
        assertEquals(auto, WishTag.decode("birthday:auto:7:ANNIVERSARY:2027"))
    }
}
