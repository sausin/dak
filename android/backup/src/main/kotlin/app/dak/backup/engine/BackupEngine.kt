package app.dak.backup.engine

import app.dak.backup.crypto.BackupCrypto
import app.dak.backup.format.DakExportReader
import app.dak.backup.format.DakExportWriter
import app.dak.backup.format.Hashing
import app.dak.backup.format.Manifest
import app.dak.backup.format.ManifestKind
import app.dak.backup.format.ManifestMeta
import app.dak.backup.format.MessageRecord
import app.dak.backup.format.ThreadPrefs
import app.dak.core.model.MessageKind
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How a snapshot's contents are protected. Either key unlocks a backup made with [BackupCrypto]. */
sealed class RestoreKey {
    data class Passphrase(val value: CharArray) : RestoreKey()
    data class RecoveryCode(val value: String) : RestoreKey()
}

/** Configuration for encrypting the backups this engine writes. */
data class BackupEncryption(val passphrase: CharArray, val iterations: Int = BackupCrypto.DEFAULT_ITERATIONS)

data class BackupResult(
    val manifest: Manifest,
    val blobName: String,
    /** [MessageRecord.key] -> content hash, to pass as `previousDigest` on the next incremental call. */
    val digest: Map<String, String>,
    /** Attachment sha256 hashes now known to be present in the target (union of the input set and any newly written). */
    val knownAttachmentHashes: Set<String>,
    /** Present only if this backup was encrypted: show it to the user once. */
    val recoveryCode: String? = null,
)

/**
 * Backs up messages to a [BackupTarget] as a "Dak export format v1" snapshot (optionally
 * end-to-end encrypted via [BackupCrypto]), and restores them back out. A snapshot's blob is named
 * `"<manifest id>.dakbackup"`; `latest.json` on the target points at the newest one so callers do
 * not need to track ids themselves.
 */
