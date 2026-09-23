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
        // Structural parser: masked numbers, roles, cues, amount roles, gates.
        "A/c XX1234 debited ".repeat(3_000),
        "from your A/c XX1234 to A/c XX5678 ".repeat(2_000),
        "a/c" + " ".repeat(50_000) + "x",
        "card no" + " ".repeat(50_000) + "1",
        "loan" + " ".repeat(50_000) + "a/c",
        "A/c " + ".".repeat(50_000),
        "A/c " + "x".repeat(50_000) + "1",
        "Rs 500 Avl Bal Rs 100 ".repeat(3_000),
        "Rs:" + " ".repeat(50_000) + "1",
        "1 INR" + " ".repeat(50_000) + "2",
        "debited credited refunded will be ".repeat(3_000),
        "credited to beneficiary ".repeat(4_000),
        "sent to your ".repeat(5_000),
        "added to " + "a ".repeat(25_000),
        "to " + "a".repeat(50_000),
        "from " + "a.".repeat(25_000),
        "statement " + "x".repeat(50_000),
        "never share otp ".repeat(4_000),
        "debited by " + "9".repeat(50_000),
        "debited by 1,".repeat(5_000),
        "Dr. Cr. ".repeat(8_000),
        "XX" + "1".repeat(50_000),
        "ending " + "1".repeat(50_000),
        // Investments: folios, units / NAV figures, trade lines, scheme names, security alerts.
        "folio ".repeat(8_000),
        "Folio No. " + "X".repeat(50_000) + "1",
        "units allotted NAV ".repeat(3_000),
        "1.".repeat(25_000) + " units",
        "NAV as on 1-1-1 ".repeat(3_000),
        "shares Bought 1 A @ ".repeat(4_000),
        "Bought 1 " + "A ".repeat(25_000) + "@ 1",
        "A ".repeat(25_000) + "Fund",
        "Fund - A ".repeat(6_000),
        "pledge demat ".repeat(4_000),
        "Client ID " + "x".repeat(50_000),
        "BO ID " + "1".repeat(50_000),
        "current value of your holdings Rs 1 ".repeat(2_000),
        "shares " + "a".repeat(50_000) + " debited demat",
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
