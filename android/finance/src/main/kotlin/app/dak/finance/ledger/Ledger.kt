package app.dak.finance.ledger

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.Money
import app.dak.finance.rates.RatesTable

/** One classified message, ready to be posted to a ledger. */
data class LedgerInput(val messageKey: String, val dateMillis: Long, val transaction: ExtractedTransaction)

/** The built ledger for one [Account]: its posted entries and the balance/outstanding derived from them. */
data class AccountLedger(val account: Account, val entries: List<LedgerEntry>) {

    /** Entries sorted oldest-first, as [Ledger.apply] always produces them, but re-asserted for callers that reorder. */
    private val sortedEntries: List<LedgerEntry> get() = entries.sortedBy { it.dateMillis }

    /**
     * The account's balance, honestly: the value from the latest balance-bearing SMS, or
     * [BalanceState.Unknown] if an unsettled (typically foreign) transaction happened since then.
     */
    val balanceState: BalanceState by lazy {
        val balanceEntries = sortedEntries.filter { it.balanceAfter != null }
        val last = balanceEntries.maxByOrNull { it.dateMillis } ?: return@lazy BalanceState.NoInfo
        val known = BalanceState.Known(last.balanceAfter!!, last.dateMillis)
        val unsettledSince = sortedEntries
            .filter { !it.settled && it.dateMillis > last.dateMillis }
            .minOfOrNull { it.dateMillis }
        if (unsettledSince != null) BalanceState.Unknown(sinceMillis = unsettledSince, lastKnown = known) else known
    }

    /**
     * For a [AccountType.CREDIT_CARD] account: outstanding = debits minus payments/credits within
     * the billing cycle containing [asOfMillis], using each entry's best-known home-currency
     * value. Entries whose home value could not be resolved to the account's home currency (no
     * rate available yet) are excluded rather than guessed at. Returns null for non-card accounts.
     */
    fun cardOutstanding(asOfMillis: Long, billingCycle: BillingCycle): Money? {
        if (account.type != AccountType.CREDIT_CARD) return null
        val range = billingCycle.cycleRange(asOfMillis)
        var total = Money.zero(account.homeCurrency)
        for (entry in sortedEntries) {
            if (entry.dateMillis !in range) continue
            val value = entry.homeValue
            if (value.currencyUpper != total.currencyUpper) continue
            total = when (entry.direction) {
                TransactionDirection.DEBIT -> total + value
                TransactionDirection.CREDIT -> total - value
            }
        }
        return total
    }
}

/**
 * Pure functions that turn a flat stream of classified messages into per-account ledgers. Never
 * mutates anything and never touches I/O; callers own persistence.
 */
object Ledger {

    /**
     * Builds one [AccountLedger] per distinct (institution, instrument, last4) seen in [inputs].
     *
     * @param rates used to compute an indicative home-currency value for a foreign-currency entry;
     *   when null (or the pair is not covered), foreign entries are posted with no indicative value.
     * @param defaultHomeCurrency the account's home currency when no balance-bearing SMS states one
     *   (defaults to INR, matching Indian institutions per the product spec).
     * @param statementDayFor supplies a credit card's configured statement day; null leaves it unset.
     */
    fun apply(
        inputs: List<LedgerInput>,
        rates: RatesTable? = null,
        defaultHomeCurrency: (institution: String?) -> String = { "INR" },
        statementDayFor: (Account) -> Int? = { null },
    ): List<AccountLedger> {
        val grouped = inputs.groupBy {
            Account.idFor(it.transaction.institution, it.transaction.instrument, it.transaction.last4)
        }
        return grouped.map { (id, group) ->
            val sorted = group.sortedBy { it.dateMillis }
            val sample = sorted.first().transaction
            val homeCurrency = sorted.firstNotNullOfOrNull { it.transaction.balanceCurrency }
                ?: defaultHomeCurrency(sample.institution)
            var account = Account(
                id = id,
                institution = sample.institution ?: "Unknown",
                instrument = sample.instrument,
                last4 = sample.last4,
                homeCurrency = homeCurrency,
            )
            account = account.copy(statementDay = statementDayFor(account))
            val entries = sorted.map { buildEntry(it, homeCurrency, rates) }
            AccountLedger(account, entries)
        }
    }

    private fun buildEntry(input: LedgerInput, homeCurrency: String, rates: RatesTable?): LedgerEntry {
        val txn = input.transaction
        val original = Money(txn.amountMinor, txn.currency)
        val balanceAfter = txn.balanceMinor?.let { Money(it, txn.balanceCurrency ?: txn.currency) }
        val homeUpper = homeCurrency.uppercase()

        return if (original.currencyUpper == homeUpper) {
            LedgerEntry(
                messageKey = input.messageKey,
                dateMillis = input.dateMillis,
                direction = txn.direction,
                original = original,
                indicativeHome = original,
                settled = true,
                balanceAfter = balanceAfter,
                merchant = txn.merchant,
                reference = txn.reference,
            )
        } else {
            val rate = rates?.rate(original.currencyUpper, homeUpper)
            val indicative = rate?.let { Money.convert(original, homeUpper, it) }
            LedgerEntry(
                messageKey = input.messageKey,
                dateMillis = input.dateMillis,
                direction = txn.direction,
                original = original,
                indicativeHome = indicative,
                rate = rate,
                rateDateMillis = if (rate != null) input.dateMillis else null,
                settled = false,
                balanceAfter = balanceAfter,
                merchant = txn.merchant,
                reference = txn.reference,
            )
        }
    }
}
