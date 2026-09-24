package app.dak.classify

import app.dak.classify.scam.FakeCreditDetector
import app.dak.classify.scam.ScamLevel
import app.dak.classify.scam.ScamReason
import app.dak.core.model.Category
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UTS #46 / UTS #39 applied to links and senders (docs/standards-compliance.md §14). */
class TextSafetyTest {

    private val checker = LookalikeDomainChecker()

    private fun link(body: String) = LinkExtractor.extract(body).single()

    @Test
    fun `link hosts go through UTS 46 nontransitional processing`() {
        assertEquals("xn--fa-hia.de", link("https://faß.de/x").asciiHost) // IDNA2003 gave fass.de
        assertEquals("hdfcbank.com", link("https://ＨＤＦＣＢＡＮＫ.ｃｏｍ/kyc").asciiHost)
        assertEquals("xn--p1b6ci4b4b3a.xn--h2brj9c", link("https://उदाहरण.भारत/").asciiHost)
        assertEquals("उदाहरण.भारत", link("https://xn--p1b6ci4b4b3a.xn--h2brj9c/").unicodeHost)
        assertEquals("example.com", link("https://Example.COM/").asciiHost)
        assertEquals("[::1]", link("http://[::1]/x").asciiHost)
    }

    @Test
    fun `hosts a browser would refuse have no ascii host and are flagged`() {
        for (body in listOf("https://xn--zz-.com/", "https://xn--a.com/", "https://evil.example／hdfcbank.com/", "https://aא.com/")) {
            val l = link(body)
            assertNull(l.asciiHost, body)
            assertTrue(l.isIdn, body)
            assertEquals(LinkRisk.LOOKALIKE, checker.check(l).risk, body)
        }
    }

    @Test
    fun `homograph hosts name the brand they imitate via the UTS 39 skeleton`() {
        mapOf(
            "https://hdfcbаnk.com/kyc" to "HDFC Bank", // Cyrillic а
            "https://іcicibank.com/" to "ICICI Bank", // Cyrillic і
            "https://ｈｄｆｃｂａｎｋ.ｃｏｍ/" to "HDFC Bank", // fullwidth: resolves to the real domain, but is an obfuscation
            "https://𝐡𝐝𝐟𝐜𝐛𝐚𝐧𝐤.com" to "HDFC Bank",
            "https://аmаzоn.in/deal" to "Amazon", // Cyrillic а, о
            "https://xn--hdfcbnk-6fg.com/" to "HDFC Bank", // the punycode form of the first
            "https://hdfcbạnk.com/" to "HDFC Bank", // Latin with a dot below
            "https://flipkаrt-sale.xyz/" to "Flipkart",
        ).forEach { (body, brand) ->
            val verdict = checker.check(link(body))
            assertEquals(LinkRisk.LOOKALIKE, verdict.risk, body)
            assertEquals(brand, verdict.matchedBrand, body)
        }
        // A real IDN imitates nobody but is still flagged (SMS phishing uses IDNs almost only as homographs).
        val hindi = checker.check(link("https://उदाहरण.भारत/"))
        assertEquals(LinkRisk.LOOKALIKE, hindi.risk)
        assertNull(hindi.matchedBrand)
        // Plain ASCII hosts are unchanged.
        assertEquals(LinkRisk.OFFICIAL, checker.check(link("https://www.hdfcbank.com/")).risk)
    }

    @Test
    fun `sender names with look-alike or mixed scripts are detected`() {
        assertNull(SenderNameCheck.check("VM-HDFCBK-S"))
        assertNull(SenderNameCheck.check("+919876543210"))
        val cyrillic = SenderNameCheck.check("НDFCBK")!! // Cyrillic Н
        assertTrue(cyrillic.mixedScript)
        assertTrue(cyrillic.asciiLookalike)
        assertTrue(SenderNameCheck.check("ＨＤＦＣＢＫ")!!.asciiLookalike)
        assertTrue(SenderNameCheck.check("АХІЅВК")!!.asciiLookalike) // all Cyrillic, no mixing
        assertTrue(SenderNameCheck.isSuspicious("PаyTM"))
        assertTrue(SenderNameCheck.isSuspicious("SBI০1"))
        assertFalse(SenderNameCheck.isSuspicious("रमेश कुमार"))
        assertFalse(SenderNameCheck.isSuspicious("Ramesh रमेश"))
        assertFalse(SenderNameCheck.isSuspicious("محمد"))
        // DLT headers are ASCII: a Cyrillic one is not a registered header, and dotless ı must not upper-case into one.
        assertNull(SenderId.parseDltHeader("VM-НDFCBK-S"))
        assertNull(SenderId.parseDltHeader("VM-ıCıCıB-S"))
        assertEquals(SenderKind.ALPHANUMERIC, SenderId.classify("VM-НDFCBK-S"))
    }

    @Test
    fun `fake credit alerts from look-alike bank headers are likely scams`() {
        val detector = FakeCreditDetector(TemplateBundle.loadDefault())
        val body = "Dear Customer, INR 25,000.00 credited to your HDFC Bank A/c XX1234 on 12-09-26. Avl bal INR 31,200.00"
        for (sender in listOf("НDFCBK", "VM-НDFCBK-S", "ＨＤＦＣＢＫ", "АХІЅВК", "ІСІСІB")) {
            val v = detector.evaluate(sender, body, dateMillis = 1_758_000_000_000L)
            assertEquals(ScamLevel.LIKELY_SCAM, v.level, "$sender -> $v")
            assertTrue(ScamReason.LOOKALIKE_SENDER in v.reasons, "$sender -> $v")
        }
        // Outside India too: a look-alike bank header is spoofed wherever it arrives.
        val abroad = detector.evaluate("НDFCBK", body, region = "GB", dateMillis = 1_758_000_000_000L)
        assertTrue(ScamReason.LOOKALIKE_SENDER in abroad.reasons, abroad.toString())
        // A mixed-script sender that imitates no bank header is still a signal.
        val mixed = detector.evaluate("Ramеsh", "Bhai maine galti se 5000 rupees bhej diye, please wapas kar do", dateMillis = 1_758_000_000_000L)
        assertTrue(ScamReason.MIXED_SCRIPT_SENDER in mixed.reasons, mixed.toString())
        // The genuine header is untouched.
        assertEquals(ScamLevel.NONE, detector.evaluate("VM-HDFCBK-S", body, dateMillis = 1_758_000_000_000L).level)
    }

    @Test
    fun `links from look-alike senders are treated like an unknown number's`() {
        val pipeline = ClassifierPipeline(TemplateBundle.loadDefault(), NaiveBayesModel.loadDefault())
        val spoofed = runBlocking { pipeline.classify("НDFCBK", "Your KYC expires today. Update now: https://hdfc-kyc-update.xyz/k", 1) }
        assertTrue("unknown-sender-link" in spoofed.labels, spoofed.toString())
        assertEquals(Category.SPAM, spoofed.category, spoofed.toString())
        val genuine = runBlocking { pipeline.classify("HDFCBK", "Your statement is ready: https://www.hdfcbank.com/s", 1) }
        assertFalse("unknown-sender-link" in genuine.labels, genuine.toString())
    }
}
