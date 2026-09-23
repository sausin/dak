package app.dak.finance.parser

import kotlin.test.Test
import kotlin.test.assertTrue

/** ReDoS harness: the transaction parser runs on every incoming message body, which the sender controls. */
class ParserRedosTest {

    private val pathological: List<String> = listOf(
        "a".repeat(50_000),
        "1".repeat(50_000),
        " ".repeat(50_000) + "x",
        "debited ".repeat(6_000),
        "debited rs 1 ".repeat(4_000),
        "credited at A".repeat(4_000),
        "to X ".repeat(10_000),
        "at " + "A ".repeat(25_000),
        "card xx".repeat(7_000),
        "xxxx".repeat(12_500),
        "a/c ".repeat(12_500),
        "ref ".repeat(12_500),
        "info: ".repeat(8_000),
        "rs ".repeat(10_000) + "1,".repeat(10_000),
        "₹" + "9,".repeat(25_000),
        "upi ref no ".repeat(4_000),
        "due on ".repeat(7_000),
        "debited " + "१".repeat(40_000),
    )

    @Test
    fun `parser finishes quickly on hostile bodies`() {
        for (body in pathological) {
            val start = System.nanoTime()
            TransactionParser.parse("VM-HDFCBK", body)
            TransactionParser.parseBillReminder("VM-HDFCBK", body)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(ms < 1_500, "parse on '${body.take(16)}…' took ${ms}ms")
        }
    }
}
