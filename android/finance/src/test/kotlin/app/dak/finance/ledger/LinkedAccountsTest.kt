package app.dak.finance.ledger

import app.dak.core.model.InstrumentType
import app.dak.finance.money.Money
import app.dak.finance.parser.TransactionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Debit cards / loans linked to the bank account an SMS names, manual type overrides, and matcher families. */
class LinkedAccountsTest {

    private fun input(key: String, date: Long, sender: String, body: String) =
        LedgerInput(key, date, assertNotNull(TransactionParser.parse(sender, body), body))

    private val inr: (String?) -> String? = { "INR" }

    @Test
    fun `debit card spend naming the account reduces that account's balance`() {
        val ledgers = Ledger.apply(
            listOf(
                input("m1", 1_000, "VM-HDFCBK", "Rs.500.00 debited from A/c XX1234 on 01-09-26. Avl Bal Rs.42,500.00"),
                input("m2", 2_000, "VM-HDFCBK", "Rs.2,500.00 debited from A/c XX1234 using Debit Card XX5678 at CROMA. Avl Bal Rs.40,000.00"),
            ),
            defaultHomeCurrency = inr,
        ).associateBy { it.account.id }

        val bank = assertNotNull(ledgers["HDFC_BANK:BANK_ACCOUNT:1234"])
        val card = assertNotNull(ledgers["HDFC_BANK:DEBIT_CARD:5678"])
        assertEquals(2, ledgers.size)

        // The bank account sees both debits and the balance stated in the card SMS.
        assertEquals(listOf("m1", "m2"), bank.entries.map { it.messageKey })
        assertEquals("HDFC_BANK:DEBIT_CARD:5678", bank.entries.last().viaAccountId)
        assertEquals(Money(4000000, "INR"), assertIs<BalanceState.Known>(bank.balanceState).balance)

        // The card has the spend, no balance of its own, and knows its account.
        assertEquals(AccountType.DEBIT_CARD, card.account.type)
        assertEquals("HDFC_BANK:BANK_ACCOUNT:1234", card.account.linkedAccountId)
        assertEquals(listOf("m2"), card.entries.map { it.messageKey })
        assertNull(card.entries.single().balanceAfter)
        assertNull(card.entries.single().viaAccountId)
        assertEquals(BalanceState.NoInfo, card.balanceState)
    }

    @Test
    fun `debit card with no account named is its own entry and invents no balance`() {
        val ledgers = Ledger.apply(
            listOf(input("m1", 1_000, "VM-ICICIB", "Rs 850.00 spent using ICICI Bank Debit Card XX1234 at ZOMATO on 12-Sep-26.")),
            defaultHomeCurrency = inr,
        )
        val card = ledgers.single()
        assertEquals(AccountType.DEBIT_CARD, card.account.type)
        assertNull(card.account.linkedAccountId)
        assertEquals(BalanceState.NoInfo, card.balanceState)
    }

    @Test
    fun `linked bank account with no SMS of its own still appears`() {
        val ledgers = Ledger.apply(
            listOf(input("m1", 1_000, "VM-HDFCBK", "Rs.2,500.00 debited from A/c XX1234 using Debit Card XX5678 at CROMA. Avl Bal Rs.40,000.00")),
            defaultHomeCurrency = inr,
        ).associateBy { it.account.id }
        val bank = assertNotNull(ledgers["HDFC_BANK:BANK_ACCOUNT:1234"])
        assertEquals(InstrumentType.BANK_ACCOUNT, bank.account.instrument)
        assertEquals("1234", bank.account.last4)
        assertEquals("XX1234", bank.account.maskedNumber)
        assertNull(bank.account.linkedAccountId)
    }

