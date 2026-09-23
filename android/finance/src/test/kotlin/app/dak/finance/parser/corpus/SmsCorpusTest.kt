package app.dak.finance.parser.corpus

import app.dak.finance.parser.TransactionParser
import kotlin.test.Test
import kotlin.test.fail

/**
 * Runs [SmsCorpus] through [TransactionParser]: one test per category, each reporting every failing message at once.
 */
class SmsCorpusTest {

    private fun run(category: String) {
        val cases = (SmsCorpus.all + SmsCorpusMore.all).getValue(category)
        val failures = ArrayList<String>()
        for (c in cases) {
            val actual = TransactionParser.parse(c.sender, c.body)
            val problems = when {
                c.expect == null && actual == null -> emptyList()
                c.expect == null -> listOf("expected no transaction, got $actual")
                actual == null -> listOf("expected ${c.expect.direction} ${c.expect.amount} ${c.expect.currency}, got no transaction")
                else -> c.expect.mismatches(actual)
            }
            if (problems.isNotEmpty()) failures += "[${c.sender}] ${c.body}\n    " + problems.joinToString("\n    ")
        }
        if (failures.isNotEmpty()) fail("$category: ${failures.size}/${cases.size} failed\n" + failures.joinToString("\n"))
    }

    @Test fun `account debits`() = run("account debits")
    @Test fun `account credits`() = run("account credits")
    @Test fun upi() = run("upi")
    @Test fun `debit cards`() = run("debit cards")
    @Test fun `credit cards`() = run("credit cards")
    @Test fun wallets() = run("wallets")
    @Test fun loans() = run("loans")
    @Test fun `refunds and reversals`() = run("refunds and reversals")
    @Test fun `failed and declined`() = run("failed and declined")
    @Test fun `future, requests and mandates`() = run("future, requests and mandates")
    @Test fun `balances and statements`() = run("balances and statements")
    @Test fun otps() = run("otps")
    @Test fun counterparties() = run("counterparties")
    @Test fun `amount traps`() = run("amount traps")
    @Test fun currencies() = run("currencies")
    @Test fun masks() = run("masks")

    @Test fun `more - debits`() = run("more: debits")
    @Test fun `more - credits`() = run("more: credits")
    @Test fun `more - cards`() = run("more: cards")
    @Test fun `more - wallets and upi`() = run("more: wallets and upi")
    @Test fun `more - not transactions`() = run("more: not transactions")
    @Test fun `more - counterparties`() = run("more: counterparties")
    @Test fun `more - amounts`() = run("more: amounts")
    @Test fun `more - foreign`() = run("more: foreign")

    @Test
    fun `every category is run`() {
        val tested = setOf(
            "account debits", "account credits", "upi", "debit cards", "credit cards", "wallets", "loans",
            "refunds and reversals", "failed and declined", "future, requests and mandates", "balances and statements",
            "otps", "counterparties", "amount traps", "currencies", "masks",
            "more: debits", "more: credits", "more: cards", "more: wallets and upi", "more: not transactions",
            "more: counterparties", "more: amounts", "more: foreign",
        )
        val all = SmsCorpus.all.keys + SmsCorpusMore.all.keys
        if (tested != all) fail("untested categories: ${all - tested}")
    }
}
