package app.dak.finance.parser.corpus

import app.dak.core.model.InstrumentType
import app.dak.finance.parser.TransactionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs [InvestmentCorpus] through [TransactionParser]: one test per category, each reporting every failing message. */
class InvestmentCorpusTest {

    private fun run(category: String) {
        val cases = InvestmentCorpus.all.getValue(category)
        val failures = ArrayList<String>()
        for (c in cases) {
            val actual = TransactionParser.parse(c.sender, c.body)
            val problems = when {
                c.expect == null && actual == null -> emptyList()
                c.expect == null -> listOf("expected no transaction, got $actual")
                actual == null -> listOf("expected ${c.expect.direction} ${c.expect.amount} ${c.expect.instrument}, got no transaction")
                else -> c.expect.mismatches(actual)
            }
            if (problems.isNotEmpty()) failures += "[${c.sender}] ${c.body}\n    " + problems.joinToString("\n    ")
        }
        if (failures.isNotEmpty()) fail("$category: ${failures.size}/${cases.size} failed\n" + failures.joinToString("\n"))
    }

    @Test fun `fund purchases`() = run("fund purchases")
    @Test fun `fund redemptions and switches`() = run("fund redemptions and switches")
    @Test fun `fund income`() = run("fund income")
    @Test fun valuations() = run("valuations")
    @Test fun trades() = run("trades")
    @Test fun `bank side`() = run("bank side")
    @Test fun `not transactions`() = run("not transactions")

    @Test
    fun `every category is run`() {
        assertEquals(
            setOf("fund purchases", "fund redemptions and switches", "fund income", "valuations", "trades", "bank side", "not transactions"),
            InvestmentCorpus.all.keys,
        )
    }

    @Test
    fun `ordinary messages that share investment words never become investments`() {
        val investment = setOf(InstrumentType.MUTUAL_FUND, InstrumentType.DEMAT)
        val bodies = listOf(
            "VM-QMART" to "You bought 2 items at Rs 499 each from QuickMart. Rs 998 debited from A/c XX1234. Order #12345",
            "VM-NOVABK" to "You have redeemed 500 reward points worth Rs 125 on your Credit Card XX9876.",
            "VM-POWERC" to "Your electricity bill for 250 units is Rs 1,540. Rs 1,540 debited from A/c XX1234 via auto-pay.",
            "VM-NOVABK" to "Lien of Rs 50,000 marked on your FD A/c XX5566 against your overdraft.",
            "VM-GOLDLN" to "Rs 60,000 disbursed to A/c XX1234 against pledge of gold ornaments. Loan A/c XX7788.",
            "VM-RAILWY" to "Seat allotted: Coach B2 Berth 34. PNR 1234567890. Fare Rs 1,250 paid.",
            "VM-NOVABK" to "Rs 5,00,000 disbursed to your A/c XX1234 against pledge of shares held in your demat account. Loan A/c XX7788.",
        )
        for ((sender, body) in bodies) {
            val txn = TransactionParser.parse(sender, body)
            assertTrue(txn?.instrument !in investment, "$body -> $txn")
        }
        // A loan against pledged shares is still the bank's credit, not a (dropped) pledge alert.
        val disbursal = TransactionParser.parse("VM-NOVABK", bodies.last().second)
        assertTrue(disbursal != null && disbursal.amountMinor == 50_000_000L, "$disbursal")
        // Nor does any message of the bank corpus.
        for (c in (SmsCorpus.all + SmsCorpusMore.all).values.flatten()) {
            val txn = TransactionParser.parse(c.sender, c.body)
            assertTrue(txn?.instrument !in investment, "${c.body} -> $txn")
        }
    }

    @Test
    fun `the institution is the sender header`() {
        val txn = TransactionParser.parse("VM-PEAKMF", InvestmentCorpus.fundPurchases.first().body)
        assertEquals("PEAKMF", txn?.institution)
    }
}
