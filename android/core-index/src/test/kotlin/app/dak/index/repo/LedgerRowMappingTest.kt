package app.dak.index.repo

import app.dak.core.model.InstrumentType
import app.dak.core.model.InvestmentAction
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountLedger
import app.dak.finance.ledger.BalanceState
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.money.Money
import app.dak.index.db.entity.AccountRow
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [LedgerRowMapping]: the ledger is rebuilt from messages, but the Passbook reads it back from these rows, so every
 * field must survive the model -> row -> model round trip (a dropped `transfer` flag would count an own-account
 * transfer as spending).
 */
class LedgerRowMappingTest {

    private val account = Account(
        id = "HDFC|BANK_ACCOUNT|1234",
        institution = "HDFC",
        instrument = InstrumentType.BANK_ACCOUNT,
        last4 = "1234",
        homeCurrency = "INR",
        statementDay = 12,
        maskedNumber = "XX401234",
        linkedAccountId = "HDFC|BANK_ACCOUNT|9999",
    )

    private val full = LedgerEntry(
        messageKey = "sms:1",
        dateMillis = 1_000,
        direction = TransactionDirection.DEBIT,
        original = Money(10_000, "USD"),
        indicativeHome = Money(831_250, "INR"),
        rate = BigDecimal("83.1250"),
        rateDateMillis = 900,
        settled = false,
        effectiveMarkupPercent = BigDecimal("-1.50"),
        balanceAfter = Money(5_000_000, "INR"),
        merchant = "AMAZON",
        reference = "UTR123",
        viaAccountId = "HDFC|DEBIT_CARD|5678",
        transfer = true,
        investmentAction = InvestmentAction.PURCHASE,
        units = "12.345",
        unitPrice = "81.01",
    )

    @Test
    fun everyPersistedEntryFieldRoundTrips() {
        val row = LedgerRowMapping.toEntryRow(account.id, full)
        assertEquals(account.id, row.accountId)
        assertEquals(full, LedgerRowMapping.toEntry(row))
        val minimal = LedgerEntry("mms:2", 5, TransactionDirection.CREDIT, Money(1, "INR"))
        assertEquals(minimal, LedgerRowMapping.toEntry(LedgerRowMapping.toEntryRow(account.id, minimal)))
    }

    @Test
    fun transferFlagIsNeverLostSoTransfersNeverCountAsSpend() {
        for (transfer in listOf(true, false)) {
            val entry = full.copy(transfer = transfer)
            assertEquals(transfer, LedgerRowMapping.toEntry(LedgerRowMapping.toEntryRow(account.id, entry)).transfer)
        }
    }

    @Test
    fun unitsHeldIsAnAccountFactNotAnEntryField() {
        // Documented on LedgerEntry.unitsHeld: the entry copy is not stored, the account row keeps the latest.
        val entry = full.copy(unitsHeld = "100.5")
        assertNull(LedgerRowMapping.toEntry(LedgerRowMapping.toEntryRow(account.id, entry)).unitsHeld)
        val row = LedgerRowMapping.toAccountRow(AccountLedger(account, listOf(entry)), statementDay = null, nowMillis = 0)
        assertEquals("100.5", row.unitsHeld)
    }

    @Test
    fun unreadableStoredValuesDegradeInsteadOfFailingTheRead() {
        val row = LedgerRowMapping.toEntryRow(account.id, full).copy(
            rate = "not a number",
            markupPercent = "",
            investmentAction = "FUTURE_ACTION",
            indicativeCurrency = null,
            balanceAfterMinor = null,
        )
        val entry = LedgerRowMapping.toEntry(row)
        assertNull(entry.rate)
        assertNull(entry.effectiveMarkupPercent)
        assertNull(entry.investmentAction, "an action written by a newer version reads as none")
        assertNull(entry.indicativeHome, "half a money value is no value")
        assertNull(entry.balanceAfter)
        assertEquals(full.original, entry.original)
    }

    @Test
    fun accountRowRoundTripsTheAccountAndItsKnownBalance() {
        val settled = full.copy(settled = true, dateMillis = 2_000)
        val ledger = AccountLedger(account, listOf(full.copy(balanceAfter = null), settled))
        val row = LedgerRowMapping.toAccountRow(ledger, statementDay = account.statementDay, nowMillis = 7_000)
        assertEquals(account, LedgerRowMapping.toAccount(row))
        assertEquals(LedgerRowMapping.STATE_KNOWN, row.balanceState)
        assertEquals(BalanceState.Known(Money(5_000_000, "INR"), 2_000), LedgerRowMapping.balanceOf(row))
        assertEquals(2, row.entryCount)
        assertEquals(2_000L, row.lastActivityMillis)
        assertEquals(7_000L, row.updatedAt)
    }

    @Test
    fun anUnsettledEntryAfterTheLastBalanceMakesItUnknownButKeepsTheLastKnown() {
        val withBalance = full.copy(settled = true, dateMillis = 1_000)
        val foreign = full.copy(messageKey = "sms:2", settled = false, dateMillis = 3_000, balanceAfter = null)
        val row = LedgerRowMapping.toAccountRow(AccountLedger(account, listOf(withBalance, foreign)), null, 0)
        assertEquals(LedgerRowMapping.STATE_UNKNOWN, row.balanceState)
        assertEquals(
            BalanceState.Unknown(3_000, BalanceState.Known(Money(5_000_000, "INR"), 1_000)),
            LedgerRowMapping.balanceOf(row),
        )
    }

    @Test
    fun theUsersStatementDayWinsOverTheRebuiltAccount() {
        // Recompute passes the stored (user-set) statement day; the rebuilt Account never knows it.
        val row = LedgerRowMapping.toAccountRow(AccountLedger(account.copy(statementDay = null), listOf(full)), statementDay = 25, nowMillis = 0)
        assertEquals(25, row.statementDay)
    }

    @Test
    fun inconsistentBalanceColumnsReadAsNoInfoOrUnknownWithoutAValue() {
        val base = AccountRow(
            id = "x", institution = "X", instrument = InstrumentType.BANK_ACCOUNT, last4 = null, homeCurrency = "INR",
            statementDay = null, balanceState = LedgerRowMapping.STATE_KNOWN, balanceMinor = 100, balanceCurrency = null,
            balanceAsOfMillis = 5, unknownSinceMillis = null, entryCount = 0, lastActivityMillis = 0, updatedAt = 0,
        )
        assertEquals(BalanceState.NoInfo, LedgerRowMapping.balanceOf(base), "KNOWN without a currency")
        assertEquals(BalanceState.NoInfo, LedgerRowMapping.balanceOf(base.copy(balanceState = "SOMETHING_NEW")))
        assertEquals(BalanceState.Unknown(0, null), LedgerRowMapping.balanceOf(base.copy(balanceState = LedgerRowMapping.STATE_UNKNOWN)))
        assertEquals(
            BalanceState.Known(Money(100, "INR"), 5),
            LedgerRowMapping.balanceOf(base.copy(balanceCurrency = "INR")),
        )
    }

    @Test
    fun anAccountWithoutBalanceMessagesHasNoInfo() {
        val row = LedgerRowMapping.toAccountRow(AccountLedger(account, listOf(full.copy(balanceAfter = null))), null, 0)
        assertEquals(LedgerRowMapping.STATE_NO_INFO, row.balanceState)
        assertNull(row.balanceMinor)
        assertEquals(BalanceState.NoInfo, LedgerRowMapping.balanceOf(row))
    }
}
