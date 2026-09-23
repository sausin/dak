package app.dak.classify.unicode

import java.lang.Character.UnicodeScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfusablesTest {

    @Test
    fun `homographs share the skeleton of what they imitate`() {
        val target = Confusables.skeleton("hdfcbank")
        listOf(
            "hdfcbаnk", // Cyrillic а
            "hdfcbαnk".replace('α', 'а'),
            "ｈｄｆｃｂａｎｋ", // fullwidth
            "𝐡𝐝𝐟𝐜𝐛𝐚𝐧𝐤", // mathematical bold
            "hdfc\u200Bbank", // zero width space
        ).forEach { assertEquals(target, Confusables.skeleton(it), it) }
        assertTrue(Confusables.areConfusable("amazοn", "amazon")) // Greek omicron
        assertTrue(Confusables.areConfusable("paypal", "раураl")) // all Cyrillic
        assertTrue(Confusables.areConfusable("rn", "m"))
        assertTrue(Confusables.areConfusable("HDFC0", "HDFCO"))
        assertTrue(Confusables.areConfusable("HDFC০", "HDFCo")) // Bengali zero, prototype "o"
        assertEquals(Confusables.caseFoldedSkeleton("HDFCO"), Confusables.caseFoldedSkeleton("HDFC০"))
        assertEquals(Confusables.caseFoldedSkeleton("ICICI"), Confusables.caseFoldedSkeleton("ІСІСІ")) // Cyrillic
        assertTrue(Confusables.areConfusable("SBI", "SB1"))
        assertFalse(Confusables.areConfusable("hdfcbank", "icicibank"))
    }

    @Test
    fun `loose skeleton also drops diacritics`() {
        assertNotEquals(Confusables.skeleton("hdfcbank"), Confusables.skeleton("hdfcbạnk"))
        assertEquals(Confusables.looseSkeleton("hdfcbank"), Confusables.looseSkeleton("hdfcbạnk"))
        assertEquals(Confusables.looseSkeleton("hdfcbank"), Confusables.looseSkeleton("hdfcbänk"))
    }

    @Test
    fun `ascii look-alikes are told from real non-Latin text`() {
        assertTrue(Confusables.isAsciiLookalike("НDFCBK")) // Cyrillic Н
        assertTrue(Confusables.isAsciiLookalike("АХІЅ")) // all Cyrillic
        assertTrue(Confusables.isAsciiLookalike("ＳＢＩ"))
        assertTrue(Confusables.isAsciiLookalike("раураl"))
        assertFalse(Confusables.isAsciiLookalike("HDFCBK"), "plain ASCII is not a look-alike of itself")
        assertFalse(Confusables.isAsciiLookalike("उदाहरण"))
        assertFalse(Confusables.isAsciiLookalike("пример"))
        assertFalse(Confusables.isAsciiLookalike("مثال"))
        assertFalse(Confusables.isAsciiLookalike("bücher"))
    }
}

class ScriptCheckTest {

    @Test
    fun `restriction levels follow UTS 39`() {
        assertEquals(RestrictionLevel.ASCII, ScriptCheck.restrictionLevel("HDFCBK"))
        assertEquals(RestrictionLevel.SINGLE_SCRIPT, ScriptCheck.restrictionLevel("उदाहरण।"))
        assertEquals(RestrictionLevel.SINGLE_SCRIPT, ScriptCheck.restrictionLevel("пример"))
        assertEquals(RestrictionLevel.SINGLE_SCRIPT, ScriptCheck.restrictionLevel("bücher"))
        assertEquals(RestrictionLevel.HIGHLY_RESTRICTIVE, ScriptCheck.restrictionLevel("abc例えテスト"))
        assertEquals(RestrictionLevel.MODERATELY_RESTRICTIVE, ScriptCheck.restrictionLevel("abcउदाहरण"))
        assertEquals(RestrictionLevel.MINIMALLY_RESTRICTIVE, ScriptCheck.restrictionLevel("hdfcbаnk"))
        assertEquals(RestrictionLevel.MINIMALLY_RESTRICTIVE, ScriptCheck.restrictionLevel("amazοn"))
        assertEquals(RestrictionLevel.MINIMALLY_RESTRICTIVE, ScriptCheck.restrictionLevel("উদাহরণउदाहरण"))
    }

