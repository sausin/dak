package app.dak.index.bin

import app.dak.core.model.Message
import app.dak.core.model.MessageKey
import app.dak.index.BinItem
import app.dak.index.BinPolicy
import app.dak.index.DefaultBinPolicy
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.IndexJson
import app.dak.index.db.entity.BinEntry
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.repo.AuditLogRepository
import app.dak.index.sync.IndexIngestor
import app.dak.index.sync.IndexRowMapper
import app.dak.search.TextNormalizer
import app.dak.telephony.ProviderReader
import app.dak.telephony.ProviderWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Result of [RecycleBin.moveToBin]: what landed in the bin (pass to [RecycleBin.undo]) and what failed. */
data class BinReceipt(
    val binIds: List<Long>,
    /** Keys that could not be deleted from the provider (e.g. not the default SMS app); nothing was binned. */
    val failed: List<MessageKey>,
)

/**
 * Soft delete. Every deletion first copies the full message (body, attachment metadata, box, date, SIM, category)
 * into the encrypted bin table, *then* removes it from the Telephony provider; restore re-inserts it into its
 * original thread and re-indexes it. Retention comes from the app's [BinPolicy] (default: OTP 1 day, others 30).
 *
 * Undo (the "undo" snackbar after a delete or an automation) is [undo] with the receipt: it restores the entries.
 */