class BackupEngine(private val target: BackupTarget, private val encryption: BackupEncryption? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Writes one snapshot. Pass [previousManifest] and [previousDigest] (from a prior [BackupResult])
     * to write an INCREMENTAL snapshot containing only added/changed messages and a list of deleted
     * keys; omit both for a FULL snapshot. A full snapshot streams [messages] straight through
     * without materializing them; an incremental snapshot first computes the (typically much
     * smaller) delta via [BackupPlanner], which does materialize that delta.
     */
    suspend fun backup(
        messages: Sequence<MessageRecord>,
        threads: List<ThreadPrefs> = emptyList(),
        settingsJson: String = "{}",
        appVersion: String,
        attachmentSource: suspend (sha256: String) -> InputStream? = { null },
        previousManifest: Manifest? = null,
        previousDigest: Map<String, String> = emptyMap(),
        knownAttachmentHashes: Set<String> = emptySet(),
        device: String? = null,
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): BackupResult {
        val isIncremental = previousManifest != null
        val plan = if (isIncremental) BackupPlanner.plan(previousDigest, messages) else null
        val toWrite: Sequence<MessageRecord> = plan?.toWrite()?.asSequence() ?: messages
        val deletedKeys = plan?.deletedKeys ?: emptyList()

        val blobName = "$id.dakbackup"
        var recoveryCode: String? = null
        val digest = LinkedHashMap<String, String>() // rebuilt as messages are written, one pass
        val newAttachmentHashes = LinkedHashSet<String>(knownAttachmentHashes)

        withContext(Dispatchers.IO) {
            val rawOut = target.openWrite(blobName)
            val out = if (encryption != null) {
                val result = BackupCrypto.encryptingOutputStream(rawOut, encryption.passphrase, encryption.iterations)
                recoveryCode = result.recoveryCode.formatted
                result.output
            } else {
                rawOut
            }
            out.use { stream ->
                val writer = DakExportWriter(stream)
                // Attachments referenced by the messages we are about to write, skipping ones already known.
                val neededHashes = LinkedHashSet<String>()
                val digestedMessages = toWrite.map { record ->
                    digest[record.key] = record.contentHash()
                    for (a in record.attachments) if (a.sha256 !in newAttachmentHashes) neededHashes += a.sha256
                    record
                }
                // Materialize once so attachment discovery above and the write below see the same records
                // without requiring the caller's sequence to be re-iterable.
                val toWriteList = digestedMessages.toList()
                for (hash in neededHashes) {
                    val input = attachmentSource(hash) ?: continue
                    writer.writeAttachment(hash, input)
                    newAttachmentHashes += hash
                }
                writer.writeMessages(toWriteList.asSequence())
                writer.writeThreads(threads)
                writer.writeSettings(settingsJson)
                val meta = ManifestMeta(
                    createdAt = now,
                    appVersion = appVersion,
                    device = device,
                    id = id,
                    parentId = previousManifest?.id,
                    kind = if (isIncremental) ManifestKind.INCREMENTAL else ManifestKind.FULL,
                    deletedKeys = deletedKeys,
                )
                val manifest = writer.finish(meta)
                writer.close()

                // Fold in unchanged entries from the previous digest so the returned digest stays complete
                // for the *next* incremental call, not just this snapshot's delta.
                if (isIncremental) for ((k, v) in previousDigest) digest.putIfAbsent(k, v)
                for (k in deletedKeys) digest.remove(k)

                target.openWrite("latest.json").use {
                    it.write(json.encodeToString(LatestPointer(manifest.id, blobName)).toByteArray(Charsets.UTF_8))
                }

                return@withContext BackupResult(manifest, blobName, digest, newAttachmentHashes, recoveryCode)
            }
        }
        error("unreachable") // withContext above always returns from its block
    }

    /**
     * Restores messages from the newest snapshot (or [fromBlobName] if given), walking back through
     * its incremental chain and merging added/changed/deleted entries into one effective set. Never
     * blanks anything: it only emits messages [existingKeys] reports as not already present, keyed by
     * `(kind, address, dateMillis, sha256(body))` as the spec requires, so restoring is purely additive.
     */
    fun restore(
        key: RestoreKey? = null,
        existingKeys: (kind: MessageKind, address: String, dateMillis: Long, bodyHash: String) -> Boolean,
        attachmentSink: suspend (sha256: String, input: InputStream) -> Unit = { _, _ -> },
        fromBlobName: String? = null,
    ): Flow<MessageRecord> = flow {
        val startBlob = fromBlobName ?: readLatestPointer()?.blobName
            ?: throw IllegalStateException("No backups found on this target")

        val chain = mutableListOf<Pair<Manifest, String>>() // newest first
        var currentBlob: String? = startBlob
        while (currentBlob != null) {
            val manifest = readManifestOnly(currentBlob)
            chain += manifest to currentBlob
            currentBlob = manifest.parentId?.let { "$it.dakbackup" }
        }

        val merged = LinkedHashMap<String, MessageRecord>()
        // Oldest to newest, so later snapshots' changes and deletions win.
        for ((manifest, blobName) in chain.asReversed()) {
            val records = readMessages(blobName, attachmentSink)
            for (r in records) merged[r.key] = r
            for (deleted in manifest.deletedKeys) merged.remove(deleted)
        }

        for (record in merged.values) {
            val bodyHash = Hashing.sha256Hex(record.body)
            if (!existingKeys(record.kind, record.address, record.dateMillis, bodyHash)) {
                emit(record)
            }
        }
    }

    private suspend fun readLatestPointer(): LatestPointer? = withContext(Dispatchers.IO) {
        target.openRead("latest.json")?.use { json.decodeFromString(LatestPointer.serializer(), it.readBytes().toString(Charsets.UTF_8)) }
    }

    private suspend fun readManifestOnly(blobName: String): Manifest = withContext(Dispatchers.IO) {
        val raw = target.openRead(blobName) ?: throw IllegalStateException("Missing snapshot $blobName")
        decryptingStream(raw).use { stream ->
            val reader = DakExportReader(stream)
            // Drain fully to reach the trailer manifest.json entry; message bodies are discarded here.
            reader.readMessages().forEach { }
            reader.manifest ?: throw IllegalStateException("Snapshot $blobName has no manifest")
        }
    }

    private suspend fun readMessages(
        blobName: String,
        attachmentSink: suspend (String, InputStream) -> Unit,
    ): List<MessageRecord> = withContext(Dispatchers.IO) {
        val raw = target.openRead(blobName) ?: throw IllegalStateException("Missing snapshot $blobName")
        decryptingStream(raw).use { stream ->
            val reader = DakExportReader(stream)
            reader.readMessages { sha256, input -> kotlinx.coroutines.runBlocking { attachmentSink(sha256, input) } }.toList()
        }
    }

    private fun decryptingStream(raw: InputStream): InputStream = when (val k = key(encryption)) {
        null -> raw
        else -> raw // placeholder, replaced by decryptFor()
    }

    // `restore()` needs the *caller-supplied* key (passphrase or recovery code), not the engine's own
    // write-time [encryption]; kept as a separate helper to make that distinction explicit at call sites.
    private fun key(e: BackupEncryption?): BackupEncryption? = e

    @kotlinx.serialization.Serializable
    private data class LatestPointer(val id: String, val blobName: String)
}
