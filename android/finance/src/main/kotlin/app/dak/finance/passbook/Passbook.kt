package app.dak.finance.passbook

import app.dak.core.model.InvestmentAction
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.AccountLedger
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.money.Money
import java.time.Instant
import java.time.ZoneOffset

/**
 * One calendar month's activity on an account: debits and credits kept per original currency
 * (a month can mix INR and, say, AED spends) plus a best-effort total in the account's home
 * currency, using whatever indicative/settled value each entry carries.
 *
 * Debits and credits are spending and income only: own-account / investment transfers ([LedgerEntry.transfer]: a
 * bank's SIP debit, a fund's allotment, a redemption) are totalled separately in [transfersOutHome] /
 * [transfersInHome], and switches and valuations are not money moving at all, so they are in neither.
 */
data class MonthlyTotal(
    val yearMonth: String, // "2026-09"
    val debitsByCurrency: Map<String, Money>,
    val creditsByCurrency: Map<String, Money>,
    val debitsHome: Money,
    val creditsHome: Money,
    /** Transfer debits (money moved out to the user's own investments / accounts; a redemption on a fund). */
    val transfersOutHome: Money = Money.zero(debitsHome.currencyUpper),
    /** Transfer credits (money moved in from the user's own accounts; a purchase on a fund or demat account). */
    val transfersInHome: Money = Money.zero(creditsHome.currencyUpper),
)

/** Summary helpers over a built [AccountLedger]: the "Passbook" view (a ledger, not a budgeting tool). */
object Passbook {

    /** Groups [ledger]'s entries into one [MonthlyTotal] per calendar month (UTC), oldest first. */
    fun monthlyTotals(ledger: AccountLedger): List<MonthlyTotal> {
        val home = ledger.account.homeCurrency.uppercase()
        return ledger.entries
            .groupBy { yearMonthOf(it.dateMillis) }
            .toSortedMap()
            .map { (month, entries) ->
                val moving = entries.filter { it.movesMoney }
                val (transfers, own) = moving.partition { it.transfer }
                val debits = own.filter { it.direction == TransactionDirection.DEBIT }
                val credits = own.filter { it.direction == TransactionDirection.CREDIT }
                MonthlyTotal(
                    yearMonth = month,
                    debitsByCurrency = sumByCurrency(debits.map { it.original }),
                    creditsByCurrency = sumByCurrency(credits.map { it.original }),
                    debitsHome = sumHome(debits.map { it.homeValue }, home),
                    creditsHome = sumHome(credits.map { it.homeValue }, home),
                    transfersOutHome = sumHome(transfers.filter { it.direction == TransactionDirection.DEBIT }.map { it.homeValue }, home),
                    transfersInHome = sumHome(transfers.filter { it.direction == TransactionDirection.CREDIT }.map { it.homeValue }, home),
                )
            }
    }

    /**
     * Total spend (debits only, home-currency best-known value) grouped by merchant, descending
     * by amount. Entries with no merchant are grouped under [unknownMerchantLabel]. Transfers to the user's own
     * accounts and investments ([LedgerEntry.transfer]) are not spending and are left out.
     */
    fun spendByMerchant(ledger: AccountLedger, unknownMerchantLabel: String = "Unknown"): Map<String, Money> {
        val home = ledger.account.homeCurrency.uppercase()
        val debits = ledger.entries.filter { it.isSpend }
        val grouped = debits.groupBy { it.merchant?.trim()?.takeIf { m -> m.isNotEmpty() } ?: unknownMerchantLabel }
        return grouped
            .mapValues { (_, entries) -> sumHome(entries.map { it.homeValue }, home) }
            .toList()
            .sortedByDescending { it.second.amountMinor }
            .toMap()
    }

    /** A debit that is spending: not a transfer to the user's own accounts / investments, not a valuation or switch. */
    val LedgerEntry.isSpend: Boolean get() = direction == TransactionDirection.DEBIT && !transfer && movesMoney

    /** Money entered or left the account (not a valuation statement or a switch between schemes of one folio). */
    private val LedgerEntry.movesMoney: Boolean
        get() = investmentAction != InvestmentAction.VALUATION && investmentAction != InvestmentAction.SWITCH

    private fun yearMonthOf(dateMillis: Long): String {
        val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return "%04d-%02d".format(date.year, date.monthValue)
    }

    private fun sumByCurrency(amounts: List<Money>): Map<String, Money> =
        amounts.groupBy { it.currencyUpper }.mapValues { (currency, list) ->
            list.fold(Money.zero(currency)) { acc, m -> acc + m }
        }

    /** Sums only the amounts already in [home]; a foreign amount with no resolvable home value is skipped, never guessed. */
    private fun sumHome(amounts: List<Money>, home: String): Money =
        amounts.filter { it.currencyUpper == home }.fold(Money.zero(home)) { acc, m -> acc + m }
}