@Singleton
class RecycleBin @Inject constructor(
    db: DakIndexDatabase,
    private val reader: ProviderReader,
    private val writer: ProviderWriter,
    private val ingestor: IndexIngestor,
    private val audit: AuditLogRepository,
    policy: Optional<BinPolicy>,
) {
    private val binDao = db.binDao()
    private val messageDao = db.messageDao()
    private val policy: BinPolicy = policy.orElse(DefaultBinPolicy)

    /** Bin entries, most recently deleted first. */
    fun observe(): Flow<List<BinItem>> = binDao.observeAll().map { rows -> rows.map { toItem(it) } }

    fun count(): Flow<Int> = binDao.observeCount()

    /** Moves messages to the bin: copy first, then delete from the provider, then drop the index rows. */
    suspend fun moveToBin(keys: Collection<MessageKey>, deletedBy: DeletedBy): BinReceipt = withContext(Dispatchers.IO) {
        val binned = ArrayList<Long>()
        val failed = ArrayList<MessageKey>()
        for (key in keys.distinct()) {
            val row = indexedRow(key)
            if (row == null) {
                failed += key
                continue
            }
            val now = System.currentTimeMillis()
            val message = IndexRowMapper.toMessage(row)
            val binId = binDao.insert(toEntry(row, message, deletedBy, now))
            val deleted = runCatching { writer.delete(key) }.getOrDefault(false)
            if (!deleted) {
                binDao.delete(binId)
                failed += key
                continue
            }
            ingestor.remove(listOf(key))
            binned += binId
            audit.log(actorOf(deletedBy), "bin.move", key.toString(), deletedBy.encoded)
        }
        BinReceipt(binned, failed)
    }

    /**
     * Deletes messages for good, skipping the bin (incognito chats: nothing may be left behind to restore). Returns the
     * keys actually deleted from the provider; their index rows (and full-text entries) are dropped too. The audit
     * log records only the key and [reason], never content.
     */
    suspend fun deleteWithoutBin(keys: Collection<MessageKey>, reason: String): List<MessageKey> = withContext(Dispatchers.IO) {
        val deleted = keys.distinct().filter { key -> runCatching { writer.delete(key) }.getOrDefault(false) }
        if (deleted.isNotEmpty()) {
            ingestor.remove(deleted)
            for (key in deleted) audit.log("incognito", "message.vanish", key.toString(), reason)
        }
        deleted
    }

    /** Restores everything a [moveToBin] call binned. Returns the number restored. */
    suspend fun undo(receipt: BinReceipt): Int = receipt.binIds.count { restore(it) != null }

    /**
     * Re-inserts a bin entry into the provider (original thread, box, date, SIM and read state preserved by
     * `ProviderWriter.restore`), re-indexes it and removes it from the bin. Returns the new provider key.
     */
    suspend fun restore(binId: Long): MessageKey? = withContext(Dispatchers.IO) {
        val entry = binDao.get(binId) ?: return@withContext null
        val message = decode(entry) ?: return@withContext null
        val newKey = runCatching { writer.restore(message) }.getOrNull() ?: return@withContext null
        val fresh = runCatching { reader.message(newKey) }.getOrNull()
            ?: message.copy(providerId = newKey.providerId, kind = newKey.kind)
        ingestor.ingest(listOf(fresh))
        binDao.delete(binId)
        audit.log("user", "bin.restore", newKey.toString(), entry.deletedBy)
        newKey
    }

    /** Permanently deletes one entry. */
    suspend fun deleteForever(binId: Long) {
        binDao.delete(binId)
    }

    /** Permanently deletes everything in the bin. */
    suspend fun empty(): Int = binDao.clear().also { audit.log("user", "bin.empty", detail = "$it entries") }

    /** Purges entries whose retention has expired. Called daily by the purge worker. */
    suspend fun purgeExpired(nowMillis: Long = System.currentTimeMillis()): Int = binDao.deleteExpired(nowMillis)

    /** The full message a bin entry holds (for previews or export). */
    suspend fun message(binId: Long): Message? = binDao.get(binId)?.let(::decode)

    private suspend fun indexedRow(key: MessageKey): IndexedMessage? {
        messageDao.get(key.kind.name, key.providerId)?.let { return it }
        // Not indexed yet (backfill still running): read it from the provider and index it now.
        val message = runCatching { reader.message(key) }.getOrNull() ?: return null
        return ingestor.ingest(listOf(message)).firstOrNull()
    }

    private suspend fun toEntry(row: IndexedMessage, message: Message, deletedBy: DeletedBy, now: Long): BinEntry {
        val retention = policy.retentionMillis(row.category)
        return BinEntry(
            kind = row.kind,
            providerId = row.providerId,
            threadId = row.threadId,
            conversationId = row.conversationId,
            subId = row.subId,
            address = row.address,
            mergeKey = row.mergeKey,
            body = row.body,
            dateMillis = row.dateMillis,
            box = row.box,
            read = row.read,
            hasAttachment = row.hasAttachment,
            attachmentsJson = row.attachmentsJson,
            category = row.category,
            otpCode = row.otpCode,
            deletedBy = deletedBy.encoded,
            deletedAt = now,
            purgeAt = BinRetention.purgeAt(now, retention),
            messageJson = IndexJson.json.encodeToString(Message.serializer(), message),
            searchText = TextNormalizer.normalize(row.body + " " + row.address + " " + (row.canonicalSender ?: "")),
        )
    }

    private fun decode(entry: BinEntry): Message? =
        runCatching { IndexJson.json.decodeFromString(Message.serializer(), entry.messageJson) }.getOrNull()

    private fun actorOf(deletedBy: DeletedBy): String = when (deletedBy) {
        DeletedBy.Manual -> "user"
        is DeletedBy.AutoRule -> "rule:${deletedBy.ruleName}"
        is DeletedBy.AutoConsumed, DeletedBy.AutoOtp -> "otp-lifecycle"
    }

    internal companion object {
        fun toItem(entry: BinEntry): BinItem = BinItem(
            id = entry.id,
            originalKey = MessageKey(entry.kind, entry.providerId),
            conversationId = entry.conversationId,
            threadId = entry.threadId,
            address = entry.address,
            body = entry.body,
            dateMillis = entry.dateMillis,
            subId = entry.subId,
            category = entry.category,
            attachments = IndexRowMapper.attachments(entry.attachmentsJson),
            deletedBy = entry.deletedBy,
            deletedAtMillis = entry.deletedAt,
            purgeAtMillis = entry.purgeAt,
        )
    }
}
