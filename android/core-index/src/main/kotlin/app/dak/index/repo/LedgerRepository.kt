package app.dak.index.repo

import androidx.room.withTransaction
import app.dak.classify.scam.ScamLabels
import app.dak.core.model.InstrumentType
import app.dak.core.model.MessageKey
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountAliases
import app.dak.finance.ledger.AccountLedger
import app.dak.finance.ledger.AccountMatcher
import app.dak.finance.ledger.AccountObservation
import app.dak.finance.ledger.AccountType
import app.dak.finance.ledger.AliasReason
import app.dak.finance.ledger.BalanceState
import app.dak.finance.ledger.BillingCycle
import app.dak.finance.ledger.Ledger
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.ledger.LedgerInput
import app.dak.finance.money.Money
import app.dak.finance.passbook.AccountFacts
import app.dak.finance.passbook.AccountGroup
import app.dak.finance.passbook.AccountGroups
import app.dak.finance.passbook.MonthlyTotal
import app.dak.finance.passbook.Passbook
import app.dak.finance.rates.RatesLoader
import app.dak.finance.rates.RatesTable
import app.dak.finance.reconcile.Reconciler
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.AccountAliasRow
import app.dak.index.db.entity.AccountRow
import app.dak.index.db.entity.AccountTypeOverrideRow
import app.dak.index.db.entity.LedgerEntryRow
import app.dak.index.sync.IndexRowMapper
import app.dak.telephony.region.RegionProfile
import app.dak.telephony.region.RegionProvider
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
    /** True when the user set this account's type by hand ([LedgerRepository.setAccountType]). */
    val typeOverridden: Boolean = false,
    /** For an investment account: units / shares held as the latest SMS stating them said (a plain decimal string). */
    val unitsHeld: String? = null,
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
    private val regions: RegionProvider,
) {
    private val ledgerDao = db.ledgerDao()
    private val messageDao = db.messageDao()
    private val aliasDao = db.accountAliasDao()
    private val mutex = Mutex()
    private val rates: RatesSource = ratesSource.orElse(BundledRates)

    fun accounts(): Flow<List<AccountSummary>> =
        combine(ledgerDao.observeAccounts(), overriddenIds()) { rows, overridden -> rows.map { toSummary(it, it.id in overridden) } }
            .flowOn(Dispatchers.Default)

    /** One account; an id merged into another account shows that (canonical) account. */
    fun account(accountId: String): Flow<AccountSummary?> =
        canonicalId(accountId).flatMapLatest { id -> combine(ledgerDao.observeAccount(id), overriddenIds()) { row, o -> row to o } }
            .map { (row, overridden) -> row?.let { toSummary(it, it.id in overridden) } }

    private fun overriddenIds(): Flow<Set<String>> =
        ledgerDao.observeTypeOverrides().map { rows -> rows.mapTo(HashSet()) { it.accountId } }.distinctUntilChanged()

    /**
     * The Passbook's sections: Bank accounts, Credit cards, Debit cards, Wallets, UPI, Prepaid & forex cards, Loans,
     * Investments, Other (empty ones left out), each with header totals per currency (see `AccountGroups`). Each item
     * carries its spend this month (UTC, as of [nowMillis]; own-account and investment transfers excluded), a credit
     * card's cycle outstanding when its statement day is known, and a debit card's / loan's linked bank account when an
     * SMS named one.
     */
    fun accountGroups(nowMillis: Long = System.currentTimeMillis()): Flow<List<AccountGroup<AccountGroupItem>>> =
        combine(accounts(), ledgerDao.observeDebitsSince(AccountGroups.monthStartUtc(nowMillis))) { summaries, debits -> summaries to debits }
            .map { (summaries, debits) ->
                val spend = debits.groupBy { it.accountId }
                    .mapValues { (_, rows) -> AccountGroups.sum(rows.map { Money(it.totalMinor, it.currency) }) }
                val byId = summaries.associateBy { it.account.id }
                val items = summaries.map { s ->
                    val day = s.account.statementDay
                    val outstanding = if (s.account.type == AccountType.CREDIT_CARD && day != null) {
                        AccountLedger(s.account, ledgerDao.entries(s.account.id).map { toEntry(it) }).cardOutstanding(nowMillis, BillingCycle(day))
                    } else {
                        null
                    }
                    AccountGroupItem(s, spend[s.account.id].orEmpty(), outstanding, s.account.linkedAccountId?.let { byId[it] })
                }
                AccountGroups.group(
                    items,
                    facts = { AccountFacts(it.summary.account, it.summary.balance, it.spentThisMonth, it.outstanding) },
                    lastActivity = { it.summary.lastActivityMillis },
                )
            }
            .flowOn(Dispatchers.IO)

    /**
     * Sets [accountId]'s type by hand ("This is a credit card"), or clears it with null (back to the type detected
     * from its SMS). Persisted as user data; the account keeps its id and is recomputed.
     */
    suspend fun setAccountType(accountId: String, instrument: InstrumentType?) {
        val id = loadAliases().resolve(accountId)
        if (instrument == null) {
            ledgerDao.deleteTypeOverride(id)
        } else {
            ledgerDao.putTypeOverride(AccountTypeOverrideRow(id, instrument, System.currentTimeMillis()))
        }
        recompute(listOf(id))
    }

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
        val overrides = ledgerDao.typeOverrides().associate { it.accountId to it.instrument }
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
        // Debit cards and loans first: their SMS can name a bank account, which is then recomputed after them so it
        // picks up the card's postings (and drops them when the link went away).
        val (linkers, others) = canonical.partition { isLinker(it) }
        val done = HashSet<String>()
        val linkedTargets = LinkedHashSet<String>()
        for (id in linkers) {
            linkedTargets += recomputeCanonical(id, aliases, overrides)
            done += id
        }
        for (id in others + linkedTargets.map { aliases.resolve(it) }) {
            if (done.add(id)) recomputeCanonical(id, aliases, overrides)
        }
    }

    /** Whether [accountId]'s id says debit card or loan (the instruments whose SMS may name a bank account). */
    private fun isLinker(accountId: String): Boolean =
        Account.partsOf(accountId)?.second.let { it == InstrumentType.DEBIT_CARD.name || it == InstrumentType.LOAN.name }

    /**
     * Rebuilds one canonical account. Returns the bank account ids linked to it before and after (for a debit card or
     * loan), which the caller recomputes next.
     */
    private suspend fun recomputeCanonical(
        accountId: String,
        aliases: AccountAliases,
        overrides: Map<String, InstrumentType>,
    ): List<String> {
        val members = aliases.membersOf(accountId).toList()
        val ownRows = if (members.size <= 1) messageDao.byAccount(accountId) else messageDao.byAccounts(members)
        // Spends of debit cards / loans whose SMS named this account post here too (Ledger.apply keeps only the
        // postings that resolve to this account).
        val linkers = ledgerDao.accountsLinkedTo(members).flatMap { aliases.membersOf(it) }.distinct().filter { it !in members }
        val rows = if (linkers.isEmpty()) ownRows else ownRows + linkers.chunked(CHUNK).flatMap { messageDao.byAccounts(it) }
        // Likely fake credit alerts never create entries or move balances (docs/security/fake-credit-scams.md).
        val inputs = rows.filterNot { ScamLabels.excludedFromLedger(it.labels) }.mapNotNull { row ->
            IndexRowMapper.transaction(row)?.let { LedgerInput(IndexRowMapper.key(row).toString(), row.dateMillis, it) }
        }
        val previous = ledgerDao.account(accountId)
        val previousLinked = listOfNotNull(previous?.linkedAccountId)
        if (inputs.isEmpty()) {
            db.withTransaction {
                ledgerDao.deleteEntries(accountId)
                ledgerDao.deleteAccount(accountId)
            }
            return previousLinked
        }
        val region = regions.current()
        val built = Ledger.apply(
            inputs,
            rates.current(),
            defaultHomeCurrency = { institution -> defaultHomeCurrency(institution, region) },
            statementDayFor = { previous?.statementDay },
            aliases = aliases,
            instrumentOverride = { overrides[it] },
        )
            .firstOrNull { it.account.id == accountId } ?: return previousLinked
        val reconciled = AccountLedger(built.account, Reconciler.reconcile(built.entries).entries)
        val now = System.currentTimeMillis()
        val accountRow = toAccountRow(reconciled, previous?.statementDay, now)
        val entryRows = reconciled.entries.map { toEntryRow(accountId, it) }
        db.withTransaction {
            ledgerDao.deleteEntries(accountId)
            entryRows.chunked(CHUNK).forEach { ledgerDao.putEntries(it) }
            ledgerDao.putAccount(accountRow)
        }
        return (previousLinked + listOfNotNull(reconciled.account.linkedAccountId)).distinct()
    }

    /**
     * Home currency of an account whose SMS never state a balance: INR for a known Indian institution (wherever the
     * user is now), else the currency of the default SMS SIM's region; null (the ledger then uses the currency most
     * of the account's transactions are in) when the region is unknown.
     */
    private fun defaultHomeCurrency(institution: String?, region: RegionProfile): String? =
        Ledger.institutionHomeCurrency(institution) ?: region.homeCurrency

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
                it.account.type.aliasFamily == self.type.aliasFamily
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

        fun toSummary(row: AccountRow, typeOverridden: Boolean = false): AccountSummary =
            AccountSummary(toAccount(row), balanceOf(row), row.entryCount, row.lastActivityMillis, typeOverridden, row.unitsHeld)

        // Row <-> model mapping lives in the pure LedgerRowMapping (JVM-tested); these keep the existing call sites.
        fun toAccount(row: AccountRow): Account = LedgerRowMapping.toAccount(row)

        fun balanceOf(row: AccountRow): BalanceState = LedgerRowMapping.balanceOf(row)

        fun toAccountRow(ledger: AccountLedger, statementDay: Int?, nowMillis: Long): AccountRow =
            LedgerRowMapping.toAccountRow(ledger, statementDay, nowMillis)

        fun toEntryRow(accountId: String, e: LedgerEntry): LedgerEntryRow = LedgerRowMapping.toEntryRow(accountId, e)

        fun toEntry(row: LedgerEntryRow): LedgerEntry = LedgerRowMapping.toEntry(row)

        const val STATE_KNOWN = LedgerRowMapping.STATE_KNOWN
        const val STATE_UNKNOWN = LedgerRowMapping.STATE_UNKNOWN
        const val STATE_NO_INFO = LedgerRowMapping.STATE_NO_INFO
    }
}
