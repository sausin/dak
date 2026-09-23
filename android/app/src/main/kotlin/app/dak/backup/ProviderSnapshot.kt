package app.dak.backup

import android.content.Context
import android.net.Uri
import app.dak.backup.format.AttachmentRecord
import app.dak.backup.format.Hashing
import app.dak.backup.format.MessageRecord
import app.dak.core.model.Message
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.telephony.ProviderReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Every provider message as export records, plus where each attachment's bytes live. */
class SnapshotResult(
    val records: List<MessageRecord>,
    /** Attachment sha256 → content URI to read it from. */
    val attachmentUris: Map<String, String>,
)

/**
 * Reads the Telephony provider (the canonical store) into the open export format's [MessageRecord]s. Backups and
 * exports always come from the provider, never from the derived index, so nothing the index lost can be missing.
 */
@Singleton
class ProviderSnapshot @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: ProviderReader,
) {
    /** Walks the provider newest to oldest; [onProgress] gets (done, total). Hashes attachments when [withAttachments]. */
    suspend fun read(withAttachments: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }): SnapshotResult =
        withContext(Dispatchers.IO) {
            val total = runCatching { reader.totalMessageCount() }.getOrDefault(0)
            val records = ArrayList<MessageRecord>(total.coerceAtLeast(16))
            val uris = HashMap<String, String>()
            forEachMessage { message ->
                val attachments = if (withAttachments) {
                    message.attachments.mapNotNull { a ->
                        val sha = hashOf(a.uri) ?: return@mapNotNull null
                        uris[sha] = a.uri
                        AttachmentRecord(mimeType = a.mimeType, sha256 = sha, name = a.name, sizeBytes = a.sizeBytes)
                    }
                } else {
                    emptyList()
                }
                records += toRecord(message, attachments)
                if (records.size % PROGRESS_EVERY == 0) onProgress(records.size, total)
            }
            onProgress(records.size, maxOf(total, records.size))
            SnapshotResult(records, uris)
        }

    /** Dedupe keys `(kind, address, date, sha256(body))` of every provider message, for additive restore/import. */
    suspend fun existingKeys(): Set<String> = withContext(Dispatchers.IO) {
        val keys = HashSet<String>()
        forEachMessage { keys += dedupeKey(it.kind, it.address, it.dateMillis, Hashing.sha256Hex(it.body)) }
        keys
    }

    /** Opens an attachment's bytes. */
    fun open(uri: String): InputStream? = runCatching { context.contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull()

    private suspend fun forEachMessage(block: (Message) -> Unit) {
        var before = Long.MAX_VALUE
        val seen = HashSet<MessageKey>()
        while (true) {
            val page = reader.messagesBefore(before, PAGE)
            if (page.isEmpty()) break
            var fresh = 0
            for (m in page) if (seen.add(m.key)) { block(m); fresh++ }
            val oldest = page.minOf { it.dateMillis }
            // A full page may have cut through messages sharing the oldest timestamp: ask again from just after it
            // (already-seen ones are skipped). Once a page brings nothing new, step strictly past the boundary.
            before = if (fresh > 0 && page.size >= PAGE) oldest + 1 else oldest
        }
    }

    private fun hashOf(uri: String): String? = open(uri)?.use { Hashing.sha256Hex(it) }

    private fun toRecord(m: Message, attachments: List<AttachmentRecord>) = MessageRecord(
        key = m.key.toString(),
        kind = m.kind,
        threadId = m.threadId,
        address = m.address,
        body = m.body,
        dateMillis = m.dateMillis,
        subId = m.subId,
        box = m.box,
        read = m.read,
        seen = m.seen,
        attachments = attachments,
    )

    companion object {
        private const val PAGE = 500
        private const val PROGRESS_EVERY = 250

        fun dedupeKey(kind: MessageKind, address: String, dateMillis: Long, bodyHash: String): String =
            "${kind.name}|${address.trim()}|$dateMillis|$bodyHash"
    }
}
