package app.dak.finance.ledger

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountAliasesTest {

    @Test
    fun `resolves chains and survives cycles`() {
        val aliases = AccountAliases(mapOf("a" to "b", "b" to "c", "x" to "y", "y" to "x"))
        assertEquals("c", aliases.resolve("a"))
        assertEquals("c", aliases.resolve("c"))
        assertEquals("z", aliases.resolve("z"))
        // A cycle terminates (on some member of it) instead of looping forever.
        assertTrue(aliases.resolve("x") in setOf("x", "y"))
        assertTrue(aliases.isAlias("a"))
        assertFalse(aliases.isAlias("c"))
        assertEquals(setOf("a", "b", "c"), aliases.membersOf("c"))
    }

    @Test
    fun `canonical is the id showing more digits`() {
        assertEquals("HDFC_BANK:BANK_ACCOUNT:440065", AccountAliases.canonicalOf("HDFC_BANK:BANK_ACCOUNT:40065", "HDFC_BANK:BANK_ACCOUNT:440065"))
        assertEquals("P:UPI:1234", AccountAliases.canonicalOf("P:UPI:1234", "P:BANK_ACCOUNT:1234") { if (it.contains("UPI")) 1L else 2L })
    }

    private fun txn(masked: String, amount: Long, balance: Long? = null) = ExtractedTransaction(
        direction = TransactionDirection.DEBIT,
        amountMinor = amount,
        currency = "INR",
        instrument = InstrumentType.BANK_ACCOUNT,
        last4 = masked.filter { it.isDigit() }.takeLast(4),
        institution = "HDFC Bank",
        balanceMinor = balance,
        balanceCurrency = balance?.let { "INR" },
        maskedNumber = masked,
    )

    @Test
    fun `ledger posts alias entries to the canonical account`() {
        val inputs = listOf(
            LedgerInput("m1", 1000L, txn("XX440065", 100, balance = 10_000)),
            LedgerInput("m2", 2000L, txn("XX40065", 200, balance = 9_800)),
        )
        val separate = Ledger.apply(inputs)
        assertEquals(2, separate.size)

        val canonical = "HDFC_BANK:BANK_ACCOUNT:440065"
        val merged = Ledger.apply(inputs, aliases = AccountAliases(mapOf("HDFC_BANK:BANK_ACCOUNT:40065" to canonical)))
        val ledger = merged.single()
        assertEquals(canonical, ledger.account.id)
        assertEquals("XX440065", ledger.account.maskedNumber)
        assertEquals("440065", ledger.account.visibleDigits)
        assertEquals(listOf("m1", "m2"), ledger.entries.map { it.messageKey })
        assertEquals(BalanceState.Known(app.dak.finance.money.Money(9_800, "INR"), 2000L), ledger.balanceState)
    }
}
