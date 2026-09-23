package app.dak.finance.passbook

import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountType
import app.dak.finance.ledger.BalanceState
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.money.Money
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountGroupsTest {

    private fun account(id: String, instrument: InstrumentType, home: String = "INR") =
        Account(id = id, institution = "Bank", instrument = instrument, last4 = "1234", homeCurrency = home)

    private fun facts(
        id: String,
        instrument: InstrumentType,
        balance: BalanceState = BalanceState.NoInfo,
        spent: List<Money> = emptyList(),
        outstanding: Money? = null,
    ) = AccountFacts(account(id, instrument), balance, spent, outstanding)

    @Test
    fun `groups follow the passbook order and empty groups are hidden`() {
        val items = listOf(
            facts("w", InstrumentType.WALLET),
            facts("a", InstrumentType.BANK_ACCOUNT),
            facts("l", InstrumentType.LOAN),
            facts("d", InstrumentType.DEBIT_CARD),
            facts("u", InstrumentType.UNKNOWN),
            facts("c", InstrumentType.CREDIT_CARD),
            facts("p", InstrumentType.PREPAID_CARD),
            facts("i", InstrumentType.UPI),
        )
        val groups = AccountGroups.group(items, { it })
        assertEquals(
            listOf(
                AccountType.BANK_ACCOUNT, AccountType.CREDIT_CARD, AccountType.DEBIT_CARD, AccountType.WALLET,
                AccountType.UPI, AccountType.PREPAID_CARD, AccountType.LOAN, AccountType.UNKNOWN,
            ),
            groups.map { it.type },
        )
        val onlyTwo = AccountGroups.group(listOf(facts("w", InstrumentType.WALLET), facts("a", InstrumentType.BANK_ACCOUNT)), { it })
        assertEquals(listOf(AccountType.BANK_ACCOUNT, AccountType.WALLET), onlyTwo.map { it.type })
    }

    @Test
    fun `bank balances are summed per currency and unknown balances are counted, not guessed`() {
        val items = listOf(
            facts("a1", InstrumentType.BANK_ACCOUNT, BalanceState.Known(Money(100000, "INR"), 1)),
            facts("a2", InstrumentType.BANK_ACCOUNT, BalanceState.Known(Money(50000, "INR"), 1)),
            facts("a3", InstrumentType.BANK_ACCOUNT, BalanceState.Known(Money(2000, "AED"), 1)),
            facts("a4", InstrumentType.BANK_ACCOUNT, BalanceState.Unknown(5, BalanceState.Known(Money(1, "INR"), 1))),
            facts("a5", InstrumentType.BANK_ACCOUNT),
        )
        val totals = AccountGroups.group(items, { it }).single().totals
        assertEquals(TotalKind.BALANCE, totals.kind)
        assertEquals(listOf(Money(2000, "AED"), Money(150000, "INR")), totals.amounts)
        assertEquals(2, totals.missingCount)
    }

    @Test
    fun `credit card header totals outstanding`() {
        val items = listOf(
            facts("c1", InstrumentType.CREDIT_CARD, outstanding = Money(30000, "INR")),
            facts("c2", InstrumentType.CREDIT_CARD, outstanding = Money(20000, "INR")),
            facts("c3", InstrumentType.CREDIT_CARD),
        )
        val totals = AccountGroups.group(items, { it }).single().totals
        assertEquals(TotalKind.OUTSTANDING, totals.kind)
        assertEquals(listOf(Money(50000, "INR")), totals.amounts)
        assertEquals(1, totals.missingCount)
    }

    @Test
    fun `debit card header totals this month's spend per currency`() {
        val items = listOf(
            facts("d1", InstrumentType.DEBIT_CARD, spent = listOf(Money(1000, "INR"), Money(500, "USD"))),
            facts("d2", InstrumentType.DEBIT_CARD, spent = listOf(Money(2000, "INR"))),
        )
        val totals = AccountGroups.group(items, { it }).single().totals
        assertEquals(TotalKind.SPENT_THIS_MONTH, totals.kind)
        assertEquals(listOf(Money(3000, "INR"), Money(500, "USD")), totals.amounts)
        assertEquals(0, totals.missingCount)
    }

    @Test
    fun `prepaid card balances stay per currency`() {
        val items = listOf(
            facts("p1", InstrumentType.PREPAID_CARD, BalanceState.Known(Money(54000, "USD"), 1)),
            facts("p2", InstrumentType.PREPAID_CARD, BalanceState.Known(Money(23010, "EUR"), 1)),
        )
        val totals = AccountGroups.group(items, { it }).single().totals
        assertEquals(listOf(Money(23010, "EUR"), Money(54000, "USD")), totals.amounts)
    }

    @Test
    fun `items within a group are ordered by last activity`() {
        val items = listOf("a" to 1L, "b" to 3L, "c" to 2L)
        val groups = AccountGroups.group(items, { facts(it.first, InstrumentType.WALLET) }, lastActivity = { it.second })
        assertEquals(listOf("b", "c", "a"), groups.single().items.map { it.first })
    }

    @Test
    fun `spentSince counts debits on or after the start only`() {
        fun entry(key: String, date: Long, direction: TransactionDirection, money: Money) =
            LedgerEntry(messageKey = key, dateMillis = date, direction = direction, original = money)
        val entries = listOf(
            entry("1", 99, TransactionDirection.DEBIT, Money(100, "INR")),
            entry("2", 100, TransactionDirection.DEBIT, Money(200, "INR")),
            entry("3", 150, TransactionDirection.CREDIT, Money(999, "INR")),
            entry("4", 160, TransactionDirection.DEBIT, Money(300, "AED")),
        )
        assertEquals(listOf(Money(300, "AED"), Money(200, "INR")), AccountGroups.spentSince(entries, 100))
    }

    @Test
    fun `month start is UTC midnight of day one`() {
        val now = LocalDate.of(2026, 9, 23).atTime(15, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val expected = LocalDate.of(2026, 9, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expected, AccountGroups.monthStartUtc(now))
        assertTrue(AccountGroups.monthStartUtc(expected) == expected)
    }
}
