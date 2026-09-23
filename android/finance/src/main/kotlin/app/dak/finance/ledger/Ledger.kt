package app.dak.finance.ledger

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
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
     * Builds one [AccountLedger] per distinct account (institution, instrument, visible digits; see
     * [Account.idOf]) seen in [inputs], after applying [aliases].
     *
     * @param rates used to compute an indicative home-currency value for a foreign-currency entry;
     *   when null (or the pair is not covered), foreign entries are posted with no indicative value.
     * @param defaultHomeCurrency the account's home currency when no balance-bearing SMS states one, e.g. INR for
     *   an Indian institution or the currency of the user's region (see `CurrencyTable.symbolMapFor`); null when
     *   unknown, in which case the currency most of the account's own transactions are in is used. Never assumes a
     *   country.
     * @param statementDayFor supplies a credit card's configured statement day; null leaves it unset.
     * @param aliases user-confirmed merges: inputs of an alias id are posted to the account it resolves to.
     * @param instrumentOverride the user's manual type for an account ("This is a credit card"), by canonical id;
     *   it changes the account's [Account.instrument] (and so its type and group) but never its id.
     *
     * A debit-card or loan transaction whose SMS also names the bank account the money moved from
     * ([ExtractedTransaction.linkedMaskedNumber]) is posted twice: to the card/loan (without the balance, which is
     * the bank account's) and to that bank account (with the balance, [LedgerEntry.viaAccountId] = the card/loan), so
     * the spend reduces the right balance. Without a named account nothing is linked and no balance is moved.
     */
    fun apply(
        inputs: List<LedgerInput>,
        rates: RatesTable? = null,
        defaultHomeCurrency: (institution: String?) -> String? = { null },
        statementDayFor: (Account) -> Int? = { null },
        aliases: AccountAliases = AccountAliases.NONE,
        instrumentOverride: (accountId: String) -> InstrumentType? = { null },
    ): List<AccountLedger> {
        val grouped = inputs.flatMap { postingsOf(it, aliases) }.groupBy { aliases.resolve(Account.idOf(it.input.transaction)) }
        return grouped.map { (id, group) ->
            val sorted = group.sortedBy { it.input.dateMillis }
            // Describe the account by its own (canonical) messages when there are any, newest first, so a merged
            // alias never renames it; fall back to entries posted through a linked card, then to merged messages.
            val canonical = sorted.filter { Account.idOf(it.input.transaction) == id }
            val own = canonical.filter { it.viaAccountId == null }.ifEmpty { canonical }.ifEmpty { sorted }
            val sample = own.last().input.transaction
            val homeCurrency = sorted.firstNotNullOfOrNull { it.input.transaction.balanceCurrency }
                ?: defaultHomeCurrency(sample.institution)?.trim()?.uppercase()?.takeIf { it.length == 3 }
                ?: dominantCurrency(sorted.map { it.input })
            val linked = own.lastOrNull { it.viaAccountId == null && Account.linkedIdOf(it.input.transaction) != null }
                ?.let { Account.linkedIdOf(it.input.transaction) }
                ?.let { aliases.resolve(it) }
            var account = Account(
                id = id,
                institution = sample.institution ?: "Unknown",
                instrument = instrumentOverride(id) ?: sample.instrument,
                last4 = sample.last4,
                homeCurrency = homeCurrency,
                maskedNumber = own.lastOrNull { it.input.transaction.maskedNumber != null }?.input?.transaction?.maskedNumber,
                linkedAccountId = linked,
            )
            account = account.copy(statementDay = statementDayFor(account))
            val entries = sorted.map { buildEntry(it.input, homeCurrency, rates).copy(viaAccountId = it.viaAccountId) }
            AccountLedger(account, entries)
        }
    }

    /** One input posted to one account; [viaAccountId] is set on the copy posted to a linked bank account. */
    private data class Posting(val input: LedgerInput, val viaAccountId: String? = null)

    private fun postingsOf(input: LedgerInput, aliases: AccountAliases): List<Posting> {
        val txn = input.transaction
        if (Account.linkedIdOf(txn) == null) return listOf(Posting(input))
        val linkedMasked = txn.linkedMaskedNumber ?: return listOf(Posting(input))
        // The stated balance is the bank account's: keep it off the card/loan.
        val primary = input.copy(transaction = txn.copy(balanceMinor = null, balanceCurrency = null))
        val bankTxn = txn.copy(
            instrument = InstrumentType.BANK_ACCOUNT,
            maskedNumber = linkedMasked,
            last4 = linkedMasked.filter { it.isDigit() }.takeLast(4),
            linkedMaskedNumber = null,
        )
        return listOf(Posting(primary), Posting(input.copy(transaction = bankTxn), viaAccountId = aliases.resolve(Account.idOf(txn))))
    }

    /** The currency most of [inputs] are in (ties: the earliest seen), from the SMS themselves. */
    private fun dominantCurrency(inputs: List<LedgerInput>): String =
        inputs.groupingBy { it.transaction.currency.uppercase() }.eachCount()
            .maxByOrNull { it.value }?.key ?: inputs.first().transaction.currency.uppercase()

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
