package app.dak.index.sync

import androidx.room.withTransaction
import app.dak.core.model.Message
import app.dak.core.model.MessageKey
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.MessageFlag
import app.dak.index.enrich.MessageEnricher
import app.dak.index.repo.LedgerRepository
import app.dak.index.signature.AppSignatureRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single write path from provider messages into the index: classify ([MessageEnricher]), parse transactions,
 * compute merge keys / conversation ids, OTP info and consumed-by (via [AppSignatureRegistry]), then upsert the
 * rows (FTS stays in sync through Room's content triggers) and recompute affected ledger accounts.
 *
 * Rows whose body and enricher version are unchanged are only refreshed (box, read state, SIM...), never
 * re-classified, so repeated ingestion of the same messages is cheap and idempotent.
 */
@Singleton
class IndexIngestor @Inject constructor(
    private val db: DakIndexDatabase,
    private val enricher: MessageEnricher,
    private val signatures: AppSignatureRegistry,
    private val ledger: LedgerRepository,
) {
    private val messageDao = db.messageDao()
    private val mergeDao = db.senderMergeDao()

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
        val aliases = mergeDao.aliases().associate { it.address to it.mergeKey }
        val out = ArrayList<IndexedMessage>(messages.size)
        val affectedAccounts = HashSet<String>()
        for (chunk in messages.distinctBy { it.key }.chunked(CHUNK)) {
            val existing = loadExisting(chunk)
            val flags = loadFlags(chunk)
            val now = System.currentTimeMillis()
            val toWrite = ArrayList<IndexedMessage>(chunk.size)
            for (message in chunk) {
                val key = message.key
                val old = existing[key]
                val row = if (!force && old != null && IndexRowMapper.canRefresh(old, message, version)) {
                    val refreshed = IndexRowMapper.refresh(old, message, aliases, now)
                    if (refreshed.copy(indexedAt = old.indexedAt) == old) {
                        out += old
                        continue
                    }
                    refreshed
                } else {
                    val enrichment = enricher.enrich(message, allowCloud)
                    val otp = enrichment.classification.otp
                    val detected = if (otp != null && (otp.retrieverHash != null || otp.webOtpDomain != null)) {
                        signatures.consumerOf(otp, refreshSignaturesOnMiss)
                    } else {
                        null
                    }
                    val consumedBy = detected ?: old?.otpConsumedBy
                    IndexRowMapper.build(message, enrichment, aliases, flags[key], consumedBy, version, now)
                }
                old?.accountId?.let { affectedAccounts += it }
                row.accountId?.let { affectedAccounts += it }
                toWrite += row
                out += row
            }
            if (toWrite.isNotEmpty()) db.withTransaction { write(toWrite) }
        }
        ledger.recompute(affectedAccounts)
        out
    }

    /** Removes rows for messages that no longer exist in the provider (deleted, or moved to the bin). */
    suspend fun remove(keys: Collection<MessageKey>) = withContext(Dispatchers.IO) {
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
        const val CHUNK = 200
    }
}
