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

    @Test
    fun `other dates name the occasion, or read special day without a label`() {
        assertEquals("Happy graduation, Anita! Thinking of you today.", WishTemplates.render(WishTemplates.DEFAULT_OTHER, "Anita Rao", "Anita", occasion = "Graduation"))
        assertEquals("Happy special day, Anita! Thinking of you today.", WishTemplates.render(WishTemplates.DEFAULT_OTHER, "Anita Rao", "Anita", occasion = " "))
        assertEquals(WishTemplates.otherDefaults, WishTemplates.defaultsFor(OccasionKind.OTHER))
    }

    @Test
    fun `other date wish tags round-trip`() {
        val tag = WishTag(ask = true, contactId = 9, kind = OccasionKind.OTHER, year = 2027)
        assertEquals(tag, WishTag.decode(tag.encode()))
        assertEquals("9:OTHER:2027", tag.dedupeKey)
    }

    @Test
    fun `built-in defaults stay english and every preset keeps its id`() {
        // Pinned before language-aware defaults: the constants (what existing installs stored) never change.
        assertEquals("Happy birthday, {firstName}! Wishing you a wonderful year ahead.", WishTemplates.DEFAULT_BIRTHDAY)
        assertEquals("Happy anniversary, {firstName}! Wishing you many more happy years together.", WishTemplates.DEFAULT_ANNIVERSARY)
        assertEquals("Happy {occasion}, {firstName}! Thinking of you today.", WishTemplates.DEFAULT_OTHER)
        assertEquals("special day", WishTemplates.FALLBACK_OCCASION)
        assertEquals(
            listOf("en_warm", "en_short", "en_formal", "hi_warm", "hi_latin", "en_anniv", "hi_anniv", "en_other", "en_other_short", "hi_other"),
            OccasionKind.entries.flatMap { WishTemplates.defaultsFor(it) }.map { it.id },
        )
    }

    @Test
    fun `new users get the preset of their app language`() {
        assertEquals("hi_warm", WishTemplates.defaultFor(OccasionKind.BIRTHDAY, "hi").id)
        assertEquals("hi_warm", WishTemplates.defaultFor(OccasionKind.BIRTHDAY, "hi-IN").id)
        assertEquals("hi_warm", WishTemplates.defaultFor(OccasionKind.BIRTHDAY, "hi_IN").id)
        assertEquals("hi_latin", WishTemplates.defaultFor(OccasionKind.BIRTHDAY, "hi-Latn-IN").id)
        assertEquals("hi_anniv", WishTemplates.defaultFor(OccasionKind.ANNIVERSARY, "hi-IN").id)
        assertEquals("hi_other", WishTemplates.defaultFor(OccasionKind.OTHER, "HI").id)
        for (tag in listOf("en-IN", "en", "ta-IN", "fr", "", null, "hin")) {
            assertEquals("en_warm", WishTemplates.defaultFor(OccasionKind.BIRTHDAY, tag).id, "$tag")
        }
        assertEquals(WishTemplates.DEFAULT_BIRTHDAY, WishTemplates.defaultFor(OccasionKind.BIRTHDAY, "en").text)
    }

    @Test
    fun `the fallback occasion and the label case follow the app language`() {
        assertEquals(
            "Happy día especial, Anita! Thinking of you today.",
            WishTemplates.render(WishTemplates.DEFAULT_OTHER, "Anita Rao", "Anita", fallbackOccasion = "día especial"),
        )
        val tr = java.util.Locale.forLanguageTag("tr")
        assertEquals("Happy izin günü, Ayşe! Thinking of you today.", WishTemplates.render(WishTemplates.DEFAULT_OTHER, "Ayşe", null, occasion = "İzin Günü", locale = tr))
        // Default stays locale-invariant.
        assertEquals("Happy graduation, Anita! Thinking of you today.", WishTemplates.render(WishTemplates.DEFAULT_OTHER, "Anita", null, occasion = "GRADUATION"))
    }
}
