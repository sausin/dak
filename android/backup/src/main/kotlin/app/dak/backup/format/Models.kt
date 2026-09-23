package app.dak.backup.format

import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.core.model.NO_SUB_ID
import kotlinx.serialization.Serializable

/**
 * "Dak export format v1" — the open, documented backup/export format. See `FORMAT.md` for the
 * on-disk layout and `dak-export-v1.schema.json` for the JSON Schema of these types.
 */
const val DAK_EXPORT_FORMAT_NAME: String = "dak-export"
const val DAK_EXPORT_FORMAT_VERSION: Int = 1

/** A backed-up attachment, referencing content-addressed bytes under `attachments/<sha256>` in the archive. */
@Serializable
data class AttachmentRecord(
    val mimeType: String,
    val sha256: String,
    val name: String? = null,
    val sizeBytes: Long? = null,
)

/**
 * One exported message: the canonical provider fields from `core-model.Message`, plus enrichment
 * that only Dak's own index knows about. All enrichment fields are optional so the format degrades
 * gracefully for readers that only care about the raw message.
 */
@Serializable
data class MessageRecord(
    val key: String,
    val kind: MessageKind,
    val threadId: Long,
    val address: String,
    val body: String,
    val dateMillis: Long,
    val subId: Int = NO_SUB_ID,
    val box: MessageBox = MessageBox.INBOX,
    val read: Boolean = false,
    val seen: Boolean = false,
    val attachments: List<AttachmentRecord> = emptyList(),
    // Enrichment, all optional:
    val category: Category? = null,
    val labels: Set<String> = emptySet(),
    val starred: Boolean = false,
    val archived: Boolean = false,
) {
    /** Stable content hash used by [app.dak.backup.engine.BackupPlanner] to detect changes across backups. */
    fun contentHash(): String {
        val sb = StringBuilder()
        sb.append(kind).append('\u0001').append(threadId).append('\u0001')
            .append(address).append('\u0001').append(body).append('\u0001')
            .append(dateMillis).append('\u0001').append(subId).append('\u0001')
            .append(box).append('\u0001').append(read).append('\u0001').append(seen).append('\u0001')
            .append(category).append('\u0001').append(labels.sorted().joinToString(",")).append('\u0001')
            .append(starred).append('\u0001').append(archived).append('\u0001')
        for (a in attachments) sb.append(a.sha256).append(':').append(a.mimeType).append(';')
        return Hashing.sha256Hex(sb.toString())
    }
}

/** Per-thread preferences that live outside the provider: reply SIM, pin/mute/archive, bubble colour. */
@Serializable
data class ThreadPrefs(
    val threadId: Long,
    val replySubId: Int? = null,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
    /** ARGB, null = theme default. */
    val bubbleColorArgb: Int? = null,
)

@Serializable
data class ManifestPart(
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class ManifestCounts(
    val messages: Int,
    val threads: Int,
    val attachments: Int,
)

@Serializable
enum class ManifestKind { FULL, INCREMENTAL }

/**
 * `manifest.json`: the trailer entry of a Dak export archive, written last so every part's SHA-256
 * and size are known and verifiable without a second pass.
 */
@Serializable
data class Manifest(
    val format: String = DAK_EXPORT_FORMAT_NAME,
    val version: Int = DAK_EXPORT_FORMAT_VERSION,
    val createdAt: Long,
    val appVersion: String,
    val device: String? = null,
    val counts: ManifestCounts,
    val parts: List<ManifestPart>,
    /** Unique id of this snapshot, used as the chain link for incremental backups. */
    val id: String,
    /** Id of the manifest this one incrementally builds on, null for a full backup. */
    val parentId: String? = null,
    val kind: ManifestKind = ManifestKind.FULL,
    /** Message keys removed since [parentId]'s snapshot; only meaningful when [kind] is INCREMENTAL. */
    val deletedKeys: List<String> = emptyList(),
)

/** Caller-supplied metadata for [app.dak.backup.format.DakExportWriter.finish]. */
data class ManifestMeta(
    val createdAt: Long,
    val appVersion: String,
    val device: String? = null,
    val id: String,
    val parentId: String? = null,
    val kind: ManifestKind = ManifestKind.FULL,
    val deletedKeys: List<String> = emptyList(),
)
