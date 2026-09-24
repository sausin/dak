package app.dak.index.perf

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.dak.classify.scam.ScamLabels
import app.dak.core.model.Category
import app.dak.core.model.Classification
import app.dak.core.model.ClassifierSource
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.InvestmentAction
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.AccountAliases
import app.dak.finance.ledger.AccountLedger
import app.dak.finance.ledger.Ledger
import app.dak.finance.ledger.LedgerInput
import app.dak.finance.money.Money
import app.dak.finance.rates.RatesLoader
import app.dak.finance.rates.RatesTable
import app.dak.finance.reconcile.Reconciler
import app.dak.index.BackfillReason
import app.dak.index.BackfillStage
import app.dak.index.IndexSchedule
import app.dak.index.crypto.IndexDatabaseFactory
import app.dak.index.crypto.IndexPassphraseStore
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.IndexConverters
import app.dak.index.db.IndexJson
import app.dak.index.db.entity.AccountRow
import app.dak.index.db.entity.BackfillStateRow
import app.dak.index.db.entity.LedgerEntryRow
import app.dak.index.enrich.Enrichment
import app.dak.index.enrich.MessageEnricher
import app.dak.index.repo.AuditLogRepository
import app.dak.index.repo.FoldEngine
import app.dak.index.repo.LedgerRepository
import app.dak.index.repo.LedgerRowMapping
import app.dak.index.signature.AppSignatureRegistry
import app.dak.index.sync.BackfillScheduler
import app.dak.index.sync.IndexIngestor
import app.dak.index.sync.IndexMaintenance
import app.dak.telephony.ProviderReader
import app.dak.telephony.ProviderThread
import app.dak.telephony.region.FixedRegionProvider
import app.dak.telephony.region.RegionProfile
import app.dak.telephony.region.RegionSource
import java.io.Closeable
import java.math.BigDecimal
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/*
 * Shared fixtures of the performance-equivalence tests (docs/performance.md, "Ledger and inbox"): a scripted enricher,
 * a scripted provider, an in-memory index wired exactly as production wires it (ingestor, ledger, stage-2 backfill),
 * cost counters that need no production hook, and an independent reference ledger to compare against.
 */

internal const val DAY_MILLIS = 86_400_000L

/** 2026-01-01T00:00Z. */
internal const val START_MILLIS = 1_767_225_600_000L

/** India, from the SIM: the region the ledger falls back to for a home currency. */
internal val INDIA = RegionProfile(RegionProfile.INDIA, RegionSource.SIM)

/** One provider message and what the (scripted) enricher says about it. */
internal data class CorpusItem(val message: Message, val enrichment: Enrichment)

/**
 * A [MessageEnricher] that answers from a table (by message key), so ledger tests pin the ledger and the index, not
 * the classifier or the transaction parser (both are developed elsewhere). Thread-safe: the ingestor enriches large
 * chunks on several threads.
 */
internal class FakeEnricher(initialVersion: Int = 1) : MessageEnricher {
    private val table = ConcurrentHashMap<MessageKey, Enrichment>()

    @Volatile
    var currentVersion: Int = initialVersion

    override val version: Int get() = currentVersion

    fun put(key: MessageKey, enrichment: Enrichment) {
        table[key] = enrichment
    }

    fun putAll(items: Collection<CorpusItem>) = items.forEach { put(it.message.key, it.enrichment) }

    override suspend fun enrich(message: Message, allowCloud: Boolean): Enrichment =
        table[message.key] ?: Enrichment(Classification.Unclassified, null)
}

/**
 * A provider holding [messages], answering the stage-2 backfill's `messagesBefore` like the real reader (strictly
 * older, newest first). [beforeBatch] runs at the start of every `messagesBefore` call, i.e. between two backfill
 * batches, when no index transaction is open (the cost counters are read there). [failOnCall] makes that call throw,
 * as if the worker were stopped mid-pass.
 */
internal class FakeProviderReader : ProviderReader {
    @Volatile
    var messages: List<Message> = emptyList()
        set(value) {
            field = value.sortedWith(compareByDescending<Message> { it.dateMillis }.thenByDescending { it.providerId })
        }

