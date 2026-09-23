package app.dak.finance.passbook

import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountType
import app.dak.finance.ledger.BalanceState
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.money.Money
import java.time.Instant
import java.time.ZoneOffset

/** What the grouping needs to know about one account. */
data class AccountFacts(
    val account: Account,
    val balance: BalanceState,
    /** Debits in the current month, one [Money] per original currency. */
    val spentThisMonth: List<Money> = emptyList(),
    /** A credit card's outstanding for the current billing cycle, when its statement day is known. */
    val outstanding: Money? = null,
)

/**
 * Header totals of one Passbook group, per currency and never converted.
 *
 * [amounts] is what the group's header shows: the sum of stated balances for bank accounts, wallets, prepaid cards
 * and loans (a loan's balance is its stated outstanding), the sum of billing-cycle outstandings for credit cards,
 * and this month's spend for debit cards, UPI and other. [missingCount] accounts contributed nothing to [amounts]
 * because their balance/outstanding is not known (no SMS stated it, or it went unknown after a foreign spend), so
 * the header can say the total is partial instead of pretending it is complete. [spentThisMonth] is always the
 * group's debits this month.
 */
data class GroupTotals(
    val kind: TotalKind,
    val amounts: List<Money>,
    val missingCount: Int,
    val spentThisMonth: List<Money>,
)

/** Which figure a [GroupTotals.amounts] is. */
enum class TotalKind { BALANCE, OUTSTANDING, SPENT_THIS_MONTH }

/** One Passbook section: accounts of one [type], most recently active first, with [totals]. */
data class AccountGroup<T>(val type: AccountType, val items: List<T>, val totals: GroupTotals)

/**
 * Groups accounts for the Passbook: Bank accounts, Credit cards, Debit cards, Wallets, UPI, Prepaid & forex cards,
 * Loans, Other ([AccountType] order). Empty groups are left out. Pure; callers supply per-account [AccountFacts].
 */
object AccountGroups {

    /** Group order of the Passbook. */
    val ORDER: List<AccountType> = AccountType.entries

    /** Which figure the header of a [type] group totals. */
    fun totalKindOf(type: AccountType): TotalKind = when (type) {
        AccountType.BANK_ACCOUNT, AccountType.WALLET, AccountType.PREPAID_CARD, AccountType.LOAN -> TotalKind.BALANCE
        AccountType.CREDIT_CARD -> TotalKind.OUTSTANDING
        AccountType.DEBIT_CARD, AccountType.UPI, AccountType.UNKNOWN -> TotalKind.SPENT_THIS_MONTH
    }

    /** Groups [items] by their account's type; [facts] describes each item. [lastActivity] orders within a group. */
    fun <T> group(items: List<T>, facts: (T) -> AccountFacts, lastActivity: (T) -> Long = { 0L }): List<AccountGroup<T>> {
        val byType = items.groupBy { facts(it).account.type }
        return ORDER.mapNotNull { type ->
            val members = byType[type].orEmpty()
            if (members.isEmpty()) return@mapNotNull null
            val sorted = members.sortedByDescending(lastActivity)
            AccountGroup(type, sorted, totalsOf(type, sorted.map(facts)))
        }
    }

    /** Header totals of a [type] group made of [members]. */
    fun totalsOf(type: AccountType, members: List<AccountFacts>): GroupTotals {
        val kind = totalKindOf(type)
        val spent = sum(members.flatMap { it.spentThisMonth })
        return when (kind) {
            TotalKind.BALANCE -> {
                val known = members.mapNotNull { (it.balance as? BalanceState.Known)?.balance }
                GroupTotals(kind, sum(known), members.size - known.size, spent)
            }
            TotalKind.OUTSTANDING -> {
                val known = members.mapNotNull { it.outstanding }
                GroupTotals(kind, sum(known), members.size - known.size, spent)
            }
            TotalKind.SPENT_THIS_MONTH -> GroupTotals(kind, spent, 0, spent)
        }
    }

    /** Debits of [entries] dated on/after [sinceMillis], one [Money] per original currency. */
    fun spentSince(entries: List<LedgerEntry>, sinceMillis: Long): List<Money> =
        sum(entries.filter { it.direction == TransactionDirection.DEBIT && it.dateMillis >= sinceMillis }.map { it.original })

    /** Start (UTC midnight of day 1) of the calendar month containing [nowMillis], as [Passbook.monthlyTotals] uses. */
    fun monthStartUtc(nowMillis: Long): Long {
        val date = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /** Sums per currency; the result is sorted by currency code for a stable display. */
    fun sum(amounts: List<Money>): List<Money> =
        amounts.groupBy { it.currencyUpper }
            .map { (currency, list) -> list.fold(Money.zero(currency)) { acc, m -> acc + m } }
            .sortedBy { it.currencyUpper }
}
