package app.dak.classify

import app.dak.classify.entities.EntityExtractor
import app.dak.classify.entities.EntityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scheme-less links (`bit.ly/x`, `fb.example.in/ffb63`) and the text that must never be mistaken for one. */
class SchemelessLinkTest {

    private fun raws(body: String) = LinkExtractor.extract(body).map { it.raw }

    @Test
    fun `finds scheme-less links with and without a path`() {
        val cases = mapOf(
            "Pay customs fee at bit.ly/3xYzAb" to listOf("bit.ly/3xYzAb"),
            "For feedback pls click fb.xbees.in/ffb63 Xpressbees" to listOf("fb.xbees.in/ffb63"),
            "Please Rate our Service on acl.cc/BLUDRT/lqZvGvJb." to listOf("acl.cc/BLUDRT/lqZvGvJb"),
            "Track: swft.sh/t/88776655 or call us" to emptyList(), // .sh is not a known TLD: stays text
            "Visit amazon.in today" to listOf("amazon.in"),
            "Visit EXAMPLE.COM" to listOf("EXAMPLE.COM"),
            "Update here kyc-update.info/pan now" to listOf("kyc-update.info/pan"),
            "WhatsApp HR: wa.me/919876500096" to listOf("wa.me/919876500096"),
            "Join t.me/earnfast now" to listOf("t.me/earnfast"),
            "(see shk.it/sale)" to listOf("shk.it/sale"),
            "Link:grocit.co/t/abc12, thanks" to listOf("grocit.co/t/abc12"),
            "hdfc-bank.co.in" to listOf("hdfc-bank.co.in"),
            "go to pay.example.me" to listOf("pay.example.me"),
            "localhost.example.com:8080/x" to listOf("localhost.example.com:8080/x"),
            "Two links: a1.in/x and https://b.example/y" to listOf("a1.in/x", "https://b.example/y"),
        )
        for ((body, expected) in cases) assertEquals(expected, raws(body), body)
    }

    @Test
    fun `numbers abbreviations sentences and e-mail addresses are not links`() {
        val bodies = listOf(
            "Rs.500 debited from A/c.No.XX1234", "INR.1,000.00 credited", "Rs.500/- paid", "Avl.Bal.Rs.2,000",
            "Balance 1.5GB left", "Meet at 10.30am", "v1.2.3 released", "Dt.12.09.2026", "Pvt.Ltd", "B.Com final year",
            "M.Com exam", "e.g. this", "i.e. that", "Thank you.In case of queries call us", "Done.Com se aaya",
            "Order.ID 12345", "Contact.us today", "ho.ga kal", "500.ml bottle", "ok.so what", "Mail me at name@mail.com",
            "john.doe@example.in wrote", "send to ravi.k@okaxis", "report.pdf attached", "photo.jpg", "a..com",
            "-abc.com-", "hello.world", "example.community", "M/s.ABC.Co", "wait...in 5 mins", "S.No.1",
        )
        for (body in bodies) {
            assertEquals(emptyList(), raws(body), body)
            assertFalse(LinkPresence.containsLink(body), body)
        }
    }

    @Test
    fun `scheme-less links open over https and keep their host`() {
        val link = LinkExtractor.extract("click fb.xbees.in/ffb63").single()
        assertEquals("xbees.in".let { "fb.$it" }, link.host)
        assertEquals("https://fb.xbees.in/ffb63", link.url)
        assertFalse(link.hasScheme)
        assertEquals("http://x.example/a", LinkExtractor.extract("go http://x.example/a").single().url)
        assertEquals("https://www.example.com", LinkExtractor.extract("go www.example.com").single().url)
    }

    @Test
    fun `link safety sees scheme-less links`() {
        val checker = LookalikeDomainChecker()
        assertEquals(LinkRisk.SHORTENED, checker.check(LinkExtractor.extract("pay at bit.ly/3xYzAb").single()).risk)
        assertEquals(LinkRisk.SUSPICIOUS_TLD, checker.check(LinkExtractor.extract("go secure-kyc.xyz/login").single()).risk)
        assertEquals(LinkRisk.LOOKALIKE, checker.check(LinkExtractor.extract("go hdfc-bank-kyc.top/x").single()).risk)
        assertEquals(LinkRisk.OFFICIAL, checker.check(LinkExtractor.extract("see hdfcbank.com/kyc").single()).risk)
    }

    @Test
    fun `links inside scheme links and e-mail domains are not found twice`() {
        assertEquals(listOf("https://a.example/b.in/c.com"), raws("https://a.example/b.in/c.com"))
        assertEquals(listOf("https://evil.example"), raws("https://evil.example​.hdfcbank.com"))
        assertEquals(emptyList(), raws("javascript:alert(1) content://app.dak.dakfiles/x market://details?id=x"))
    }

    @Test
    fun `entities mark scheme-less links as urls and e-mails as e-mails`() {
        val body = "Track fb.xbees.in/ffb63 or mail care@xbees.in"
        val spans = EntityExtractor.extract(body, "IN")
        assertEquals(listOf("fb.xbees.in/ffb63"), spans.filter { it.type == EntityType.URL }.map { it.text })
        assertEquals(listOf("care@xbees.in"), spans.filter { it.type == EntityType.EMAIL }.map { it.text })
        // A wa.me link is one URL, not a phone number.
        val wa = EntityExtractor.extract("HR: wa.me/919876500096", "IN")
        assertTrue(wa.any { it.type == EntityType.URL } && wa.none { it.type == EntityType.PHONE }, wa.toString())
    }

    @Test
    fun `scan is linear on hostile input`() {
        val hostile = listOf(
            "a.".repeat(25_000), "a.com ".repeat(8_000), "x.co".repeat(12_500) + "m", "a-".repeat(25_000),
            "bit.ly/" + "a".repeat(50_000), "a@b.com ".repeat(6_000), ".in".repeat(15_000),
        )
        for (body in hostile) {
            val start = System.nanoTime()
            LinkExtractor.extract(body)
            LinkPresence.containsLink(body)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "slow on ${body.take(8)}…")
        }
    }
}
