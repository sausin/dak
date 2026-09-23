package app.dak.finance.ledger

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.Money
import app.dak.finance.rates.RatesLoader
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val rates = RatesLoader.loadBundled()

    private fun txn(
        direction: TransactionDirection,
        amountMinor: Long,
        currency: String,
        institution: String? = "HDFC Bank",
        instrument: InstrumentType = InstrumentType.BANK_ACCOUNT,
        last4: String? = "1234",
        balanceMinor: Long? = null,
        balanceCurrency: String? = null,
    ) = ExtractedTransaction(
        direction = direction,
        amountMinor = amountMinor,
        currency = currency,
        instrument = instrument,
        last4 = last4,
        institution = institution,
        balanceMinor = balanceMinor,
        balanceCurrency = balanceCurrency,
    )

    @Test
    fun `groups entries into one account per institution instrument and last4`() {
        val inputs = listOf(
            LedgerInput("m1", 1000L, txn(TransactionDirection.DEBIT, 50000, "INR", last4 = "1234")),
            LedgerInput("m2", 2000L, txn(TransactionDirection.DEBIT, 20000, "INR", last4 = "5678")),
            LedgerInput("m3", 3000L, txn(TransactionDirection.DEBIT, 10000, "INR", institution = "ICICI Bank", last4 = "1234")),
        )
        val ledgers = Ledger.apply(inputs)
        assertEquals(3, ledgers.size)
    }

    @Test
    fun `home currency entry is settled immediately`() {
        val inputs = listOf(LedgerInput("m1", 1000L, txn(TransactionDirection.DEBIT, 50000, "INR")))
        val ledger = Ledger.apply(inputs).single()
        val entry = ledger.entries.single()
        assertTrue(entry.settled)
        assertEquals(Money(50000, "INR"), entry.indicativeHome)
    }

    @Test
    fun `foreign currency entry is unsettled with an indicative home value`() {
        val inputs = listOf(LedgerInput("m1", 1000L, txn(TransactionDirection.DEBIT, 12050, "AED")))
        val ledger = Ledger.apply(inputs, rates = rates).single()
        val entry = ledger.entries.single()
        assertTrue(!entry.settled)
        assertEquals("INR", entry.indicativeHome?.currencyUpper)
        assertTrue(entry.indicativeHome!!.amountMinor > 0)
    }

    @Test
    fun `balance state is known from the latest balance-bearing message`() {
        val inputs = listOf(
            LedgerInput("m1", 1000L, txn(TransactionDirection.DEBIT, 50000, "INR", balanceMinor = 1000000, balanceCurrency = "INR")),
            LedgerInput("m2", 2000L, txn(TransactionDirection.DEBIT, 20000, "INR", balanceMinor = 980000, balanceCurrency = "INR")),
        )
        val ledger = Ledger.apply(inputs).single()
        val state = assertIs<BalanceState.Known>(ledger.balanceState)
        assertEquals(Money(980000, "INR"), state.balance)
        assertEquals(2000L, state.asOfMillis)
    }

    @Test
    fun `balance becomes unknown after a foreign transaction newer than the last balance SMS`() {
        val inputs = listOf(
            LedgerInput("m1", 1000L, txn(TransactionDirection.DEBIT, 50000, "INR", balanceMinor = 1000000, balanceCurrency = "INR")),
            // A foreign-currency spend on the same account (e.g. a debit card used abroad).
            LedgerInput("m2", 2000L, txn(TransactionDirection.DEBIT, 12050, "AED")),
        )
        val ledger = Ledger.apply(inputs, rates = rates).single()
        val state = assertIs<BalanceState.Unknown>(ledger.balanceState)
        assertEquals(2000L, state.sinceMillis)
        assertEquals(Money(1000000, "INR"), state.lastKnown?.balance)
    }

    @Test
    fun `no balance-bearing message means no balance info`() {
        val inputs = listOf(LedgerInput("m1", 1000L, txn(TransactionDirection.DEBIT, 50000, "INR")))
        val ledger = Ledger.apply(inputs).single()
        assertEquals(BalanceState.NoInfo, ledger.balanceState)
    }

    @Test
    fun `credit card outstanding never affects a bank account and is computed by billing cycle`() {
        val cycleDay = 5
        val inputs = listOf(
            LedgerInput(
                "m1",
                epochMillisFor(2026, 8, 10),
                txn(TransactionDirection.DEBIT, 500000, "INR", instrument = InstrumentType.CREDIT_CARD, last4 = "4321"),
            ),
            LedgerInput(
                "m2",
                epochMillisFor(2026, 8, 20),
                txn(TransactionDirection.DEBIT, 300000, "INR", instrument = InstrumentType.CREDIT_CARD, last4 = "4321"),
            ),
            // Payment towards the card reduces outstanding.
            LedgerInput(
                "m3",
                epochMillisFor(2026, 8, 25),
                txn(TransactionDirection.CREDIT, 200000, "INR", instrument = InstrumentType.CREDIT_CARD, last4 = "4321"),
            ),
        )
        val ledger = Ledger.apply(inputs, statementDayFor = { cycleDay }).single()
        assertEquals(AccountType.CREDIT_CARD, ledger.account.type)
        val outstanding = ledger.cardOutstanding(epochMillisFor(2026, 8, 22), BillingCycle(cycleDay))
        assertEquals(Money(600000, "INR"), outstanding) // 5000 + 3000 - 2000 = 6000 INR

        // A bank account built from separate messages must not see the card's activity at all.
        val bankInputs = listOf(LedgerInput("b1", 1000L, txn(TransactionDirection.DEBIT, 10000, "INR", last4 = "9999")))
        val bankLedger = Ledger.apply(bankInputs).single()
        assertNull(bankLedger.cardOutstanding(2000L, BillingCycle(cycleDay)))
    }

    private fun epochMillisFor(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
}