    @Test
    fun `mixed number systems are detected`() {
        assertTrue(ScriptCheck.hasMixedNumbers("12৩4"))
        assertTrue(ScriptCheck.hasMixedNumbers("HDFC০1"))
        assertFalse(ScriptCheck.hasMixedNumbers("१२३४"))
        assertFalse(ScriptCheck.hasMixedNumbers("12３4"), "fullwidth digits fold to ASCII")
        assertFalse(ScriptCheck.hasMixedNumbers("HDFCBK"))
    }

    @Test
    fun `mixed-script words are flagged, mixed-script names of separate words are not`() {
        assertTrue(ScriptCheck.hasMixedScriptWord("НDFC Bank")) // Cyrillic Н inside a Latin word
        assertTrue(ScriptCheck.hasMixedScriptWord("PayPаl"))
        assertTrue(ScriptCheck.hasMixedScriptWord("SBI০1")) // Bengali zero among ASCII digits
        assertFalse(ScriptCheck.hasMixedScriptWord("SBI১"), "Latin + Bengali (digit) is moderately restrictive")
        assertFalse(ScriptCheck.hasMixedScriptWord("Ramesh रमेश"))
        assertFalse(ScriptCheck.hasMixedScriptWord("Анна Smith"))
        assertFalse(ScriptCheck.hasMixedScriptWord("محمد علی"))
        assertFalse(ScriptCheck.hasMixedScriptWord("रमेश कुमार"))
        assertFalse(ScriptCheck.hasMixedScriptWord("ᱥᱟᱱᱛᱟᱲᱤ"), "Ol Chiki alone is one script")
        assertFalse(ScriptCheck.hasMixedScriptWord("abcउदाहरण"), "Latin with one Indic script is moderately restrictive")
    }

    @Test
    fun `reader scripts come from the language list`() {
        assertEquals(setOf(UnicodeScript.DEVANAGARI), ScriptCheck.scriptsForLanguages(listOf("hi-IN")))
        assertEquals(setOf(UnicodeScript.ARABIC, UnicodeScript.BENGALI), ScriptCheck.scriptsForLanguages(listOf("ur-IN", "bn")))
        assertEquals(setOf(UnicodeScript.LATIN), ScriptCheck.scriptsForLanguages(listOf("sr-Latn-RS")))
        assertEquals(emptySet(), ScriptCheck.scriptsForLanguages(listOf("en-IN")))
    }
}

class HostDisplayTest {

    private val english = emptySet<UnicodeScript>()
    private val russian = setOf(UnicodeScript.CYRILLIC)

    @Test
    fun `homographs are shown in punycode`() {
        assertEquals("xn--pple-43d.com", HostDisplay.displayHost("xn--pple-43d.com", english)) // аpple
        assertEquals("xn--pple-43d.com", HostDisplay.displayHost("xn--pple-43d.com", russian), "mixed Cyrillic + Latin")
        assertEquals("xn--hdfcbnk-6fg.com", HostDisplay.displayHost("xn--hdfcbnk-6fg.com", english))
        // Whole-script confusable: all Cyrillic, but spells "paypal": punycode even for a Russian reader.
        assertEquals("xn--l-7sba6dbr.com", HostDisplay.displayHost("xn--l-7sba6dbr.com", russian))
        // Bengali zero inside a Latin label: mixed scripts.
        assertEquals("xn--hdfcbank-2xr.com", HostDisplay.displayHost("xn--hdfcbank-2xr.com", english))
    }

    @Test
    fun `real IDNs are shown in Unicode to readers of their script`() {
        // .भारत only takes Devanagari names, so its script is readable for everyone.
        assertEquals("उदाहरण.भारत", HostDisplay.displayHost("xn--p1b6ci4b4b3a.xn--h2brj9c", english))
        assertEquals("উদাহরণ.ভারত", HostDisplay.displayHost("xn--d5b6ci4b4b3a.xn--45brj9c", english))
        assertEquals("пример.рф", HostDisplay.displayHost("xn--e1afmkfd.xn--p1ai", english))
        assertEquals("bücher.de", HostDisplay.displayHost("xn--bcher-kva.de", english))
        // A Devanagari name under .com: Unicode for a Hindi reader, punycode for others.
        assertEquals("उदाहरण.com", HostDisplay.displayHost("xn--p1b6ci4b4b3a.com", setOf(UnicodeScript.DEVANAGARI)))
        assertEquals("xn--p1b6ci4b4b3a.com", HostDisplay.displayHost("xn--p1b6ci4b4b3a.com", english))
        assertEquals("مثال.com", HostDisplay.displayHost("xn--mgbh0fb.com", setOf(UnicodeScript.ARABIC)))
    }

