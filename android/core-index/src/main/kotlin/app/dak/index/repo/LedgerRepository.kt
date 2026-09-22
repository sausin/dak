package app.dak.index.repo

import androidx.room.withTransaction
import app.dak.core.model.MessageKey
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountLedger
import app.dak.finance.ledger.BalanceState
import app.dak.finance.ledger.BillingCycle
import app.dak.finance.ledger.Ledger
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.ledger.LedgerInput
import app.dak.finance.money.Money
import app.dak.finance.passbook.MonthlyTotal
import app.dak.finance.passbook.Passbook
import app.dak.finance.rates.RatesLoader
import app.dak.finance.rates.RatesTable
import app.dak.finance.reconcile.Reconciler
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.AccountRow
import app.dak.index.db.entity.LedgerEntryRow
import app.dak.index.sync.IndexRowMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the exchange-rates table for indicative home-currency values (bundled, or the latest OTA fetch). */
fun interface RatesSource {
    fun current(): RatesTable?
}

/** An account with its honest balance, for the Passbook list. */
data class AccountSummary(
    val account: Account,
    val balance: BalanceState,
    val entryCount: Int,
    val lastActivityMillis: Long,
)

/**
 * The finance ledger over the index. Ledgers are derived: whenever a transaction message of an account is
 * (re)indexed, that account is recomputed with `app.dak.finance.ledger.Ledger` and
 * `app.dak.finance.reconcile.Reconciler` and persisted (entries + cached balance). The user-set statement day is
 * preserved across recomputes.
 */
