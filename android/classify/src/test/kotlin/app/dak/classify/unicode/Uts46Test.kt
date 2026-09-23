package app.dak.classify.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class Uts46Test {

    private fun ascii(s: String) = Uts46.toAscii(s).also { assertTrue(it.ok, "$s: ${it.errors}") }.value

    @Test
    fun `nontransitional processing keeps deviation characters (IDNA2008, not IDNA2003)`() {
        assertEquals("xn--fa-hia.de", ascii("faß.de"))
        assertEquals("xn--fa-hia.de", ascii("Faß.DE"))
        assertEquals("faß.de", Uts46.toUnicode("xn--fa-hia.de").value)
        // Final sigma stays ς (IDNA2003 folded it to σ).
        assertEquals("xn--wxaijb9b.gr", ascii("σόλος.gr"))
    }

    @Test
    fun `maps case, fullwidth and ideographic full stops`() {
        assertEquals("hdfcbank.com", ascii("ＨＤＦＣＢａｎｋ．ｃｏｍ"))
        assertEquals("example.com", ascii("EXAMPLE。com"))
        assertEquals("hdfcbank.com", ascii("𝐡𝐝𝐟𝐜𝐛𝐚𝐧𝐤.com"))
    }

    @Test
    fun `converts homographs and real IDNs to punycode`() {
        assertEquals("xn--pple-43d.com", ascii("аpple.com")) // Cyrillic а
        assertEquals("xn--hdfcbnk-6fg.com", ascii("hdfcbаnk.com"))
        assertEquals("xn--p1b6ci4b4b3a.xn--h2brj9c", ascii("उदाहरण.भारत"))
        assertEquals("xn--d5b6ci4b4b3a.xn--45brj9c", ascii("উদাহরণ.ভারত"))
        assertEquals("xn--mgbh0fb.xn--kgbechtv", ascii("مثال.إختبار"))
        assertEquals("xn--e1afmkfd.xn--p1ai", ascii("пример.рф"))
        assertEquals("उदाहरण.भारत", Uts46.toUnicode("xn--p1b6ci4b4b3a.xn--h2brj9c").value)
        assertEquals("pаypal.com", Uts46.toUnicode("xn--pypal-4ve.com").value)
    }

    @Test
    fun `invalid labels are errors`() {
        val bad = listOf(
            "xn--a.com", // punycode that decodes to ASCII
            "xn--ls8h-.com".uppercase() + "\u0000", // disallowed control
            "a\u0301.com".let { "\u0301$it" }, // leading combining mark
            "xn--zz-.com", // invalid punycode
            "ab\u200Dc.com", // ZWJ outside a virama context (CONTEXTJ)
            "aא.com", // LTR label with an RTL letter in a Bidi domain (RFC 5893)
            "exa�mple.com",
        )
        for (host in bad) assertFalse(Uts46.toAscii(host).ok, host)
        // ZWJ after a virama is how a Devanagari half form is spelled: allowed.
        assertTrue(Uts46.toAscii("क्\u200Dष.com").ok)
        // Hyphen rules only apply when asked (browsers do not check them).
        assertTrue(Uts46.toAscii("ab--c.com").ok)
        assertFalse(Uts46.toAscii("ab--c.com", Uts46.STRICT).ok)
        assertFalse(Uts46.toAscii("-abc.com", Uts46.STRICT).ok)
        assertFalse(Uts46.toAscii("a_b.com", Uts46.STRICT).ok)
        assertFalse(Uts46.toAscii("a".repeat(64) + ".com", Uts46.STRICT).ok)
    }

    @Test
    fun `ignored characters disappear`() {
        assertEquals("hdfcbank.com", ascii("hdfc\u00ADbank.com")) // soft hyphen
        assertEquals("hdfcbank.com", ascii("hdfc\uFE0Fbank.com")) // variation selector
    }

    @Test
    fun `huge or hostile input does not throw`() {
        assertFalse(Uts46.toAscii("a".repeat(Uts46.MAX_INPUT + 1)).ok)
        Uts46.toAscii("xn--" + "9".repeat(900))
        Uts46.toAscii("\uD800.\uDFFF.xn--")
        Uts46.toUnicode(".".repeat(500))
    }

    /**
     * Unicode's own conformance file (IdnaTestV2, 17.0.0), limited to characters old enough for the JVM's Unicode data
     * (see gen_unicode_tables.py). Every flag on, as in the file; CONTEXTO is not implemented, so rows whose only
     * expected errors are CONTEXTO-free still have to match exactly.
     */
    @Test
    fun `IdnaTestV2 conformance`() {
        val stream = javaClass.getResourceAsStream("/app/dak/classify/unicode/IdnaTestV2-subset.txt") ?: fail("missing vectors")
        val options = Uts46.Options(
            checkHyphens = true, checkBidi = true, checkJoiners = true, useStd3AsciiRules = true, rejectEmptyLabels = true,
        )
        val failures = ArrayList<String>()
        var rows = 0
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val f = line.split(";").map { it.trim() }
                val source = unescape(f[0])
                val toUnicode = if (f[1].isEmpty()) source else unescape(f[1])
                val toUnicodeStatus = f[2]
                val toAsciiN = if (f[3].isEmpty()) toUnicode else unescape(f[3])
                val toAsciiStatus = if (f[4].isEmpty()) toUnicodeStatus else f[4]
                rows++

                val u = Uts46.toUnicode(source, options)
                val uErr = expectedErrors(toUnicodeStatus)
                if (u.ok == uErr) failures += "toUnicode($line) = $u"
                else if (!uErr && u.value != toUnicode) failures += "toUnicode($line) = ${u.value}"

                val a = Uts46.toAscii(source, options.copy(verifyDnsLength = true))
                val aErr = expectedErrors(toAsciiStatus)
                if (a.ok == aErr) failures += "toAscii($line) = $a"
                else if (!aErr && a.value != toAsciiN) failures += "toAscii($line) = ${a.value}"
            }
        }
        assertTrue(rows > 3_000, "only $rows rows")
        assertTrue(failures.isEmpty(), "${failures.size} of $rows rows fail:\n" + failures.take(60).joinToString("\n"))
    }

    private fun expectedErrors(status: String): Boolean = status.isNotEmpty() && status != "[]"

    private fun unescape(s: String): String {
        if (s == "\"\"") return ""
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s.startsWith("\\u", i) && i + 6 <= s.length) {
                sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            } else if (s.startsWith("\\x{", i)) {
                val end = s.indexOf('}', i)
                sb.appendCodePoint(s.substring(i + 3, end).toInt(16))
                i = end + 1
            } else {
                sb.append(s[i++])
            }
        }
        return sb.toString()
    }
}