    @Test
    fun `invalid or plain hosts are shown as they are`() {
        assertEquals("example.com", HostDisplay.displayHost("Example.COM"))
        assertEquals("xn--zz-.com", HostDisplay.displayHost("xn--zz-.com"))
        assertEquals("xn--a.com", HostDisplay.displayHost("xn--a.com"))
    }
}

class UntrustedTextTest {

    private val fsi = UntrustedText.FSI
    private val pdi = UntrustedText.PDI

    @Test
    fun `isolate wraps in FSI and PDI`() {
        assertEquals("${fsi}محمد$pdi", UntrustedText.isolate("محمد"))
        assertEquals("${fsi}Ramesh$pdi", UntrustedText.isolate("Ramesh"))
        assertEquals("\u2066https://x.example/א$pdi", UntrustedText.isolateLtr("https://x.example/א"))
    }

    @Test
    fun `RLO file-name trick is neutralised`() {
        // "\u202Egpj.exe" displays as "exe.jpg"; without the override it reads as what it is.
        assertEquals("${fsi}gpj.exe$pdi", UntrustedText.isolate("\u202Egpj.exe"))
        assertEquals("gpj.exe", UntrustedText.sanitizeName("\u202Egpj.exe"))
        assertEquals("invoicegpj.exe", UntrustedText.sanitizeName("invoice\u202Egpj.exe\u202C"))
    }

    @Test
    fun `text cannot close the isolate early`() {
        // A stray PDI would end our isolate; an unterminated RLO would reverse the rest of the paragraph.
        val hostile = "Bank$pdi\u202E 005 sR"
        val isolated = UntrustedText.isolate(hostile)
        assertEquals(1, isolated.count { it == pdi })
        assertTrue(isolated.endsWith(pdi))
        assertFalse(isolated.any { it in '\u202A'..'\u202E' })
        for (c in "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069") {
            assertEquals("${fsi}ab$pdi", UntrustedText.isolate("a${c}b"), "U+%04X".format(c.code))
        }
    }

    @Test
    fun `implicit RTL text is untouched`() {
        val urdu = "عمران خان"
        assertEquals(urdu, UntrustedText.sanitizeName(urdu))
        assertEquals(urdu, UntrustedText.neutralize(urdu))
        val mixed = "Amit \u200F(امیت)"
        assertEquals("Amit (امیت)", UntrustedText.sanitizeName(mixed), "RLM removed from a name")
        assertEquals(mixed, UntrustedText.neutralize(mixed), "marks stay in message text")
    }

    @Test
    fun `joiners stay where Indic, Arabic and emoji need them`() {
        val halfForm = "क्\u200Dष" // ZWJ after a virama
        assertEquals(halfForm, UntrustedText.sanitizeName(halfForm))
        val zwnj = "ज्\u200Cञ"
        assertEquals(zwnj, UntrustedText.sanitizeName(zwnj))
        val persian = "می\u200Cخواهم"
        assertEquals(persian, UntrustedText.sanitizeName(persian))
        val family = "👨\u200D👩\u200D👧"
        assertEquals(family, UntrustedText.sanitizeName(family))
        assertEquals("HDFCBK", UntrustedText.sanitizeName("\u200DHDFC\u200BBK\u200C"))
        assertEquals("HDFC BK", UntrustedText.sanitizeName("HDFC\u200D BK"))
    }

    @Test
    fun `offsets survive neutralisation of a body`() {
        val body = "Pay at \u202Ehttps://moc.knabcfdh now"
        val shown = UntrustedText.neutralizeKeepingOffsets(body)
        assertEquals(body.length, shown.length)
        assertEquals(body.indexOf("https"), shown.indexOf("https"))
        assertFalse(shown.any { UntrustedText.isScopedBidiControl(it) })
        val plain = "no controls here"
        assertTrue(plain === UntrustedText.neutralizeKeepingOffsets(plain))
    }
}
