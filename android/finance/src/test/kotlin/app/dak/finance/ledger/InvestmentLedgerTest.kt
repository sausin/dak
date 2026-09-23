package app.dak.finance.ledger

import app.dak.core.model.InstrumentType
import app.dak.core.model.InvestmentAction
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.Money
import app.dak.finance.parser.TransactionParser
import app.dak.finance.passbook.AccountFacts
import app.dak.finance.passbook.AccountGroups
import app.dak.finance.passbook.Passbook
import app.dak.finance.passbook.TotalKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Investments in the ledger: a SIP is two sides of one own-account transfer (the bank's debit and the fund's
 * allotment), never spending; valuations become the investment account's balance; dividends are income.
 */
class InvestmentLedgerTest {

    private fun input(key: String, date: Long, sender: String, body: String) =
        LedgerInput(key, date, assertNotNull(TransactionParser.parse(sender, body), body))

    private val inr: (String?) -> String? = { "INR" }

    private val sipDebit = "Rs.5,000.00 debited from A/c XX4321 on 05-09-26 towards ACH D- PEAK MF SIP. Avl Bal Rs.45,000.00"
    private val allotment = "Dear Investor, your SIP instalment of Rs.5,000.00 in Peak Flexi Cap Fund - Direct Growth, Folio No. " +
        "XXXX1234 has been processed. Units allotted: 45.678 at NAV Rs.109.4563 on 05-Sep-2026."
    private val grocery = "Rs 640.00 debited from A/c XX4321 on 06-09-2026 at FRESH MART. Avl Bal Rs 44,360.00"
    private val valuation = "Dear Investor, the current value of your investments in Folio XXXX1234 as on 19-Sep-2026 is " +
        "Rs 1,23,456.78. Units held: 1,127.890."
    private val idcw = "IDCW of Rs 1,250.00 declared under Peak Equity Income Fund for folio XXXX1234 has been paid to your bank " +
        "a/c XX4321 on 20-Sep-2026."

    private fun ledgers() = Ledger.apply(
        listOf(
            input("sip-bank", 1_000, "VM-NOVABK", sipDebit),
            input("sip-fund", 2_000, "VM-PEAKMF", allotment),
            input("grocery", 3_000, "VM-NOVABK", grocery),
            input("value", 4_000, "VM-PEAKMF", valuation),
            input("idcw", 5_000, "VM-PEAKMF", idcw),
        ),
        defaultHomeCurrency = inr,
    ).associateBy { it.account.id }

    @Test
    fun `a SIP is a transfer on both sides and never spending`() {
        val all = ledgers()
        val bank = assertNotNull(all["NOVABK:BANK_ACCOUNT:4321"])
        val fund = assertNotNull(all["PEAKMF:MUTUAL_FUND:1234"])
        assertEquals(2, all.size)

        // The bank's balance moves with the SIP debit, but the debit is a transfer.
        val bankSip = bank.entries.single { it.messageKey == "sip-bank" }
        assertTrue(bankSip.transfer)
        assertEquals(TransactionDirection.DEBIT, bankSip.direction)
        assertFalse(bank.entries.single { it.messageKey == "grocery" }.transfer)

        // The fund records the same money as invested (a credit), with units and NAV.
        val fundSip = fund.entries.single { it.messageKey == "sip-fund" }
        assertEquals(TransactionDirection.CREDIT, fundSip.direction)
        assertEquals(Money(500000, "INR"), fundSip.original)
        assertTrue(fundSip.transfer)
        assertEquals(InvestmentAction.PURCHASE, fundSip.investmentAction)
        assertEquals("45.678", fundSip.units)
        assertEquals("109.4563", fundSip.unitPrice)

        // Spend this month: only the grocery debit.
        assertEquals(listOf(Money(64000, "INR")), AccountGroups.spentSince(bank.entries, 0))
        assertEquals(emptyList(), AccountGroups.spentSince(fund.entries, 0))

        val month = Passbook.monthlyTotals(bank).single()
        assertEquals(Money(64000, "INR"), month.debitsHome)
        assertEquals(Money(500000, "INR"), month.transfersOutHome)
        assertEquals(mapOf("INR" to Money(64000, "INR")), month.debitsByCurrency)
        assertEquals(listOf("FRESH MART"), Passbook.spendByMerchant(bank).keys.toList())
    }