    @Test
    fun `foreign debit card spend makes the linked account's balance unknown`() {
        val ledgers = Ledger.apply(
            listOf(
                input("m1", 1_000, "VM-HDFCBK", "Rs.500.00 debited from A/c XX1234 on 01-09-26. Avl Bal Rs.42,500.00"),
                input("m2", 2_000, "VM-HDFCBK", "USD 20.00 debited from A/c XX1234 using Debit Card XX5678 at WALMART."),
            ),
            defaultHomeCurrency = inr,
        ).associateBy { it.account.id }
        val bank = assertNotNull(ledgers["HDFC_BANK:BANK_ACCOUNT:1234"])
        val state = assertIs<BalanceState.Unknown>(bank.balanceState)
        assertEquals(2_000, state.sinceMillis)
    }

    @Test
    fun `EMI debited from an account posts to the loan and the account`() {
        val ledgers = Ledger.apply(
            listOf(input("m1", 1_000, "AXISBK", "EMI of Rs.4,599.00 auto-debited from A/c XX2211 towards Loan a/c on 05-09-26. Avl Bal Rs.22,000.00")),
            defaultHomeCurrency = inr,
        ).associateBy { it.account.id }
        val loan = assertNotNull(ledgers["AXIS_BANK:LOAN:0000"])
        val bank = assertNotNull(ledgers["AXIS_BANK:BANK_ACCOUNT:2211"])
        assertEquals(AccountType.LOAN, loan.account.type)
        assertEquals(bank.account.id, loan.account.linkedAccountId)
        assertEquals(BalanceState.NoInfo, loan.balanceState)
        assertEquals(Money(2200000, "INR"), assertIs<BalanceState.Known>(bank.balanceState).balance)
    }

    @Test
    fun `linked account follows the user's merges`() {
        val aliases = AccountAliases(mapOf("HDFC_BANK:BANK_ACCOUNT:1234" to "HDFC_BANK:BANK_ACCOUNT:441234"))
        val ledgers = Ledger.apply(
            listOf(input("m1", 1_000, "VM-HDFCBK", "Rs.2,500.00 debited from A/c XX1234 using Debit Card XX5678 at CROMA.")),
            defaultHomeCurrency = inr,
            aliases = aliases,
        ).associateBy { it.account.id }
        assertEquals("HDFC_BANK:BANK_ACCOUNT:441234", ledgers["HDFC_BANK:DEBIT_CARD:5678"]?.account?.linkedAccountId)
        assertNotNull(ledgers["HDFC_BANK:BANK_ACCOUNT:441234"])
    }

    @Test
    fun `manual type override changes the type but not the id`() {
        val body = "Rs 300 spent on Yes Bank Card XX2020 at CAFE COFFEE DAY on 01-09-26."
        val detected = Ledger.apply(listOf(input("m1", 1_000, "VM-YESBNK", body)), defaultHomeCurrency = inr).single()
        assertEquals(AccountType.CREDIT_CARD, detected.account.type)

        val overridden = Ledger.apply(
            listOf(input("m1", 1_000, "VM-YESBNK", body)),
            defaultHomeCurrency = inr,
            instrumentOverride = { if (it == detected.account.id) InstrumentType.DEBIT_CARD else null },
        ).single()
        assertEquals(detected.account.id, overridden.account.id)
        assertEquals(AccountType.DEBIT_CARD, overridden.account.type)
        assertNull(overridden.cardOutstanding(1_000, BillingCycle(1)))
    }

    @Test
    fun `every instrument maps to its own account type`() {
        for (instrument in InstrumentType.entries) {
            assertEquals(instrument.name, AccountType.of(instrument).name)
        }
    }

    @Test
    fun `UPI and bank account ids with the same digits are still suggested as one account`() {
        val upi = AccountObservation("HDFC_BANK:UPI:1234", "HDFC Bank", AccountType.UPI, "1234", 0, 10)
        val bank = AccountObservation("HDFC_BANK:BANK_ACCOUNT:1234", "HDFC Bank", AccountType.BANK_ACCOUNT, "1234", 20, 30)
        val card = AccountObservation("HDFC_BANK:DEBIT_CARD:1234", "HDFC Bank", AccountType.DEBIT_CARD, "1234", 20, 30)
        assertEquals(AliasReason.SAME_DIGITS, AccountMatcher.compare(upi, bank)?.reason)
        assertNull(AccountMatcher.compare(bank, card))
    }
}