    var beforeBatch: (suspend () -> Unit)? = null
    var failOnCall: Int = -1
    var calls: Int = 0
        private set

    override suspend fun threads(): List<ProviderThread> = emptyList()

    override suspend fun messagesInThread(threadId: Long, limit: Int, offset: Int): List<Message> = emptyList()

    override suspend fun message(key: MessageKey): Message? = messages.firstOrNull { it.key == key }

    override suspend fun recentMessages(sinceMillis: Long, minCount: Int): List<Message> {
        val recent = messages.filter { it.dateMillis >= sinceMillis }
        return if (recent.size >= minCount) recent else messages.take(minCount)
    }

    override suspend fun messagesBefore(beforeMillis: Long, limit: Int): List<Message> {
        beforeBatch?.invoke()
        calls++
        if (calls == failOnCall) throw IllegalStateException("simulated worker stop at call $calls")
        return messages.filter { it.dateMillis < beforeMillis }.take(limit)
    }

    override suspend fun messagesAfter(smsIdExclusive: Long, mmsIdExclusive: Long, limit: Int): List<Message> = emptyList()

    override suspend fun totalMessageCount(): Int = messages.size

    override suspend fun maxIds(): Pair<Long, Long> = 0L to 0L

    override suspend fun allKeys(): Set<MessageKey> = messages.mapTo(HashSet()) { it.key }
}

/** Ledger work observed through SQLite triggers (see [IndexHarness]); no production hook needed. */
internal data class LedgerCost(
    /** Account ledgers written (`ledger_account` rows put): one per recompute pass of an account that has entries. */
    val accountRebuilds: Int,
    /** Index rows those passes had to read: the account's own, merged and linked-card rows at the time of the pass. */
    val rowsRead: Long,
    /** `ledger_entry` rows inserted. */
    val entryWrites: Long,
    /** `ledger_entry` rows deleted (each pass deletes the account's entries before writing them again). */
    val entryDeletes: Long,
) {
    operator fun plus(o: LedgerCost) =
        LedgerCost(accountRebuilds + o.accountRebuilds, rowsRead + o.rowsRead, entryWrites + o.entryWrites, entryDeletes + o.entryDeletes)

    companion object {
        val ZERO = LedgerCost(0, 0, 0, 0)
    }
}

/** The ledger tables, `updatedAt` zeroed (it is a wall-clock stamp). Keys: account id; (account id, message key). */
internal data class LedgerSnapshot(
    val accounts: Map<String, AccountRow>,
    val entries: Map<Pair<String, String>, LedgerEntryRow>,
    /** Every `ledger_entry` row, so entries left behind for an account with no `ledger_account` row are caught. */
    val entryTableCount: Int,
) {
    /** A readable description of the first differences (for assertion messages). */
    fun diff(other: LedgerSnapshot, max: Int = 15): String {
        val out = ArrayList<String>()
        for (id in (accounts.keys + other.accounts.keys).sorted()) {
            val a = accounts[id]
            val b = other.accounts[id]
            if (a != b) out += "account $id:\n    expected $a\n    actual   $b"
        }
        for (key in (entries.keys + other.entries.keys).sortedWith(compareBy({ it.first }, { it.second }))) {
            val a = entries[key]
            val b = other.entries[key]
            if (a != b) out += "entry $key:\n    expected $a\n    actual   $b"
        }
        if (entryTableCount != other.entryTableCount) out += "ledger_entry rows: expected $entryTableCount, actual ${other.entryTableCount}"
        return if (out.isEmpty()) "no differences" else "${out.size} differences:\n" + out.take(max).joinToString("\n")
    }
}

/**
 * An in-memory index wired like production: [IndexIngestor] (with the scripted [enricher]), [LedgerRepository] (bundled
 * rates, [region]), and [IndexMaintenance] over [reader] for the stage-2 backfill. Cost counters are SQLite triggers on
 * the ledger tables, read through [takeCost].
 */
