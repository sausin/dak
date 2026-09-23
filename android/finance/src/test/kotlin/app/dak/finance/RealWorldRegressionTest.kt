package app.dak.finance

import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.BalanceState
import app.dak.finance.ledger.Ledger
import app.dak.finance.ledger.LedgerInput
import app.dak.finance.money.Money
import app.dak.finance.parser.TransactionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Messages users actually reported (names and numbers changed), run end to end: parser -> ledger -> balance. The
 * parser-level checks live next to each fix (MaskedNumberParsingTest, BeneficiaryTransferTest); these make sure the
 * fix also holds where the user saw the bug, in the passbook.
 */
class RealWorldRegressionTest {

    private val auCredit =
        "Credited INR 50,000.00 to A/c X5073 on 06-AUG-2026 Ref IMPS-62181 -ABC XYZ -SBIN. Bal INR 1,00,000.00.\n-AU Bank"
    private val auBeneficiaryNeft =
        "Confirmation! INR 100,000.00 credited to beneficiary A/c XX5632 for your NEFT on 07-Dec-2024 at 08:02 PM. Ref N34224216. - AU BANK"

    /** Commit 003a547: AU Bank writes a single mask character ("A/c X5073"); the account was unknown. */
    @Test
    fun `AU Bank X5073 credit lands on account 5073 with the stated Bal INR balance`() {
        val txn = assertNotNull(TransactionParser.parse("AX-AUBANK-S", auCredit))
        val ledger = Ledger.apply(listOf(LedgerInput("au1", 1_000L, txn))).single()
        assertEquals("AU_SMALL_FINANCE_BANK:BANK_ACCOUNT:5073", ledger.account.id)
        assertEquals("AU Small Finance Bank", ledger.account.institution)
        assertEquals("X5073", ledger.account.maskedNumber)
        assertEquals(BalanceState.Known(Money(10_000_000L, "INR"), 1_000L), ledger.balanceState)
        assertEquals(TransactionDirection.CREDIT, ledger.entries.single().direction)
        assertEquals(Money(5_000_000L, "INR"), ledger.entries.single().original)
    }

    /** Commit 003a547: "Bal INR 1,00,000.00" (no "Avl") is the balance, never the transaction amount. */
    @Test
    fun `Bal INR is read as the balance whichever order the bank writes it in`() {
        val before = assertNotNull(
            TransactionParser.parse("AX-AUBANK-S", "Bal INR 1,00,000.00. Credited INR 50,000.00 to A/c X5073 on 06-AUG-2026."),
        )
        assertEquals(5_000_000L, before.amountMinor)
        assertEquals(10_000_000L, before.balanceMinor)
        val debit = assertNotNull(TransactionParser.parse("AX-AUBANK-S", "Debited INR 2,000.00 from A/c X5073. Bal INR 98,000.00"))
        assertEquals(200_000L, debit.amountMinor)
        assertEquals(9_800_000L, debit.balanceMinor)
        assertEquals("INR", debit.balanceCurrency)
    }

    /**
     * Commit 80b0844: "credited to beneficiary A/c XX5632 for your NEFT" confirms the user's own outgoing transfer. It
     * was read as a credit to a new account 5632, so the passbook grew a phantom account with a Rs 1,00,000 credit.
     */
    @Test
    fun `beneficiary NEFT confirmation never creates a phantom account`() {
        assertNull(TransactionParser.parse("AX-AUBANK-S", auBeneficiaryNeft))
        val inputs = listOf("au1" to auCredit, "au2" to auBeneficiaryNeft).mapNotNull { (key, body) ->
            TransactionParser.parse("AX-AUBANK-S", body)?.let { LedgerInput(key, 1_000L, it) }
        }
        val ledgers = Ledger.apply(inputs)
        assertEquals(listOf("AU_SMALL_FINANCE_BANK:BANK_ACCOUNT:5073"), ledgers.map { it.account.id })
        assertEquals(listOf("au1"), ledgers.single().entries.map { it.messageKey })
    }

    @Test
    fun `beneficiary NEFT confirmation naming the user's account debits that account, not the payee's`() {
        val txn = assertNotNull(
            TransactionParser.parse(
                "AX-AUBANK-S",
                "INR 1,00,000.00 credited to beneficiary A/c XX5632 from your A/c X5073 via NEFT on 07-Dec-2024. Ref N34224216.",
            ),
        )
        val ledger = Ledger.apply(listOf(LedgerInput("n", 1L, txn))).single()
        assertEquals("AU_SMALL_FINANCE_BANK:BANK_ACCOUNT:5073", ledger.account.id)
        assertEquals(TransactionDirection.DEBIT, ledger.entries.single().direction)
        assertEquals(Money(10_000_000L, "INR"), ledger.entries.single().original)
    }
}
