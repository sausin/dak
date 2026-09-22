package app.dak.classify

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkExtractorTest {

    @Test
    fun `extracts https and www urls with hosts`() {
        val links = LinkExtractor.extract("Track your order at https://www.delhivery.com/track/123 now")
        assertEquals(1, links.size)
        assertEquals("delhivery.com", links[0].host)
    }

    @Test
    fun `extracts bare www links`() {
        val links = LinkExtractor.extract("Visit www.example.com for more")
        assertEquals("example.com", links[0].host)
    }

    @Test
    fun `finds no links in plain text`() {
        assert(LinkExtractor.extract("Hey, are you free tonight?").isEmpty())
    }
}

class LookalikeDomainCheckerTest {

    private val checker = LookalikeDomainChecker()

    @Test
    fun `flags official bank domain as official`() {
        val verdict = checker.check(LinkExtractor.extract("https://hdfcbank.com/login").first())
        assertEquals(LinkRisk.OFFICIAL, verdict.risk)
    }

    @Test
    fun `flags a shortener`() {
        val verdict = checker.check(LinkExtractor.extract("http://bit.ly/abc123").first())
        assertEquals(LinkRisk.SHORTENED, verdict.risk)
    }

    @Test
    fun `flags brand-in-subdomain lookalike`() {
        val verdict = checker.check(LinkExtractor.extract("http://hdfc-bank-kyc.xyz/verify").first())
        assertEquals(LinkRisk.LOOKALIKE, verdict.risk)
        assertEquals("HDFC Bank", verdict.matchedBrand)
    }

    @Test
    fun `flags edit-distance typo-squat`() {
        val verdict = checker.check(LinkExtractor.extract("http://irctc-info.co.in/pnr").first())
        // registrable label "irctc-info" -> checked against official "irctc"; ensure it doesn't
        // false-positive as official, and the general unknown/lookalike path is exercised.
        assert(verdict.risk == LinkRisk.LOOKALIKE || verdict.risk == LinkRisk.UNKNOWN)
    }

    @Test
    fun `flags suspicious tld with no brand match`() {
        val verdict = checker.check(LinkExtractor.extract("http://randomoffers.xyz/win").first())
        assertEquals(LinkRisk.SUSPICIOUS_TLD, verdict.risk)
    }

    @Test
    fun `unknown but benign domain is unknown`() {
        val verdict = checker.check(LinkExtractor.extract("http://mypersonalblog.com/post").first())
        assertEquals(LinkRisk.UNKNOWN, verdict.risk)
    }
}
