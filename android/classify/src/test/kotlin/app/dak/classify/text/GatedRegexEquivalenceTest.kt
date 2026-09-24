package app.dak.classify.text

import app.dak.classify.LinkExtractor
import app.dak.classify.LinkPresence
import app.dak.classify.OtpExtractor
import app.dak.classify.TemplateBundle
import app.dak.classify.bench.SyntheticCorpus
import app.dak.classify.scam.BankNames
import app.dak.classify.scam.FakeCreditDetector
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every production regex that now sits behind a keyword gate ([GatedRegex] / [KeywordPrefilter]) finds exactly what
 * the bare regex finds, over the benchmark corpus plus adversarial variants (case tricks, Kelvin sign / long s,
 * Devanagari, keywords split across words).
 */
class GatedRegexEquivalenceTest {

    private val texts: List<String> = run {
        val r = Random(5)
        // 12k synthetic bodies plus variants keep the classify suite under a minute; the hand-written tricky cases
        // below carry the adversarial weight.
        val corpus = SyntheticCorpus.generate(12_000, seed = 99).map { it.body }
        val tricky = listOf(
            "KYC update", "Your OTP is 1234", "ſPENT Rs 500", "रु 500 जमा", "₹1,00,000 credited to a/c XX1234",
            "WWW.EXAMPLE.COM", "HTTPS://bit.ly/x", "upi://pay?pa=a@b", "wapas kar do 5000", "sent by mistake, please return",
            "enter UPI PIN to receive Rs 500", "tap here to accept the request", "@site.com #123456", "Code: AB12CD", "",
            // Scheme-less links, promotions dressed as alerts, long-window and "use X as" OTPs, numeric headers.
            "pay customs fee at bit.ly/3xYzAb", "click fb.xbees.in/ffb63", "Rs 500 cashback credited* T&C apply", "Order now! 50% off",
            "OTP for txn of Rs 2,499.00 at SHOP on card XX4411 is 773201", "Use 5521 as your one time password", "USE 5521 AS YOUR OTP",
            "Your KYC will be blocked, update at kyc-update.info/pan", "Bijli connection aaj raat kaat diya jayega 9876500097",
            "Part time job: earn Rs 3000 daily", "Invest Rs 10,000 and get Rs 50,000 in 7 days", "आपका ऑर्डर डिलीवर कर दिया गया",
        )
        corpus + tricky + List(3_000) { corpus[r.nextInt(corpus.size)].let { t -> if (r.nextBoolean()) t.uppercase() else t.replace(" ", "") } }
    }

    private fun assertSame(gated: GatedRegex) {
        for (t in texts) {
            val expected = gated.regex.findAll(t).map { it.range }.toList()
            assertEquals(expected, gated.findAll(t).map { it.range }.toList(), "${gated.regex.pattern} on '$t'")
            assertEquals(expected.isNotEmpty(), gated.containsMatchIn(t))
        }
    }

    @Test
    fun detectorPatternsAreUnchanged() {
        val patterns = FakeCreditDetector.allPatterns
        assertTrue(patterns.count { it.literals != null } >= 12, "most detector patterns should be gated")
        patterns.forEach(::assertSame)
    }

    @Test
    fun otpAndLinkPatternsAreUnchanged() {
        (OtpExtractor.gatedPatterns + LinkExtractor.urlRegex + LinkPresence.schemeOrWww).forEach(::assertSame)
    }

    @Test
    fun keywordPrefiltersNeverHideAMatch() {
        val sets = listOf(
            TemplateBundle.loadDefault().rules.map { it.pattern },
            BankNames.families.map { it.regex.pattern },
        )
        for (patterns in sets) {
            val prefilter = KeywordPrefilter(patterns)
            val regexes = patterns.map { Regex(it, RegexOption.IGNORE_CASE) }
            for (t in texts) {
                val hits = prefilter.scan(t)
                regexes.forEachIndexed { i, regex ->
                    if (!prefilter.mayMatch(i, hits)) assertTrue(!regex.containsMatchIn(t), "${patterns[i]} hidden on '$t'")
                }
            }
        }
    }
}