@Singleton
class LedgerRepository @Inject constructor(
    private val db: DakIndexDatabase,
    ratesSource: Optional<RatesSource>,
) {
    private val ledgerDao = db.ledgerDao()
    private val messageDao = db.messageDao()
    private val mutex = Mutex()
    private val rates: RatesSource = ratesSource.orElse(BundledRates)

    fun accounts(): Flow<List<AccountSummary>> =
        ledgerDao.observeAccounts().map { rows -> rows.map { toSummary(it) } }.flowOn(Dispatchers.Default)

    fun account(accountId: String): Flow<AccountSummary?> =
        ledgerDao.observeAccount(accountId).map { it?.let { row -> toSummary(row) } }

    /** Entries of one account, newest first. */
    fun entries(accountId: String): Flow<List<LedgerEntry>> =
        ledgerDao.observeEntries(accountId).map { rows -> rows.map { toEntry(it) } }

    /** Source message of a ledger entry (to open it in its thread). */
    fun messageKeyOf(entry: LedgerEntry): MessageKey? = MessageKey.parse(entry.messageKey)

    fun ledger(accountId: String): Flow<AccountLedger?> =
        combine(ledgerDao.observeAccount(accountId), ledgerDao.observeEntries(accountId)) { account, entries ->
            account?.let { row -> AccountLedger(toAccount(row), entries.map { e -> toEntry(e) }.sortedBy { e -> e.dateMillis }) }
        }.flowOn(Dispatchers.Default)

    fun monthlyTotals(accountId: String): Flow<List<MonthlyTotal>> =
        ledger(accountId).map { it?.let(Passbook::monthlyTotals).orEmpty() }

    fun spendByMerchant(accountId: String): Flow<Map<String, Money>> =
        ledger(accountId).map { it?.let { l -> Passbook.spendByMerchant(l) }.orEmpty() }

    /** Card outstanding for the billing cycle containing [asOfMillis]; null for non-cards or unknown statement day. */
    fun cardOutstanding(accountId: String, asOfMillis: Long): Flow<Money?> =
        ledger(accountId).map { l ->
            if (l == null) return@map null
            val day = l.account.statementDay ?: return@map null
            l.cardOutstanding(asOfMillis, BillingCycle(day))
        }

    /** Sets a credit card's statement day (1..31, or null to clear). */
    suspend fun setStatementDay(accountId: String, statementDay: Int?) {
        require(statementDay == null || statementDay in 1..31) { "statementDay must be 1..31" }
        ledgerDao.setStatementDay(accountId, statementDay)
        recompute(listOf(accountId))
    }

    /** Recomputes the given accounts from their indexed transaction messages. */
    suspend fun recompute(accountIds: Collection<String>) {
        if (accountIds.isEmpty()) return
        mutex.withLock {
            for (id in accountIds.distinct()) recomputeLocked(id)
        }
    }

    /** Recomputes every account (after a rebuild, restore or rates update). */
    suspend fun recomputeAll() {
        mutex.withLock {
            val ids = (messageDao.accountIds() + ledgerDao.accountIds()).distinct()
            for (id in ids) recomputeLocked(id)
        }
    }

    private suspend fun recomputeLocked(accountId: String) {
        val rows = messageDao.byAccount(accountId)
        val inputs = rows.mapNotNull { row ->
            IndexRowMapper.transaction(row)?.let { LedgerInput(IndexRowMapper.key(row).toString(), row.dateMillis, it) }
        }
        val previous = ledgerDao.account(accountId)
        if (inputs.isEmpty()) {
            db.withTransaction {
                ledgerDao.deleteEntries(accountId)
                ledgerDao.deleteAccount(accountId)
            }
            return
        }
        val built = Ledger.apply(inputs, rates.current(), statementDayFor = { previous?.statementDay })
            .firstOrNull { it.account.id == accountId } ?: return
        val reconciled = AccountLedger(built.account, Reconciler.reconcile(built.entries).entries)
        val now = System.currentTimeMillis()
        val accountRow = toAccountRow(reconciled, previous?.statementDay, now)
        val entryRows = reconciled.entries.map { toEntryRow(accountId, it) }
        db.withTransaction {
            ledgerDao.deleteEntries(accountId)
            entryRows.chunked(CHUNK).forEach { ledgerDao.putEntries(it) }
            ledgerDao.putAccount(accountRow)
        }
    }

    private object BundledRates : RatesSource {
        private val table: RatesTable by lazy { RatesLoader.loadBundled() }
        override fun current(): RatesTable = table
    }

    internal companion object {
        private const val CHUNK = 300

        fun toAccount(row: AccountRow): Account = Account(
            id = row.id,
            institution = row.institution,
            instrument = row.instrument,
            last4 = row.last4,
            homeCurrency = row.homeCurrency,
            statementDay = row.statementDay,
        )

        fun toSummary(row: AccountRow): AccountSummary =
            AccountSummary(toAccount(row), balanceOf(row), row.entryCount, row.lastActivityMillis)

        fun balanceOf(row: AccountRow): BalanceState {
            val known = if (row.balanceMinor != null && row.balanceCurrency != null && row.balanceAsOfMillis != null) {
                BalanceState.Known(Money(row.balanceMinor, row.balanceCurrency), row.balanceAsOfMillis)
            } else {
                null
            }
            return when (row.balanceState) {
                STATE_KNOWN -> known ?: BalanceState.NoInfo
                STATE_UNKNOWN -> BalanceState.Unknown(row.unknownSinceMillis ?: 0L, known)
                else -> BalanceState.NoInfo
            }
        }

        fun toAccountRow(ledger: AccountLedger, statementDay: Int?, nowMillis: Long): AccountRow {
            val state = ledger.balanceState
            val known: BalanceState.Known? = when (state) {
                is BalanceState.Known -> state
                is BalanceState.Unknown -> state.lastKnown
                BalanceState.NoInfo -> null
            }
            return AccountRow(
                id = ledger.account.id,
                institution = ledger.account.institution,
                instrument = ledger.account.instrument,
                last4 = ledger.account.last4,
                homeCurrency = ledger.account.homeCurrency,
                statementDay = statementDay,
                balanceState = when (state) {
                    is BalanceState.Known -> STATE_KNOWN
                    is BalanceState.Unknown -> STATE_UNKNOWN
                    BalanceState.NoInfo -> STATE_NO_INFO
                },
                balanceMinor = known?.balance?.amountMinor,
                balanceCurrency = known?.balance?.currencyUpper,
                balanceAsOfMillis = known?.asOfMillis,
                unknownSinceMillis = (state as? BalanceState.Unknown)?.sinceMillis,
                entryCount = ledger.entries.size,
                lastActivityMillis = ledger.entries.maxOfOrNull { it.dateMillis } ?: 0L,
                updatedAt = nowMillis,
            )
        }

        fun toEntryRow(accountId: String, e: LedgerEntry): LedgerEntryRow = LedgerEntryRow(
            messageKey = e.messageKey,
            accountId = accountId,
            dateMillis = e.dateMillis,
            direction = e.direction,
            originalMinor = e.original.amountMinor,
            originalCurrency = e.original.currencyUpper,
            indicativeMinor = e.indicativeHome?.amountMinor,
            indicativeCurrency = e.indicativeHome?.currencyUpper,
            rate = e.rate?.toPlainString(),
            rateDateMillis = e.rateDateMillis,
            settled = e.settled,
            markupPercent = e.effectiveMarkupPercent?.toPlainString(),
            balanceAfterMinor = e.balanceAfter?.amountMinor,
            balanceAfterCurrency = e.balanceAfter?.currencyUpper,
            merchant = e.merchant,
            reference = e.reference,
        )

        fun toEntry(row: LedgerEntryRow): LedgerEntry = LedgerEntry(
            messageKey = row.messageKey,
            dateMillis = row.dateMillis,
            direction = row.direction,
            original = Money(row.originalMinor, row.originalCurrency),
            indicativeHome = if (row.indicativeMinor != null && row.indicativeCurrency != null) {
                Money(row.indicativeMinor, row.indicativeCurrency)
            } else {
                null
            },
            rate = row.rate?.let(::decimalOrNull),
            rateDateMillis = row.rateDateMillis,
            settled = row.settled,
            effectiveMarkupPercent = row.markupPercent?.let(::decimalOrNull),
            balanceAfter = if (row.balanceAfterMinor != null && row.balanceAfterCurrency != null) {
                Money(row.balanceAfterMinor, row.balanceAfterCurrency)
            } else {
                null
            },
            merchant = row.merchant,
            reference = row.reference,
        )

        private fun decimalOrNull(s: String): BigDecimal? = runCatching { BigDecimal(s) }.getOrNull()

        const val STATE_KNOWN = "KNOWN"
        const val STATE_UNKNOWN = "UNKNOWN"
        const val STATE_NO_INFO = "NO_INFO"
    }
}
