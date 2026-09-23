package app.dak.index.enrich

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression: `has:link` detection runs on every indexed body, which the sender controls. */
class LinkDetectorSecurityTest {

    @Test
    fun hostileBodiesNeitherOverflowNorStall() {
        // "a.a.a.…" used to end in a StackOverflowError (one regex recursion per label) inside the indexer.
        val hostile = listOf(
            "a.".repeat(25_000), "a-".repeat(25_000), "a".repeat(50_000), "a.b-".repeat(12_500),
            "x.co".repeat(12_500) + "m", ".".repeat(50_000), "www".repeat(20_000),
        )
        for (body in hostile) {
            val start = System.nanoTime()
            LinkDetector.containsLink(body)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "slow on ${body.take(8)}…")
        }
    }

    @Test
    fun keepsDetectingBareDomains() {
        assertTrue(LinkDetector.containsLink("pay at hdfc-bank.co.in today"))
        assertTrue(LinkDetector.containsLink("Visit EXAMPLE.COM"))
        assertFalse(LinkDetector.containsLink("hello.world"))
        assertFalse(LinkDetector.containsLink("example.community"))
    }
}
