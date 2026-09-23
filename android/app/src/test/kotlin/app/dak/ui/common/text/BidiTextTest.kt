package app.dak.ui.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UAX #9 isolation of untrusted text (docs/standards-compliance.md §14). */
class BidiTextTest {

    private val fsi = '\u2068'
    private val pdi = '\u2069'

    @Test
    fun usesRealIsolatesNotEmbeddings() {
        val wrapped = BidiText.isolate("محمد")
        assertEquals("${fsi}محمد$pdi", wrapped)
        assertFalse(wrapped.any { it in '\u202A'..'\u202E' || it == '\u200E' || it == '\u200F' })
    }

    @Test
    fun arabicNameBeforeAnAmountStaysInItsOwnSpan() {
        // Composed like the UI does: the amount sits outside the isolate, so the name cannot pull it into RTL order.
        val line = BidiText.displaySafe("عمران") + " ₹500"
        assertEquals("${fsi}عمران$pdi ₹500", line)
    }

    @Test
    fun rloTricksAreNeutralised() {
        assertEquals("${fsi}gpj.exe$pdi", BidiText.displaySafe("\u202Egpj.exe"))
        assertEquals("${fsi}gpj.exe$pdi", BidiText.isolate("\u202Egpj.exe"))
        // A stray PDI inside cannot close the wrapper early.
        val hostile = BidiText.isolate("Bank$pdi\u202E 005 sR")
        assertEquals(1, hostile.count { it == pdi })
        assertTrue(hostile.endsWith(pdi))
    }

    @Test
    fun mixedDirectionNamesKeepTheirLetters() {
        assertEquals("Amit (امیت)", BidiText.sanitizeDisplayName("Amit \u200F(امیت)"))
        assertEquals("क्\u200Dष", BidiText.sanitizeDisplayName("क्\u200Dष"))
        assertEquals("\u2066https://x.example/$pdi", BidiText.isolateLtr("https://x.example/"))
    }
}