internal class IndexHarness(val region: RegionProfile = INDIA) : Closeable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: DakIndexDatabase = Room.inMemoryDatabaseBuilder(context, DakIndexDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val enricher = FakeEnricher()
    val reader = FakeProviderReader()
    val ledger = LedgerRepository(db, Optional.empty(), FixedRegionProvider(region))
    val ingestor = IndexIngestor(db, enricher, AppSignatureRegistry(context, db), ledger, FoldEngine(db, enricher))
    val maintenance = IndexMaintenance(
        db = db,
        opened = IndexDatabaseFactory.OpenedIndex(db, IndexDatabaseFactory.DeferredIndexOpen(context, IndexPassphraseStore(context))),
        reader = reader,
        ingestor = ingestor,
        enricher = enricher,
        scheduler = BackfillScheduler(context),
        ledger = ledger,
        audit = AuditLogRepository(db),
    )

    val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    private var lastPutSeq = 0L
    private var lastEntryPuts = 0L
    private var lastEntryDeletes = 0L

    init {
        sql.execSQL("CREATE TABLE perf_account_put (seq INTEGER PRIMARY KEY AUTOINCREMENT, accountId TEXT NOT NULL)")
        sql.execSQL("CREATE TABLE perf_counter (name TEXT NOT NULL PRIMARY KEY, n INTEGER NOT NULL)")
        sql.execSQL("INSERT INTO perf_counter (name, n) VALUES ('entry_put', 0)")
        sql.execSQL("INSERT INTO perf_counter (name, n) VALUES ('entry_delete', 0)")
        sql.execSQL(
            "CREATE TRIGGER perf_account_put_t AFTER INSERT ON ledger_account " +
                "BEGIN INSERT INTO perf_account_put (accountId) VALUES (NEW.id); END",
        )
        sql.execSQL(
            "CREATE TRIGGER perf_entry_put_t AFTER INSERT ON ledger_entry " +
                "BEGIN UPDATE perf_counter SET n = n + 1 WHERE name = 'entry_put'; END",
        )
        sql.execSQL(
            "CREATE TRIGGER perf_entry_delete_t AFTER DELETE ON ledger_entry " +
                "BEGIN UPDATE perf_counter SET n = n + 1 WHERE name = 'entry_delete'; END",
        )
    }

    /** Loads [items] into the provider and the enricher. */
    fun load(items: List<CorpusItem>) {
        enricher.putAll(items)
        reader.messages = items.map { it.message }
    }

    /**
     * Ledger work since the previous call. Call it only while no index transaction is open (between backfill batches,
     * or after a step returned): the rows a pass read are counted as they are now.
     */
    fun takeCost(): LedgerCost {
        val passes = ArrayList<Pair<Long, String>>()
        sql.query("SELECT seq, accountId FROM perf_account_put WHERE seq > ? ORDER BY seq", arrayOf<Any?>(lastPutSeq)).use { c ->
            while (c.moveToNext()) passes += c.getLong(0) to c.getString(1)
        }
        if (passes.isNotEmpty()) lastPutSeq = passes.last().first
        var rows = 0L
        for ((_, id) in passes) {
            rows += longOf(
                "SELECT COUNT(*) FROM indexed_message WHERE accountId = ? " +
                    "OR accountId IN (SELECT aliasId FROM account_alias WHERE canonicalId = ? AND same = 1) " +
                    "OR accountId IN (SELECT id FROM ledger_account WHERE linkedAccountId = ?)",
                id, id, id,
            )
        }
        val puts = longOf("SELECT n FROM perf_counter WHERE name = 'entry_put'")
        val deletes = longOf("SELECT n FROM perf_counter WHERE name = 'entry_delete'")
        val cost = LedgerCost(passes.size, rows, puts - lastEntryPuts, deletes - lastEntryDeletes)
        lastEntryPuts = puts
        lastEntryDeletes = deletes
        return cost
    }

    fun longOf(query: String, vararg args: Any?): Long =
        sql.query(query, arrayOf(*args)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** The ledger tables as they are now. */
    suspend fun ledgerSnapshot(): LedgerSnapshot {
        val dao = db.ledgerDao()
        val accounts = dao.accountIds().associateWith { id -> dao.account(id)!!.copy(updatedAt = 0L) }
        val entries = HashMap<Pair<String, String>, LedgerEntryRow>()
        for (id in accounts.keys) dao.entries(id).forEach { entries[it.accountId to it.messageKey] = it }
        return LedgerSnapshot(accounts, entries, longOf("SELECT COUNT(*) FROM ledger_entry").toInt())
    }

    /** Stage 1 as `IndexMaintenance.runStageOne` does it: the newest 30 days (at least 1,000), in chunks of 200. */
    suspend fun runStageOne(): List<Message> {
        val newest = reader.messages.firstOrNull()?.dateMillis ?: return emptyList()
        val recent = reader.recentMessages(newest - 30 * DAY_MILLIS, 1_000)
        for (chunk in recent.chunked(200)) ingestor.ingest(chunk)
        return recent
    }

    /**
     * Marks a stage-2 pass pending, as stage 1 or `requestReindex` leave it: messages strictly older than [cursorMillis]
     * remain, for [reason].
     */
    suspend fun markStageTwoPending(cursorMillis: Long, reason: BackfillReason) {
        val now = System.currentTimeMillis()
        db.backfillStateDao().put(
            BackfillStateRow(
                stage = BackfillStage.STAGE2.name,
                cursorMillis = cursorMillis,
                total = reader.messages.size,
                schedule = IndexSchedule.NOW.name,
                reason = reason.name,
                enricherVersion = enricher.version,
                startedAt = now,
                updatedAt = now,
                finishedAt = null,
            ),
        )
    }

    /** The full first-run flow: stage 1, then the stage-2 backfill (initial) to the end. */
    suspend fun initialBackfill(): IndexMaintenance.StageTwoOutcome {
        val recent = runStageOne()
        val oldest = recent.minOfOrNull { it.dateMillis } ?: Long.MAX_VALUE
        markStageTwoPending(if (oldest == Long.MAX_VALUE) Long.MAX_VALUE else oldest + 1, BackfillReason.INITIAL)
        return maintenance.runStageTwo(IndexSchedule.NOW) { _, _ -> }
    }

    override fun close() = db.close()
}

/**
 * An independent reference for the ledger tables: rebuilds every account from scratch out of the indexed rows, with
 * the same finance functions production uses (`Ledger.apply`, `Reconciler.reconcile`, `LedgerRowMapping`) but none of
 * `LedgerRepository`'s incremental machinery (affected sets, per-account passes, linked-account ordering, stored
 * state). Whatever order and batching the index was built in, the ledger must end up equal to this.
 *
 * [statementDays] are the user's statement days by canonical account id (user input the tables carry over).
 */
internal object ReferenceLedger {
    private val rates: RatesTable by lazy { RatesLoader.loadBundled() }
    private val converters = IndexConverters()

    private class Src(val key: String, val dateMillis: Long, val accountId: String, val labels: Set<String>, val transactionJson: String?)

    fun compute(harness: IndexHarness, statementDays: Map<String, Int> = emptyMap()): LedgerSnapshot {
        val sql = harness.sql
        val rows = ArrayList<Src>()
        sql.query("SELECT kind, providerId, dateMillis, accountId, labels, transactionJson FROM indexed_message WHERE accountId IS NOT NULL").use { c ->
            while (c.moveToNext()) {
                val key = MessageKey(MessageKind.valueOf(c.getString(0)), c.getLong(1)).toString()
                rows += Src(key, c.getLong(2), c.getString(3), converters.labelsFromJson(c.getString(4)), c.stringOrNull(5))
            }
        }
        val aliasMap = HashMap<String, String>()
        sql.query("SELECT aliasId, canonicalId FROM account_alias WHERE same = 1").use { c ->
            while (c.moveToNext()) aliasMap[c.getString(0)] = c.getString(1)
        }
        val aliases = AccountAliases(aliasMap)
        val overrides = HashMap<String, InstrumentType>()
        sql.query("SELECT accountId, instrument FROM account_type_override").use { c ->
            while (c.moveToNext()) overrides[c.getString(0)] = InstrumentType.valueOf(c.getString(1))
        }
        val byAccount = rows.groupBy { it.accountId }
        fun rowsOf(ids: Collection<String>): List<Src> = ids.flatMap { byAccount[it].orEmpty() }.sortedBy { it.dateMillis }

        fun build(accountId: String, source: List<Src>): AccountLedger? {
            val inputs = source.filterNot { ScamLabels.excludedFromLedger(it.labels) }.mapNotNull { row ->
                val txn = row.transactionJson?.let { json ->
                    runCatching { IndexJson.json.decodeFromString(ExtractedTransaction.serializer(), json) }.getOrNull()
                }
                txn?.let { LedgerInput(row.key, row.dateMillis, it) }
            }
            if (inputs.isEmpty()) return null
            val built = Ledger.apply(
                inputs,
                rates,
                defaultHomeCurrency = { institution -> Ledger.institutionHomeCurrency(institution) ?: harness.region.homeCurrency },
                statementDayFor = { statementDays[accountId] },
                aliases = aliases,
                instrumentOverride = { overrides[it] },
            ).firstOrNull { it.account.id == accountId } ?: return null
            return AccountLedger(built.account, Reconciler.reconcile(built.entries).entries)
        }

        val canonical = rows.map { aliases.resolve(it.accountId) }.toSortedSet()
        // An account's link to a bank account comes from its own SMS only, so one pass over own rows finds every link.
        val linkedTo = HashMap<String, MutableList<String>>()
        for (id in canonical) {
            val linked = build(id, rowsOf(aliases.membersOf(id)))?.account?.linkedAccountId ?: continue
            linkedTo.getOrPut(linked) { ArrayList() } += id
        }
        val accounts = HashMap<String, AccountRow>()
        val entries = HashMap<Pair<String, String>, LedgerEntryRow>()
        for (id in canonical + linkedTo.keys) {
            val members = aliases.membersOf(id)
            val linkers = members.flatMap { linkedTo[it].orEmpty() }.flatMap { aliases.membersOf(it) }.distinct().filter { it !in members }
            val ledger = build(id, rowsOf(members) + rowsOf(linkers)) ?: continue
            accounts[id] = LedgerRowMapping.toAccountRow(ledger, statementDays[id], 0L)
            ledger.entries.forEach { e -> entries[id to e.messageKey] = LedgerRowMapping.toEntryRow(id, e) }
        }
        return LedgerSnapshot(accounts, entries, entries.size)
    }

    private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
}

/**
 * A deterministic, realistic finance corpus: bank, UPI, credit-card (with foreign spends and their settlements),
 * debit-card and loan SMS naming a bank account, a wallet, a mutual-fund folio, a merged alias pair, a foreign bank,
 * an account the user re-typed, own-account transfers, and fake-credit (scam-labelled) alerts, among the usual OTPs,
 * promotions and chats. Every date is distinct (ties would make SQL order, not logic, decide some results).
 */
internal object LedgerCorpus {

    class Acct(
        val institution: String,
        val instrument: InstrumentType,
        val masked: String?,
        val last4: String?,
        val currency: String,
        val address: String,
        val threadId: Long,
        val weight: Int,
        val balance: Boolean,
        val linked: String? = null,
    ) {
        val id: String get() = app.dak.finance.ledger.Account.idFor(institution, instrument, digits)
        private val digits: String?
            get() = masked?.filter { it.isDigit() }?.takeIf { it.length > 4 } ?: last4
    }

    val HDFC = Acct("HDFC Bank", InstrumentType.BANK_ACCOUNT, "XXXX1234", "1234", "INR", "VM-HDFCBK", 1, 18, balance = true)
    val HDFC_UPI = Acct("HDFC Bank", InstrumentType.UPI, null, "1234", "INR", "VM-HDFCBK", 1, 6, balance = false)
    val ICICI_CARD = Acct("ICICI Bank", InstrumentType.CREDIT_CARD, "XX5073", "5073", "INR", "JD-ICICIT", 2, 12, balance = false)
    val SBI_DEBIT = Acct("State Bank of India", InstrumentType.DEBIT_CARD, "XX9876", "9876", "INR", "AD-SBIINB", 3, 8, balance = true, linked = "XXXX5555")
    val SBI_BANK = Acct("State Bank of India", InstrumentType.BANK_ACCOUNT, "XXXX5555", "5555", "INR", "AD-SBIINB", 3, 6, balance = true)
    val AXIS_LOAN = Acct("Axis Bank", InstrumentType.LOAN, "XX7777", "7777", "INR", "VK-AXISBK", 4, 3, balance = false, linked = "XXXX8888")
    val PAYTM = Acct("Paytm", InstrumentType.WALLET, null, null, "INR", "VM-PAYTMB", 5, 5, balance = true)
    val HDFC_WIDE = Acct("HDFC Bank", InstrumentType.BANK_ACCOUNT, "XX440065", "0065", "INR", "VM-HDFCBK", 1, 5, balance = true)
    val HDFC_WIDE_ALIAS = Acct("HDFC Bank", InstrumentType.BANK_ACCOUNT, "XX40065", "0065", "INR", "VM-HDFCBK", 1, 3, balance = true)
    val ENBD = Acct("Emirates NBD", InstrumentType.BANK_ACCOUNT, "XXXX3456", "3456", "AED", "ENBD", 6, 4, balance = true)
    val KOTAK = Acct("Kotak Mahindra Bank", InstrumentType.UNKNOWN, null, "4321", "INR", "VM-KOTAKB", 7, 3, balance = false)
    val FOLIO = Acct("HDFC Mutual Fund", InstrumentType.MUTUAL_FUND, "XXXX6789", "6789", "INR", "VM-HDFCMF", 8, 3, balance = false)
    val CHASE = Acct("Chase", InstrumentType.CREDIT_CARD, null, "1111", "USD", "CHASE", 9, 2, balance = false)

    val ACCOUNTS = listOf(HDFC, HDFC_UPI, ICICI_CARD, SBI_DEBIT, SBI_BANK, AXIS_LOAN, PAYTM, HDFC_WIDE, HDFC_WIDE_ALIAS, ENBD, KOTAK, FOLIO, CHASE)

    /** The bank account Axis's loan SMS name; it has no SMS of its own (it exists only through the link). */
    val AXIS_LINKED_BANK_ID: String = app.dak.finance.ledger.Account.idFor("Axis Bank", InstrumentType.BANK_ACCOUNT, "8888")

    private val MERCHANTS = listOf("AMAZON", "SWIGGY", "ZOMATO", "UBER", "BIGBASKET", "IRCTC", "NETFLIX", "APOLLO", null)
    private val OTHER_SENDERS = listOf("VM-AMAZON", "AD-FLPKRT", "JM-SWIGGY", "VK-JIOINF", "+919812345601", "+919812345602", "+919812345603", "+14155550100")

    /** Pre-existing user data the corpus is meant to be used with: [HDFC_WIDE_ALIAS] merged into [HDFC_WIDE]. */
    val ALIAS_FROM: String get() = HDFC_WIDE_ALIAS.id
    val ALIAS_TO: String get() = HDFC_WIDE.id

    /** Pre-existing user data: Kotak's UNKNOWN account re-typed as a credit card. */
    val OVERRIDE_ID: String get() = KOTAK.id

    private class Draft(val date: Long, val address: String, val threadId: Long, val body: String, val box: MessageBox, val read: Boolean, val enrichment: Enrichment)

    /**
     * [size] messages (plus the settlement SMS their foreign card spends produce), sorted by date, provider ids
     * 1..n in date order. [transactionShare] of them are transactions.
     */
    fun generate(size: Int, seed: Long = 42L, transactionShare: Double = 0.4, slotMillis: Long = 30 * 60_000L): List<CorpusItem> {
        val rnd = Random(seed)
        val rates = RatesLoader.loadBundled()
        val usdInr = rates.rate("USD", "INR") ?: BigDecimal("83")
        val balances = HashMap<Acct, Long>()
        val drafts = ArrayList<Draft>(size + size / 10)
        val totalWeight = ACCOUNTS.sumOf { it.weight }
        for (i in 0 until size) {
            val date = START_MILLIS + i * slotMillis + rnd.nextLong(slotMillis - 1_000)
            if (rnd.nextDouble() >= transactionShare) {
                drafts += other(rnd, date, i)
                continue
            }
            var pick = rnd.nextInt(totalWeight)
            val acct = ACCOUNTS.first { pick -= it.weight; pick < 0 }
            val credit = rnd.nextInt(10) < 3
            val merchant = MERCHANTS[rnd.nextInt(MERCHANTS.size)]
            var currency = acct.currency
            var amount = 100L + rnd.nextLong(2_000_000L)
            var labels = emptySet<String>()
            var linked: String? = null
            var action: InvestmentAction? = null
            var units: String? = null
            var unitPrice: String? = null
            var unitsHeld: String? = null
            var ownTransfer = false
            when (acct) {
                ICICI_CARD -> if (!credit && rnd.nextInt(4) == 0) {
                    // A foreign spend: an unsettled estimate now, the INR settlement SMS one to three days later.
                    currency = "USD"
                    amount = 500L + rnd.nextLong(50_000L)
                    val home = BigDecimal(amount).multiply(usdInr).toLong()
                    val markup = 100 + rnd.nextInt(5)
                    val settledAt = date + DAY_MILLIS + rnd.nextLong(2 * DAY_MILLIS)
                    val settlementTxn = txn(acct, TransactionDirection.DEBIT, home * markup / 100, "INR", merchant, "SET$i", null)
                    drafts += Draft(settledAt, acct.address, acct.threadId, "INR ${home * markup / 100} spent on card ${acct.masked} at $merchant settlement ref SET$i", MessageBox.INBOX, true, transaction(acct, settlementTxn, emptySet()))
                }
                SBI_DEBIT -> linked = if (rnd.nextInt(10) < 7) acct.linked else null
                AXIS_LOAN -> linked = if (rnd.nextInt(10) < 8) acct.linked else null
                FOLIO -> {
                    when (rnd.nextInt(10)) {
                        in 0..6 -> action = InvestmentAction.PURCHASE
                        7, 8 -> action = InvestmentAction.REDEMPTION
                        else -> {
                            action = InvestmentAction.VALUATION
                            amount = 0L
                        }
                    }
                    units = "${rnd.nextInt(500)}.${rnd.nextInt(1000)}"
                    unitPrice = "${10 + rnd.nextInt(90)}.${rnd.nextInt(100)}"
                    unitsHeld = "${1000 + i}.${rnd.nextInt(1000)}"
                }
                HDFC -> {
                    ownTransfer = !credit && rnd.nextInt(20) == 0
                    // Fake credit alerts: likely fakes stay out of the ledger unless the user dismissed the warning.
                    if (credit) {
                        labels = when (rnd.nextInt(100)) {
                            in 0..9 -> setOf(ScamLabels.LIKELY, ScamLabels.REASON_PREFIX + "sender")
                            in 10..13 -> setOf(ScamLabels.LIKELY, ScamLabels.DISMISSED)
                            in 14..17 -> setOf(ScamLabels.SUSPICIOUS)
                            else -> emptySet()
                        }
                    }
                }
                else -> Unit
            }
            val balance = if (acct.balance && (acct != SBI_DEBIT || linked != null) && rnd.nextInt(10) < 7) {
                val next = ((balances[acct] ?: 5_000_000L) + if (credit) amount else -amount).coerceAtLeast(0L)
                balances[acct] = next
                next
            } else {
                null
            }
            val t = txn(acct, if (credit) TransactionDirection.CREDIT else TransactionDirection.DEBIT, amount, currency, merchant, "REF$i", balance)
                .copy(
                    linkedMaskedNumber = linked,
                    investmentAction = action,
                    units = units,
                    unitPrice = unitPrice,
                    unitsHeld = unitsHeld,
                    ownTransfer = ownTransfer,
                    balanceMinor = if (action == InvestmentAction.VALUATION) 1_000_000L + i else balance,
                    balanceCurrency = if (action == InvestmentAction.VALUATION) "INR" else balance?.let { acct.currency },
                )
            val body = "${t.currency} ${t.amountMinor} ${if (credit) "credited to" else "debited from"} ${acct.instrument.name.lowercase()} " +
                "${acct.masked ?: acct.last4 ?: "wallet"} ${merchant ?: ""} ref REF$i"
            drafts += Draft(date, acct.address, acct.threadId, body, MessageBox.INBOX, rnd.nextBoolean(), transaction(acct, t, labels))
        }
        val sorted = drafts.sortedBy { it.date }
        var previous = Long.MIN_VALUE
        return sorted.mapIndexed { index, d ->
            val date = if (d.date <= previous) previous + 1 else d.date
            previous = date
            val message = Message(
                providerId = index + 1L,
                kind = MessageKind.SMS,
                threadId = d.threadId,
                address = d.address,
                body = d.body,
                dateMillis = date,
                subId = 1 + (index % 2),
                box = d.box,
                read = d.read,
                seen = d.read,
            )
            CorpusItem(message, d.enrichment)
        }
    }

    fun txn(acct: Acct, direction: TransactionDirection, amount: Long, currency: String, merchant: String?, reference: String, balance: Long?) =
        ExtractedTransaction(
            direction = direction,
            amountMinor = amount,
            currency = currency,
            instrument = acct.instrument,
            last4 = acct.last4,
            merchant = merchant,
            reference = reference,
            balanceMinor = balance,
            balanceCurrency = balance?.let { acct.currency },
            institution = acct.institution,
            maskedNumber = acct.masked,
        )

    fun transaction(acct: Acct, t: ExtractedTransaction, labels: Set<String>) = Enrichment(
        Classification(Category.TRANSACTION, 0.95f, ClassifierSource.TEMPLATE, canonicalSender = acct.institution, labels = labels),
        t,
    )

    private fun other(rnd: Random, date: Long, i: Int): Draft {
        val sender = OTHER_SENDERS[rnd.nextInt(OTHER_SENDERS.size)]
        val personal = sender.startsWith("+")
        val category = if (personal) Category.PERSONAL else listOf(Category.OTP, Category.PROMOTION, Category.UNKNOWN)[rnd.nextInt(3)]
        val sent = personal && rnd.nextInt(3) == 0
        return Draft(
            date = date,
            address = sender,
            threadId = 100L + OTHER_SENDERS.indexOf(sender),
            body = "message $i from $sender about ${listOf("order", "offer", "code", "dinner", "recharge")[rnd.nextInt(5)]} number ${rnd.nextInt(1_000_000)}",
            box = if (sent) MessageBox.SENT else MessageBox.INBOX,
            read = rnd.nextBoolean(),
            enrichment = Enrichment(Classification(category, 0.9f, ClassifierSource.MODEL, canonicalSender = null), null),
        )
    }

    /** A new corpus item for [acct], dated [date] (for live-ingest tests); provider id [providerId]. */
    fun single(acct: Acct, providerId: Long, date: Long, amount: Long, direction: TransactionDirection, balance: Long?, linked: String? = null): CorpusItem {
        val t = txn(acct, direction, amount, acct.currency, "LIVE", "LIVE$providerId", balance).copy(linkedMaskedNumber = linked)
        val message = Message(
            providerId = providerId,
            kind = MessageKind.SMS,
            threadId = acct.threadId,
            address = acct.address,
            body = "live ${acct.institution} $amount ref LIVE$providerId",
            dateMillis = date,
            subId = 1,
        )
        return CorpusItem(message, transaction(acct, t, emptySet()))
    }

    /** Money helper for assertions. */
    fun inr(minor: Long) = Money(minor, "INR")
}
