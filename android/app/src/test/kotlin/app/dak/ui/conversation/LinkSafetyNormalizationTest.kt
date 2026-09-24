package app.dak.ui.conversation

import app.dak.classify.LinkExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkSafetyNormalizationTest {

    @Test
    fun plainWebLinksPassThrough() {
        assertEquals("https://bank.example/login?a=1", LinkSafety.normalizedWebLink("https://bank.example/login?a=1"))
        assertEquals("http://x.example", LinkSafety.normalizedWebLink("  http://x.example "))
        assertEquals("https://www.bank.example/x", LinkSafety.normalizedWebLink("www.bank.example/x"))
    }

    @Test
    fun onlyHttpAndHttpsEverOpen() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.evil;end", "javascript:alert(1)", "content://mms/part/1",
            "file:///data/data/app.dak/databases/index.db", "market://details?id=com.evil", "tel:+1900555", "sms:+1900555?body=x",
            "data:text/html,<script>", "ftp://x.example/", "//x.example/", "", "   ",
        ).forEach { assertNull(it, LinkSafety.normalizedWebLink(it)) }
    }

    @Test
    fun backslashesAreNormalisedLikeBrowsersDo() {
        // Checked and opened by the browser as bank.example; android.net.Uri alone would resolve host evil.example.
        assertEquals("https://bank.example/@evil.example/", LinkSafety.normalizedWebLink("https://bank.example\\@evil.example\\"))
    }

    @Test
    fun whitespaceControlAndOversizedLinksAreRefused() {
        assertNull(LinkSafety.normalizedWebLink("https://bank.example/\u0000x"))
        assertNull(LinkSafety.normalizedWebLink("https://bank.example/a b"))
        assertNull(LinkSafety.normalizedWebLink("https://x.example/" + "a".repeat(LinkSafety.MAX_LINK_CHARS)))
    }

    @Test
    fun realHostIsPunycodeForHomographsAndUnicodeForReadableIdns() {
        fun host(body: String, vararg scripts: Character.UnicodeScript) =
            LinkSafety.shownHost(LinkExtractor.extract(body).single(), scripts.toSet())
        assertEquals("xn--pple-43d.com", host("https://аpple.com/")) // Cyrillic а
        assertEquals("xn--pple-43d.com", host("https://аpple.com/", Character.UnicodeScript.CYRILLIC))
        assertEquals("उदाहरण.भारत", host("https://उदाहरण.भारत/"))
        assertEquals("उदाहरण.com", host("https://उदाहरण.com/", Character.UnicodeScript.DEVANAGARI))
        assertEquals("xn--p1b6ci4b4b3a.com", host("https://उदाहरण.com/"))
        assertEquals("evil.xyz", host("https://hdfcbank.com@evil.xyz/"))
    }
}
