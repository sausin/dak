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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How an existing snapshot is unlocked for [BackupEngine.restore]. Either key [BackupCrypto] wrapped works. */
sealed class RestoreKey {
    data class Passphrase(val value: CharArray) : RestoreKey()
    data class RecoveryCode(val value: String) : RestoreKey()
}

/** Configuration for encrypting the backups this engine writes. */
data class BackupEncryption(val passphrase: CharArray, val iterations: Int = BackupCrypto.DEFAULT_ITERATIONS)

data class BackupResult(
    val manifest: Manifest,
    val blobName: String,
    /** [MessageRecord.key] -> content hash, covering every live message, to pass as `previousDigest` next time. */
    val digest: Map<String, String>,
    /** Attachment sha256 hashes now known to be present in the target (input set plus any newly written). */
    val knownAttachmentHashes: Set<String>,
    /** Present only when this backup was encrypted: show it to the user once, it is never stored. */
    val recoveryCode: String? = null,
)

/**
 * Backs up messages to a [BackupTarget] as a "Dak export format v1" snapshot (optionally
 * end-to-end encrypted via [BackupCrypto]), and restores them back out. A snapshot's blob is named
 * `"<manifest id>.dakbackup"`; `latest.json` on the target points at the newest one so callers do
 * not need to track ids themselves between calls.
 */
class BackupEngine(private val target: BackupTarget, private val encryption: BackupEncryption? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Writes one snapshot. Pass [previousManifest] and [previousDigest] (from a prior [BackupResult])
     * to write an INCREMENTAL snapshot containing only added/changed messages and a list of deleted
     * keys; omit both for a FULL snapshot. [DakExportWriter] itself streams entry-by-entry into
     * [target], but this call does materialize the message list it writes (the full set for a FULL
     * snapshot, or the — typically much smaller — delta computed by [BackupPlanner] for an
     * INCREMENTAL one) in order to compute the attachment set once before writing.
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
        val toWriteList: List<MessageRecord> = plan?.toWrite() ?: messages.toList()
        val deletedKeys = plan?.deletedKeys ?: emptyList()
        val blobName = "$id.dakbackup"

        return withContext(Dispatchers.IO) {
            var recoveryCode: String? = null
            val digest = LinkedHashMap<String, String>()
            val newAttachmentHashes = LinkedHashSet<String>(knownAttachmentHashes)

            val rawOut = target.openWrite(blobName)
            val out = if (encryption != null) {
                val result = BackupCrypto.encryptingOutputStream(rawOut, encryption.passphrase, encryption.iterations)
                recoveryCode = result.recoveryCode.formatted
                result.output
            } else {
                rawOut
            }
            val manifest = out.use { stream ->
                val writer = DakExportWriter(stream)
                val neededHashes = LinkedHashSet<String>()
                for (record in toWriteList) {
                    digest[record.key] = record.contentHash()
                    for (a in record.attachments) if (a.sha256 !in newAttachmentHashes) neededHashes += a.sha256
                }
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
                val m = writer.finish(meta)
                writer.close()
                m
            }

            // Fold in unchanged/previous entries so the returned digest is complete for the *next*
            // incremental call, not just this snapshot's delta, then drop anything just deleted.
            if (isIncremental) for ((k, v) in previousDigest) digest.putIfAbsent(k, v)
            for (k in deletedKeys) digest.remove(k)

            target.openWrite("latest.json").use {
                it.write(json.encodeToString(LatestPointer(manifest.id, blobName)).toByteArray(Charsets.UTF_8))
            }

            BackupResult(manifest, blobName, digest, newAttachmentHashes, recoveryCode)
        }
    }

    /**
     * Restores messages from the newest snapshot (or [fromBlobName] if given), walking back through
     * its incremental chain and merging added/changed/deleted entries into one effective set. Never
     * blanks anything: it only emits messages [existingKeys] reports as not already present, keyed by
     * `(kind, address, dateMillis, sha256(body))` as the spec requires, so restoring is purely additive.
     * [key] must match how the backup was written (null for an unencrypted backup).
     */
    fun restore(
        key: RestoreKey? = null,
        existingKeys: (kind: MessageKind, address: String, dateMillis: Long, bodyHash: String) -> Boolean,
        attachmentSink: (sha256: String, input: InputStream) -> Unit = { _, _ -> },
        fromBlobName: String? = null,
    ): Flow<MessageRecord> = flow {
        val startBlob = fromBlobName ?: readLatestPointer()?.blobName
            ?: throw IllegalStateException("No backups found on this target")

        val chain = mutableListOf<Pair<Manifest, String>>() // newest first
        var currentBlob: String? = startBlob
        while (currentBlob != null) {
            val manifest = readManifestOnly(currentBlob, key)
            chain += manifest to currentBlob
            currentBlob = manifest.parentId?.let { "$it.dakbackup" }
        }

        val merged = LinkedHashMap<String, MessageRecord>()
        // Oldest to newest, so later snapshots' changes and deletions win.
        for ((manifest, blobName) in chain.asReversed()) {
            for (r in readMessages(blobName, key, attachmentSink)) merged[r.key] = r
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
        target.openRead("latest.json")?.use { json.decodeFromString<LatestPointer>(it.readBytes().toString(Charsets.UTF_8)) }
    }

    private suspend fun readManifestOnly(blobName: String, key: RestoreKey?): Manifest = withContext(Dispatchers.IO) {
        val raw = target.openRead(blobName) ?: throw IllegalStateException("Missing snapshot $blobName")
        decryptingStream(raw, key).use { stream ->
            val reader = DakExportReader(stream)
            reader.readMessages().forEach { } // drain to reach the trailer manifest.json entry
            reader.manifest ?: throw IllegalStateException("Snapshot $blobName has no manifest")
        }
    }

    private suspend fun readMessages(
        blobName: String,
        key: RestoreKey?,
        attachmentSink: (String, InputStream) -> Unit,
    ): List<MessageRecord> = withContext(Dispatchers.IO) {
        val raw = target.openRead(blobName) ?: throw IllegalStateException("Missing snapshot $blobName")
        decryptingStream(raw, key).use { stream ->
            DakExportReader(stream).readMessages(attachmentSink).toList()
        }
    }

    private fun decryptingStream(raw: InputStream, key: RestoreKey?): InputStream = when (key) {
        null -> raw
        is RestoreKey.Passphrase -> BackupCrypto.decryptingInputStream(raw, key.value)
        is RestoreKey.RecoveryCode -> BackupCrypto.decryptingInputStreamWithRecoveryCode(raw, key.value)
    }

    @Serializable
    private data class LatestPointer(val id: String, val blobName: String)
}
