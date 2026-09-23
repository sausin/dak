package app.dak.classify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression: `has:link` detection runs on every indexed body, which the sender controls. */
class LinkPresenceTest {

    @Test
    fun hostileBodiesNeitherOverflowNorStall() {
        // "a.a.a.…" used to end in a StackOverflowError (one regex recursion per label) inside the indexer.
        val hostile = listOf(
            "a.".repeat(25_000), "a-".repeat(25_000), "a".repeat(50_000), "a.b-".repeat(12_500),
            "x.co".repeat(12_500) + "m", ".".repeat(50_000), "www".repeat(20_000),
        )
        for (body in hostile) {
            val start = System.nanoTime()
            LinkPresence.containsLink(body)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "slow on ${body.take(8)}…")
        }
    }

    @Test
    fun keepsDetectingBareDomains() {
        assertTrue(LinkPresence.containsLink("pay at hdfc-bank.co.in today"))
        assertTrue(LinkPresence.containsLink("Visit EXAMPLE.COM"))
        assertFalse(LinkPresence.containsLink("hello.world"))
        assertFalse(LinkPresence.containsLink("example.community"))
    }
}
