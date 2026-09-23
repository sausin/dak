package app.dak.index.repo

import androidx.room.withTransaction
import app.dak.core.model.MessageKey
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountAliases
import app.dak.finance.ledger.AccountLedger
import app.dak.finance.ledger.AccountMatcher
import app.dak.finance.ledger.AccountObservation
import app.dak.finance.ledger.AliasReason
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
import app.dak.index.db.entity.AccountAliasRow
import app.dak.index.db.entity.AccountRow
import app.dak.index.db.entity.LedgerEntryRow
import app.dak.index.sync.IndexRowMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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

/**
 * A probable same-account pair for the Passbook confirmation card: "Is A/c ••40065 the same as ••440065 (HDFC
 * Bank)?". [sampleA]/[sampleB] are the newest SMS of each, to show one tap away.
 */
data class AccountAliasSuggestion(
    val a: AccountSummary,
    val b: AccountSummary,
    val reason: AliasReason,
    val sampleA: MessageKey?,
    val sampleB: MessageKey?,
)

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
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LedgerRepository @Inject constructor(
    private val db: DakIndexDatabase,
    ratesSource: Optional<RatesSource>,
) {
    private val ledgerDao = db.ledgerDao()
    private val messageDao = db.messageDao()
    private val aliasDao = db.accountAliasDao()
    private val mutex = Mutex()
    private val rates: RatesSource = ratesSource.orElse(BundledRates)

    fun accounts(): Flow<List<AccountSummary>> =
        ledgerDao.observeAccounts().map { rows -> rows.map { toSummary(it) } }.flowOn(Dispatchers.Default)

    /** One account; an id merged into another account shows that (canonical) account. */
    fun account(accountId: String): Flow<AccountSummary?> =
        canonicalId(accountId).flatMapLatest { id -> ledgerDao.observeAccount(id) }.map { it?.let { row -> toSummary(row) } }

    /** Entries of one account (merged accounts included), newest first. */
    fun entries(accountId: String): Flow<List<LedgerEntry>> =
        canonicalId(accountId).flatMapLatest { id -> ledgerDao.observeEntries(id) }.map { rows -> rows.map { toEntry(it) } }

    /** The canonical id [accountId] resolves to under the user's merges, live. */
    fun canonicalId(accountId: String): Flow<String> = aliasDao.observeAll()
        .map { rows -> AccountAliases(rows.filter { it.same }.associate { it.aliasId to it.canonicalId }).resolve(accountId) }
        .distinctUntilChanged()

    /** Source message of a ledger entry (to open it in its thread). */
    fun messageKeyOf(entry: LedgerEntry): MessageKey? = MessageKey.parse(entry.messageKey)

    fun ledger(accountId: String): Flow<AccountLedger?> =
        canonicalId(accountId).flatMapLatest { id ->
            combine(ledgerDao.observeAccount(id), ledgerDao.observeEntries(id)) { account, entries ->
                account?.let { row -> AccountLedger(toAccount(row), entries.map { e -> toEntry(e) }.sortedBy { e -> e.dateMillis }) }
            }
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
        val id = loadAliases().resolve(accountId)
        ledgerDao.setStatementDay(id, statementDay)
        recompute(listOf(id))
    }

    /** Recomputes the given accounts from their indexed transaction messages (merged accounts included). */
    suspend fun recompute(accountIds: Collection<String>) {
        if (accountIds.isEmpty()) return
        mutex.withLock {
            val aliases = loadAliases()
            recomputeLocked(accountIds, aliases)
        }
    }

    /** Recomputes every account (after a rebuild, restore, rates update or account merge). */
    suspend fun recomputeAll() {
        mutex.withLock {
            val ids = (messageDao.accountIds() + ledgerDao.accountIds()).distinct()
            recomputeLocked(ids, loadAliases())
        }
    }

    private suspend fun recomputeLocked(accountIds: Collection<String>, aliases: AccountAliases) {
        val canonical = LinkedHashSet<String>()
        for (id in accountIds.distinct()) {
            val target = aliases.resolve(id)
            if (target != id) {
                // Merged into another account: its own ledger row goes; the canonical account absorbs its entries.
                db.withTransaction {
                    ledgerDao.deleteEntries(id)
                    ledgerDao.deleteAccount(id)
                }
            }
            canonical += target
        }
        for (id in canonical) recomputeCanonical(id, aliases)
    }

    private suspend fun recomputeCanonical(accountId: String, aliases: AccountAliases) {
        val members = aliases.membersOf(accountId).toList()
        val rows = if (members.size <= 1) messageDao.byAccount(accountId) else messageDao.byAccounts(members)
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
        val built = Ledger.apply(inputs, rates.current(), statementDayFor = { previous?.statementDay }, aliases = aliases)
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

    private suspend fun loadAliases(): AccountAliases =
        AccountAliases(aliasDao.all().filter { it.same }.associate { it.aliasId to it.canonicalId })

    // ---- account aliases (user-confirmed "same account" merges) ----

    /**
     * Probable same-account pairs for the user to confirm ("Is A/c XX40065 the same as XX440065?"), best first.
     * Recomputed whenever accounts or decisions change; pairs already answered are never suggested again.
     */
    fun aliasSuggestions(): Flow<List<AccountAliasSuggestion>> =
        combine(ledgerDao.observeAccounts(), aliasDao.observeAll()) { accounts, decisions -> accounts to decisions }
            .map { (accounts, decisions) -> computeSuggestions(accounts, decisions) }
            .flowOn(Dispatchers.IO)

    /** The user says [aliasId] and [otherId] are one account: merges them (the id showing more digits is kept). */
    suspend fun confirmSameAccount(aliasId: String, otherId: String): String {
        val spans = messageDao.accountSpans().associateBy { it.accountId }
        val canonical = AccountAliases.canonicalOf(aliasId, otherId) { spans[it]?.firstSeen }
        val alias = if (canonical == aliasId) otherId else aliasId
        mergeAccounts(alias, canonical)
        return canonical
    }

    /** The user says the two are different accounts: remembered so the pair is never suggested again. */
    suspend fun confirmDifferentAccounts(a: String, b: String) {
        db.withTransaction {
            aliasDao.deletePair(a, b)
            aliasDao.put(AccountAliasRow(aliasId = minOf(a, b), canonicalId = maxOf(a, b), same = false, decidedAt = System.currentTimeMillis()))
        }
    }

    /** Merges [aliasId] into [intoAccountId] (manual "Merge with…" or a confirmed suggestion) and recomputes both. */
    suspend fun mergeAccounts(aliasId: String, intoAccountId: String) {
        require(aliasId != intoAccountId) { "cannot merge an account into itself" }
        val target = loadAliases().resolve(intoAccountId)
        require(target != aliasId) { "merge would create a cycle" }
        db.withTransaction {
            aliasDao.deleteMergesOf(aliasId)
            aliasDao.deletePair(aliasId, target)
            aliasDao.put(AccountAliasRow(aliasId = aliasId, canonicalId = target, same = true, decidedAt = System.currentTimeMillis()))
        }
        recompute(listOf(aliasId, target))
    }

    /**
     * Undoes a merge of [aliasId]: it becomes its own account again and the pair is remembered as different (so it
     * is not suggested again right away). Returns false when it was not merged.
     */
    suspend fun unmergeAccount(aliasId: String): Boolean {
        val rows = aliasDao.all().filter { it.aliasId == aliasId && it.same }
        if (rows.isEmpty()) return false
        db.withTransaction {
            aliasDao.deleteMergesOf(aliasId)
            for (row in rows) {
                aliasDao.put(AccountAliasRow(aliasId = minOf(aliasId, row.canonicalId), canonicalId = maxOf(aliasId, row.canonicalId), same = false, decidedAt = System.currentTimeMillis()))
            }
        }
        recompute(listOf(aliasId) + rows.map { it.canonicalId })
        return true
    }

    /** Account ids merged into [accountId] (not including itself), live. */
    fun mergedInto(accountId: String): Flow<List<String>> = aliasDao.observeAll().map { rows ->
        val aliases = AccountAliases(rows.filter { it.same }.associate { it.aliasId to it.canonicalId })
        aliases.membersOf(accountId).filter { it != accountId }.sorted()
    }

    /** Accounts [accountId] could be merged with by hand: same institution and kind, not already merged. */
    fun mergeCandidates(accountId: String): Flow<List<AccountSummary>> = accounts().map { list ->
        val self = list.firstOrNull { it.account.id == accountId }?.account ?: return@map emptyList()
        list.filter {
            it.account.id != accountId &&
                it.account.institution.equals(self.institution, ignoreCase = true) &&
                it.account.type == self.type
        }
    }

    /** Newest message of a raw account id (an alias's own messages included), to show as a sample. */
    suspend fun sampleMessageOf(accountId: String): MessageKey? =
        messageDao.latestKeyOfAccount(accountId)?.let { MessageKey(it.kind, it.providerId) }

    private suspend fun computeSuggestions(accounts: List<AccountRow>, decisions: List<AccountAliasRow>): List<AccountAliasSuggestion> {
        if (accounts.size < 2) return emptyList()
        val spans = messageDao.accountSpans().associateBy { it.accountId }
        val observations = accounts.map { row ->
            val account = toAccount(row)
            AccountObservation(
                accountId = row.id,
                institution = row.institution,
                type = account.type,
                // Ids carry every visible digit (see Account.idOf); an account with no number at all never matches.
                visibleDigits = if (row.last4 == null) null else Account.partsOf(row.id)?.third?.takeIf { d -> d.all { it.isDigit() } },
                firstSeenMillis = spans[row.id]?.firstSeen ?: row.lastActivityMillis,
                lastSeenMillis = row.lastActivityMillis,
            )
        }
        val aliases = AccountAliases(decisions.filter { it.same }.associate { it.aliasId to it.canonicalId })
        val decided = decisions.mapTo(HashSet()) { AccountMatcher.pairKey(it.aliasId, it.canonicalId) }
        val raw = AccountMatcher.suggest(observations, decidedPairs = decided, aliases = aliases)
        if (raw.isEmpty()) return emptyList()
        val involved = raw.flatMap { listOf(it.accountA, it.accountB) }.distinct()
        val bodies = involved.flatMap { aliases.membersOf(it) }.distinct()
            .chunked(CHUNK).flatMap { messageDao.bodiesOfAccounts(it, CO_OCCURRENCE_SCAN) }
        val coOccurring = AccountMatcher.coOccurringPairs(observations.filter { it.accountId in involved }, bodies.asSequence())
        val byId = accounts.associateBy { it.id }
        return raw.filter { it.pairKey !in coOccurring }.mapNotNull { s ->
            val a = byId[s.accountA] ?: return@mapNotNull null
            val b = byId[s.accountB] ?: return@mapNotNull null
            AccountAliasSuggestion(
                a = toSummary(a),
                b = toSummary(b),
                reason = s.reason,
                sampleA = sampleMessageOf(a.id),
                sampleB = sampleMessageOf(b.id),
            )
        }
    }

    private object BundledRates : RatesSource {
        private val table: RatesTable by lazy { RatesLoader.loadBundled() }
        override fun current(): RatesTable = table
    }

    internal companion object {
        private const val CHUNK = 300
        private const val CO_OCCURRENCE_SCAN = 200

        fun toAccount(row: AccountRow): Account = Account(
            id = row.id,
            institution = row.institution,
            instrument = row.instrument,
            last4 = row.last4,
            homeCurrency = row.homeCurrency,
            statementDay = row.statementDay,
            maskedNumber = row.maskedNumber,
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
                maskedNumber = ledger.account.maskedNumber,
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
