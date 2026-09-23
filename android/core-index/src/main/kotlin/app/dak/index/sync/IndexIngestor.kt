package app.dak.index.sync

import androidx.room.withTransaction
import app.dak.core.model.Message
import app.dak.core.model.MessageKey
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.MessageFlag
import app.dak.index.enrich.Enrichment
import app.dak.index.enrich.MessageEnricher
import app.dak.index.enrich.RepeatRules
import app.dak.index.repo.FoldEngine
import app.dak.index.repo.LedgerRepository
import app.dak.index.signature.AppSignatureRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single write path from provider messages into the index: classify ([MessageEnricher]), parse transactions,
 * compute merge keys / conversation ids, OTP info and consumed-by (via [AppSignatureRegistry]), then upsert the
 * rows (FTS stays in sync through Room's content triggers) and recompute affected ledger accounts.
 *
 * Rows whose body and enricher version are unchanged are only refreshed (box, read state, SIM...), never
 * re-classified, so repeated ingestion of the same messages is cheap and idempotent.
 *
 * Throughput (docs/performance.md): a chunk of up to [CHUNK] messages is classified on a bounded pool
 * ([ENRICH_PARALLELISM] threads, at most 3, leaving a core free) and written by this single coroutine in ONE
 * transaction (rows, fold moves and repeat groups), so a 500-row backfill batch costs one commit instead of hundreds.
 * The coroutine yields between chunks so cancellation and other work get in; CPU bursts stay one chunk long.
 */
@Singleton
class IndexIngestor @Inject constructor(
    private val db: DakIndexDatabase,
    private val enricher: MessageEnricher,
    private val signatures: AppSignatureRegistry,
    private val ledger: LedgerRepository,
    private val folds: FoldEngine,
) {
    private val messageDao = db.messageDao()

    /**
     * Indexes [messages] and returns the resulting rows (in input order, duplicates collapsed).
     *
     * @param allowCloud permit the opt-in cloud classification stage (single incoming messages only).
     * @param refreshSignaturesOnMiss recompute app hashes when an OTP's retriever hash is unknown.
     * @param force re-enrich even rows that are up to date.
     */
    suspend fun ingest(
        messages: List<Message>,
        allowCloud: Boolean = false,
        refreshSignaturesOnMiss: Boolean = false,
        force: Boolean = false,
    ): List<IndexedMessage> = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext emptyList()
        val version = enricher.version
        val rules = folds.rules()
        val out = ArrayList<IndexedMessage>(messages.size)
        val affectedAccounts = HashSet<String>()
        for (chunk in messages.distinctBy { it.key }.chunked(CHUNK)) {
            val existing = loadExisting(chunk)
            val flags = loadFlags(chunk)
            val now = System.currentTimeMillis()
            val toWrite = ArrayList<IndexedMessage>(chunk.size)
            val refreshable = chunk.mapNotNullTo(HashSet()) { message ->
                val old = existing[message.key]
                message.key.takeIf { !force && old != null && IndexRowMapper.canRefresh(old, message, version) }
            }
            val enrichments = enrichAll(chunk.filterNot { it.key in refreshable }, allowCloud)
            for (message in chunk) {
                val key = message.key
                val old = existing[key]
                val row = if (old != null && key in refreshable) {
                    val refreshed = IndexRowMapper.refresh(old, message, rules, now)
                    if (refreshed.copy(indexedAt = old.indexedAt) == old) {
                        out += old
                        continue
                    }
                    refreshed
                } else {
                    val enrichment = enrichments.getValue(key)
                    val otp = enrichment.classification.otp
                    val detected = if (otp != null && (otp.retrieverHash != null || otp.webOtpDomain != null)) {
                        signatures.consumerOf(otp, refreshSignaturesOnMiss)
                    } else {
                        null
                    }
                    val consumedBy = detected ?: old?.otpConsumedBy
                    IndexRowMapper.build(message, enrichment, rules, flags[key], consumedBy, version, now, old?.repeatGroup)
                }
                old?.accountId?.let { affectedAccounts += it }
                row.accountId?.let { affectedAccounts += it }
                toWrite += row
                out += row
            }
            if (toWrite.isNotEmpty()) {
                val moves = HashMap<String, String>()
                for (row in toWrite) {
                    val old = existing[MessageKey(row.kind, row.providerId)] ?: continue
                    if (old.conversationId != row.conversationId) moves[old.conversationId] = row.conversationId
                }
                db.withTransaction {
                    write(toWrite)
                    folds.recordMoves(moves)
                    markRepeats(toWrite)
                }
            }
            yield()
        }
        ledger.recompute(affectedAccounts)
        out
    }

    /**
     * Enriches [messages] (keyed by message key). Large lists are split into [ENRICH_PARALLELISM] slices classified
     * concurrently on [enrichDispatcher]; small ones (a single incoming message, a refresh) run inline. The enricher
     * is required to be safe for concurrent calls ([MessageEnricher.enrich]).
     */
    private suspend fun enrichAll(messages: List<Message>, allowCloud: Boolean): Map<MessageKey, Enrichment> {
        if (messages.isEmpty()) return emptyMap()
        if (messages.size < PARALLEL_MIN_BATCH || ENRICH_PARALLELISM == 1) {
            return messages.associate { it.key to enricher.enrich(it, allowCloud) }
        }
        val sliceSize = (messages.size + ENRICH_PARALLELISM - 1) / ENRICH_PARALLELISM
        val results = coroutineScope {
            messages.chunked(sliceSize).map { slice ->
                async(enrichDispatcher) { slice.map { it.key to enricher.enrich(it, allowCloud) } }
            }.awaitAll()
        }
        return results.flatten().toMap()
    }

    /** Removes rows for messages that no longer exist in the provider (deleted, or moved to the bin). */
    suspend fun remove(keys: Collection<MessageKey>): Unit = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext
        val affectedAccounts = HashSet<String>()
        for ((kind, group) in keys.groupBy { it.kind }) {
            for (ids in group.map { it.providerId }.distinct().chunked(CHUNK)) {
                messageDao.getAll(kind.name, ids).mapNotNullTo(affectedAccounts) { it.accountId }
                db.withTransaction {
                    messageDao.deleteAll(kind.name, ids)
                    messageDao.deleteFlags(kind.name, ids)
                }
            }
        }
        ledger.recompute(affectedAccounts)
    }

    /**
     * Joins each eligible new row to a repeat group (see [RepeatRules]): finds the closest copy in its conversation
     * and shares (or starts) that copy's group. Rows are already written, so copies within one batch find each other;
     * the backfill runs newest to oldest, so the search looks both ways in time. Called inside the chunk's write
     * transaction, so later rows see the groups set for earlier ones and all updates share one commit.
     */
    private suspend fun markRepeats(rows: List<IndexedMessage>) {
        for (row in rows) {
            if (row.repeatGroup != null || !RepeatRules.eligible(row.box, row.category, row.body)) continue
            val copy = messageDao.repeatCandidate(
                conversationId = row.conversationId,
                kind = row.kind.name,
                providerId = row.providerId,
                body = row.body,
                otpCode = row.otpCode,
                dateMillis = row.dateMillis,
                fromMillis = row.dateMillis - RepeatRules.EXACT_WINDOW_MILLIS,
                toMillis = row.dateMillis + RepeatRules.EXACT_WINDOW_MILLIS,
                otpWindowMillis = RepeatRules.OTP_WINDOW_MILLIS,
            ) ?: continue
            if (!RepeatRules.eligible(copy.box, copy.category, copy.body)) continue
            val group = copy.repeatGroup
                ?: RepeatRules.groupKeyOf(MessageKey(row.kind, row.providerId), row.dateMillis, MessageKey(copy.kind, copy.providerId), copy.dateMillis)
            messageDao.setRepeatGroup(row.kind.name, row.providerId, group)
            if (copy.repeatGroup == null) messageDao.setRepeatGroup(copy.kind.name, copy.providerId, group)
        }
    }

    /** Insert-or-update without REPLACE, so FTS content triggers see UPDATEs and stay consistent. */
    private suspend fun write(rows: List<IndexedMessage>) {
        val ids = messageDao.insertIgnore(rows)
        val conflicts = rows.filterIndexed { i, _ -> ids.getOrElse(i) { -1L } == -1L }
        if (conflicts.isNotEmpty()) messageDao.update(conflicts)
    }

    private suspend fun loadExisting(chunk: List<Message>): Map<MessageKey, IndexedMessage> {
        val result = HashMap<MessageKey, IndexedMessage>(chunk.size)
        for ((kind, group) in chunk.groupBy { it.kind }) {
            messageDao.getAll(kind.name, group.map { it.providerId }).forEach { result[MessageKey(kind, it.providerId)] = it }
        }
        return result
    }

    private suspend fun loadFlags(chunk: List<Message>): Map<MessageKey, MessageFlag> {
        val result = HashMap<MessageKey, MessageFlag>()
        for ((kind, group) in chunk.groupBy { it.kind }) {
            messageDao.flags(kind.name, group.map { it.providerId }).forEach { result[MessageKey(kind, it.providerId)] = it }
        }
        return result
    }

    private companion object {
        /** Rows per write transaction (the stage-2 backfill hands over batches of the same size). */
        const val CHUNK = 500

        /** Below this many messages to classify, parallelism is not worth a thread hop. */
        const val PARALLEL_MIN_BATCH = 64

        /** Classifier threads: at most 3, and always one core left for the UI / the system. */
        val ENRICH_PARALLELISM: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3)

        /** Bounded view of the shared Default pool: never more than [ENRICH_PARALLELISM] classifier threads at once. */
        val enrichDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(ENRICH_PARALLELISM, "dak-enrich")
    }
}
