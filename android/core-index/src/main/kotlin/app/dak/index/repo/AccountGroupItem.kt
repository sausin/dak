package app.dak.index.repo

import app.dak.finance.money.Money

/**
 * One account in a Passbook section ([LedgerRepository.accountGroups]).
 *
 * @property spentThisMonth debits since the start of the current month (UTC), one [Money] per original currency.
 * @property outstanding a credit card's outstanding for the current billing cycle; null for other types or when the
 *   statement day is not set.
 * @property linked the bank account a debit card's / loan's SMS named, when known (never guessed).
 */
data class AccountGroupItem(
    val summary: AccountSummary,
    val spentThisMonth: List<Money>,
    val outstanding: Money?,
    val linked: AccountSummary?,
)