    @Test
    fun `a valuation is the fund's balance and a dividend is income`() {
        val fund = assertNotNull(ledgers()["PEAKMF:MUTUAL_FUND:1234"])
        assertEquals(AccountType.INVESTMENT, fund.account.type)
        assertEquals("XXXX1234", fund.account.maskedNumber)
        val known = assertIs<BalanceState.Known>(fund.balanceState)
        assertEquals(Money(12345678, "INR"), known.balance)
        assertEquals(4_000, known.asOfMillis)
        assertEquals("1127.89", fund.unitsHeld)

        val value = fund.entries.single { it.messageKey == "value" }
        assertTrue(value.isValuation)
        assertEquals(0, value.original.amountMinor)

        val dividend = fund.entries.single { it.messageKey == "idcw" }
        assertFalse(dividend.transfer)
        assertEquals(InvestmentAction.DIVIDEND, dividend.investmentAction)

        val month = Passbook.monthlyTotals(fund).single()
        assertEquals(Money(500000, "INR"), month.transfersInHome, "invested")
        assertEquals(Money(125000, "INR"), month.creditsHome, "dividends received")
        assertEquals(Money(0, "INR"), month.debitsHome)
    }

    @Test
    fun `a redemption and a switch are not spending either`() {
        val ledger = Ledger.apply(
            listOf(
                input(
                    "r", 1_000, "BZ-ORBITM",
                    "Redemption of 50.000 units from Orbit Liquid Fund, Folio 12345678/90 processed at NAV Rs 2,450.5000. " +
                        "Amount of Rs 1,22,525.00 will be credited to your bank a/c XX4321 in 1-2 working days.",
                ),
                input(
                    "s", 2_000, "BZ-ORBITM",
                    "Switch of 100.000 units from Orbit Liquid Fund to Orbit Gilt Fund in folio 12345678 processed. Switch amount Rs 25,000.00.",
                ),
            ),
            defaultHomeCurrency = inr,
        ).single()
        assertEquals("ORBITM:MUTUAL_FUND:5678", ledger.account.id)
        assertEquals(emptyList(), AccountGroups.spentSince(ledger.entries, 0))
        val month = Passbook.monthlyTotals(ledger).single()
        assertEquals(Money(12252500, "INR"), month.transfersOutHome, "redeemed")
        assertEquals(Money(0, "INR"), month.transfersInHome, "a switch moves no money in or out")
        assertEquals(Money(0, "INR"), month.debitsHome)
    }

    @Test
    fun `an account the user marks as a fund never counts as spending`() {
        val body = "Rs 2,000.00 debited from A/c XX7777 on 05-09-2026 at PEAK ASSET MGMT. Avl Bal Rs 8,000.00"
        val detected = Ledger.apply(listOf(input("m", 1_000, "VM-NOVABK", body)), defaultHomeCurrency = inr).single()
        assertEquals(listOf(Money(200000, "INR")), AccountGroups.spentSince(detected.entries, 0))
        val marked = Ledger.apply(
            listOf(input("m", 1_000, "VM-NOVABK", body)),
            defaultHomeCurrency = inr,
            instrumentOverride = { InstrumentType.MUTUAL_FUND },
        ).single()
        assertEquals(AccountType.INVESTMENT, marked.account.type)
        assertTrue(marked.entries.single().transfer)
        assertEquals(emptyList(), AccountGroups.spentSince(marked.entries, 0))
    }

    @Test
    fun `investments are one passbook section after loans, totalling current values`() {
        fun account(id: String, instrument: InstrumentType) =
            Account(id = id, institution = "X", instrument = instrument, last4 = "1234", homeCurrency = "INR")
        val items = listOf(
            AccountFacts(account("mf", InstrumentType.MUTUAL_FUND), BalanceState.Known(Money(1_000_00, "INR"), 1)),
            AccountFacts(account("demat", InstrumentType.DEMAT), BalanceState.Known(Money(2_000_00, "INR"), 1)),
            AccountFacts(account("mf2", InstrumentType.MUTUAL_FUND), BalanceState.NoInfo),
            AccountFacts(account("loan", InstrumentType.LOAN), BalanceState.NoInfo),
            AccountFacts(account("other", InstrumentType.UNKNOWN), BalanceState.NoInfo),
        )
        val groups = AccountGroups.group(items, { it })
        assertEquals(listOf(AccountType.LOAN, AccountType.INVESTMENT, AccountType.UNKNOWN), groups.map { it.type })
        val investments = groups[1]
        assertEquals(3, investments.items.size)
        assertEquals(TotalKind.CURRENT_VALUE, investments.totals.kind)
        assertEquals(listOf(Money(3_000_00, "INR")), investments.totals.amounts)
        assertEquals(1, investments.totals.missingCount)
    }

    @Test
    fun `a security alert never reaches the ledger`() {
        assertNull(
            TransactionParser.parse(
                "JM-DEPOSI",
                "10 shares of ACME LTD (ISIN INE123A01016) debited from your demat a/c XXXX5678 on 18-Sep-2026. If not done by you, contact your DP immediately.",
            ),
        )
    }
}
