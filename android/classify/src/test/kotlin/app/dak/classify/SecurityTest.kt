package app.dak.classify

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Red-team tests for :classify: every regex that runs on a message body must finish quickly on pathological input
 * (ReDoS harness), and only http(s)/www links are ever extracted, with homographs and hidden hosts flagged.
 */
class SecurityTest {

    /** Inputs that trigger catastrophic backtracking in typical regex mistakes. */
    private val pathological: List<String> = listOf(
        "a".repeat(50_000),
        "a".repeat(50_000) + "!",
        "1".repeat(50_000),
        " ".repeat(50_000) + "x",
        "\t\n ".repeat(20_000),
        "upi ".repeat(12_500),
        "otp ".repeat(12_500),
        "x.".repeat(25_000),
        "a-".repeat(25_000),
        "rs ".repeat(10_000) + "1,".repeat(10_000),
        "1 ".repeat(25_000),
        "card xx ".repeat(6_000),
        "www.".repeat(12_500),
        "https://" + "a".repeat(50_000),
        "@a.".repeat(15_000),
        "Dear ".repeat(10_000),
        "kyc ".repeat(12_500),
        "click here ".repeat(5_000),
        "‮".repeat(20_000) + "https://x.com",
    )

    private fun assertFast(label: String, budgetMs: Long = 1_000, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < budgetMs, "$label took ${ms}ms")
    }

    @Test
    fun `bundled template rules are ReDoS-safe on bounded input`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/app/dak/classify/default-templates.json")).bufferedReader().readText()
        val payload = Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(TemplatePayload.serializer(), text)
        assertTrue(payload.rules.isNotEmpty())
        for (rule in payload.rules) {
            val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
            for (input in pathological) {
                // The pipeline only ever feeds the first MAX_CLASSIFY_CHARS characters to template rules.
                val bounded = input.take(ClassifierPipeline.MAX_CLASSIFY_CHARS)
                assertFast("rule ${rule.id} on ${input.take(12)}…", 500) { regex.containsMatchIn(bounded) }
            }
        }
    }

    @Test
    fun `full pipeline stays fast on hostile bodies of any length`() {
        val pipeline = ClassifierPipeline(templates = TemplateBundle.loadDefault(), model = NaiveBayesModel.loadDefault())
        val huge = "upi ".repeat(250_000) // 1M chars, e.g. an MMS text part
        for (body in pathological + huge) {
            assertFast("pipeline on ${body.take(12)}…", 2_000) {
                runBlocking { pipeline.classify("+919876543210", body, 1) }
                runBlocking { pipeline.classify("VM-HDFCBK", body, 1) }
            }
        }
    }

    @Test
    fun `masker link extractor and otp extractor are fast on hostile bodies`() {
        for (body in pathological) {
            assertFast("Masker on ${body.take(12)}…") { Masker.mask(body) }
            assertFast("LinkExtractor on ${body.take(12)}…") { LinkExtractor.extract(body) }
            assertFast("OtpExtractor on ${body.take(12)}…") { OtpExtractor.extract(body.take(ClassifierPipeline.MAX_CLASSIFY_CHARS)) }
        }
    }

    @Test
    fun `masker handles non-ascii digits instead of throwing`() {
        val masked = Masker.mask("आपका OTP १२३४५६ है, ٣٤٥")
        assertFalse(masked.any { it.isDigit() })
        assertTrue(masked.contains("<NUM>"))
    }

    // --- Links -----------------------------------------------------------------------------------------------

    @Test
    fun `dangerous schemes are never extracted as links`() {
        val body = "javascript:alert(1) intent://scan/#Intent;scheme=zxing;end content://app.dak.dakfiles/x " +
            "file:///sdcard/x tel:+1900 sms:+1900?body=x data:text/html,<script> market://details?id=x"
        assertTrue(LinkExtractor.extract(body).isEmpty(), LinkExtractor.extract(body).toString())
        // A dangerous scheme hidden after an http link is part of that link's path, not its own link.
        val links = LinkExtractor.extract("see https://example.com/?next=javascript:alert(1)")
        assertEquals(listOf("example.com"), links.map { it.host })
    }

    @Test
    fun `bidi overrides and zero width characters end a link`() {
        val link = LinkExtractor.extract("https://evil.example/‮moc.knabcfdh//:sptth").single()
        assertEquals("https://evil.example/", link.raw)
        assertEquals("evil.example", LinkExtractor.extract("https://evil.example​.hdfcbank.com").single().host)
    }

    @Test
    fun `userinfo trick resolves to the real host and is flagged`() {
        val link = LinkExtractor.extract("Verify at https://www.hdfcbank.com@evil.xyz/login").single()
        assertEquals("evil.xyz", link.host)
        assertTrue(link.hasUserInfo)
        val official = LinkExtractor.extract("https://user@hdfcbank.com/").single()
        assertEquals(LinkRisk.OFFICIAL, LookalikeDomainChecker().check(official).risk)
        val cleanUserInfo = LinkExtractor.extract("https://someone@mypersonalblog.com/").single()
        assertEquals(LinkRisk.LOOKALIKE, LookalikeDomainChecker().check(cleanUserInfo).risk)
    }

    @Test
    fun `homograph hosts are extracted and flagged with the imitated brand`() {
        val checker = LookalikeDomainChecker()
        val cyrillic = LinkExtractor.extract("Update KYC: https://hdfcbаnk.com/kyc").single() // Cyrillic 'а'
        assertEquals("hdfcbаnk.com", cyrillic.host)
        assertTrue(cyrillic.isIdn)
        assertTrue(cyrillic.asciiHost!!.startsWith("xn--"))
        val verdict = checker.check(cyrillic)
        assertEquals(LinkRisk.LOOKALIKE, verdict.risk)
        assertEquals("HDFC Bank", verdict.matchedBrand)

        val punycode = LinkExtractor.extract("http://xn--pypal-4ve.com/").single()
        assertTrue(punycode.isIdn)
        assertEquals(LinkRisk.LOOKALIKE, checker.check(punycode).risk)

        val greek = LinkExtractor.extract("https://amazοn.in/offer").single() // Greek omicron
        assertEquals("Amazon", checker.check(greek).matchedBrand)

        val plain = LinkExtractor.extract("https://mypersonalblog.com").single()
        assertFalse(plain.isIdn)
    }

    @Test
    fun `huge bodies are only scanned up to the cap`() {
        val body = "x ".repeat(LinkExtractor.MAX_SCAN_CHARS) + "https://late.example"
        assertTrue(LinkExtractor.extract(body).isEmpty())
    }
}
