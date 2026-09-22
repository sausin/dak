package app.dak.finance.passbook

import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountLedger
import app.dak.finance.ledger.LedgerEntry
import app.dak.core.model.InstrumentType
import app.dak.finance.money.Money
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class PassbookTest {

    private fun millisFor(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val account = Account(
        id = "HDFC:BANK_ACCOUNT:1234",
        institution = "HDFC Bank",
        instrument = InstrumentType.BANK_ACCOUNT,
        last4 = "1234",
        homeCurrency = "INR",
    )

    @Test
    fun `monthly totals split debits and credits per month and currency`() {
        val entries = listOf(
            LedgerEntry("m1", millisFor(2026, 8, 5), TransactionDirection.DEBIT, Money(50000, "INR"), Money(50000, "INR"), merchant = "AMAZON"),
            LedgerEntry("m2", millisFor(2026, 8, 20), TransactionDirection.CREDIT, Money(200000, "INR"), Money(200000, "INR")),
            LedgerEntry("m3", millisFor(2026, 9, 2), TransactionDirection.DEBIT, Money(30000, "INR"), Money(30000, "INR"), merchant = "SWIGGY"),
        )
        val ledger = AccountLedger(account, entries)
        val totals = Passbook.monthlyTotals(ledger)

        assertEquals(2, totals.size)
        val august = totals.first { it.yearMonth == "2026-08" }
        assertEquals(Money(50000, "INR"), august.debitsByCurrency["INR"])
        assertEquals(Money(200000, "INR"), august.creditsByCurrency["INR"])
        assertEquals(Money(50000, "INR"), august.debitsHome)
        assertEquals(Money(200000, "INR"), august.creditsHome)

        val september = totals.first { it.yearMonth == "2026-09" }
        assertEquals(Money(30000, "INR"), september.debitsHome)
        assertEquals(Money(0, "INR"), september.creditsHome)
    }

    @Test
    fun `foreign currency debits are kept separate by original currency`() {
        val entries = listOf(
            LedgerEntry("m1", millisFor(2026, 8, 5), TransactionDirection.DEBIT, Money(12050, "AED"), Money(1001452, "INR")),
            LedgerEntry("m2", millisFor(2026, 8, 6), TransactionDirection.DEBIT, Money(50000, "INR"), Money(50000, "INR")),
        )
        val ledger = AccountLedger(account, entries)
        val august = Passbook.monthlyTotals(ledger).single()

        assertEquals(Money(12050, "AED"), august.debitsByCurrency["AED"])
        assertEquals(Money(50000, "INR"), august.debitsByCurrency["INR"])
        // Home total includes the AED spend's indicative INR value plus the native INR spend.
        assertEquals(Money(1051452, "INR"), august.debitsHome)
    }

    @Test
    fun `spend by merchant sums home-currency values and buckets missing merchants`() {
        val entries = listOf(
            LedgerEntry("m1", millisFor(2026, 8, 1), TransactionDirection.DEBIT, Money(50000, "INR"), Money(50000, "INR"), merchant = "AMAZON"),
            LedgerEntry("m2", millisFor(2026, 8, 2), TransactionDirection.DEBIT, Money(20000, "INR"), Money(20000, "INR"), merchant = "AMAZON"),
            LedgerEntry("m3", millisFor(2026, 8, 3), TransactionDirection.DEBIT, Money(15000, "INR"), Money(15000, "INR")),
            LedgerEntry("m4", millisFor(2026, 8, 4), TransactionDirection.CREDIT, Money(999900, "INR"), Money(999900, "INR"), merchant = "SALARY"),
        )
        val ledger = AccountLedger(account, entries)
        val byMerchant = Passbook.spendByMerchant(ledger)

        assertEquals(Money(70000, "INR"), byMerchant["AMAZON"])
        assertEquals(Money(15000, "INR"), byMerchant["Unknown"])
        assertEquals(null, byMerchant["SALARY"]) // credits are not spend
        assertEquals(listOf("AMAZON", "Unknown"), byMerchant.keys.toList()) // sorted descending by amount
    }
}
