package app.dak.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.dak.BuildConfig
import app.dak.backup.crypto.BackupCrypto
import app.dak.backup.crypto.BackupCryptoException
import app.dak.backup.crypto.MalformedHeaderException
import app.dak.backup.crypto.RecoveryCodeMismatchException
import app.dak.backup.crypto.TamperedException
import app.dak.backup.crypto.WrongPassphraseException
import app.dak.backup.engine.BackupEncryption
import app.dak.backup.engine.BackupEngine
import app.dak.backup.engine.BackupTarget
import app.dak.backup.engine.RestoreKey
import app.dak.backup.format.DakExportReader
import app.dak.backup.format.DakExportWriter
import app.dak.backup.format.Hashing
import app.dak.backup.format.ManifestKind
import app.dak.backup.format.ManifestMeta
import app.dak.backup.format.MessageRecord
import app.dak.backup.importers.DetectedImporter
import app.dak.backup.importers.ImportDetector
import app.dak.backup.importers.ImportedMessage
import app.dak.backup.xml.SmsBackupRestoreXmlExporter
import app.dak.core.model.Attachment
import app.dak.core.model.Message
import app.dak.di.ApplicationScope
import app.dak.index.BackfillReason
import app.dak.index.sync.IndexMaintenance
import app.dak.index.repo.SenderMergeRepository
import app.dak.settings.SettingsStore
import app.dak.telephony.ProviderWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Which long-running backup operation a state refers to. */
enum class BackupOpKind { BACKUP, RESTORE, EXPORT_DAK, EXPORT_XML, IMPORT }

/** Why an operation failed, for a specific message on screen. */
enum class BackupFailure { NO_DESTINATION, NO_PASSPHRASE, WRONG_KEY, DAMAGED, NO_BACKUP_FOUND, UNRECOGNISED_FILE, IO }

/** Progress and outcome of the current or last operation. */
sealed interface BackupOperation {
    data object Idle : BackupOperation
    data class Running(val kind: BackupOpKind, val done: Int, val total: Int) : BackupOperation

    /**
     * [count] messages written (backup/export) or added (restore/import); [skipped] were already present or unreadable.
     * [recoveryCode] is set once, after a full encrypted backup: it is shown to the user and never stored.
     */
    data class Finished(
        val kind: BackupOpKind,
        val count: Int,
        val skipped: Int = 0,
        val recoveryCode: String? = null,
        val restoredUpToMillis: Long? = null,
    ) : BackupOperation

    data class Failed(val kind: BackupOpKind, val failure: BackupFailure, val detail: String? = null) : BackupOperation
}

/** Export file formats. */
enum class ExportFormat { DAK, SMS_BACKUP_RESTORE_XML }

/**
 * Backup, restore, export and import use cases. Everything reads from and writes to the Telephony provider (the
 * canonical store): restore and import are purely additive via `ProviderWriter.restore` (never deleting or
 * blanking anything), then ask the index to re-index. Operations run on the application scope, so leaving the
 * screen does not cancel them; [operation] reports progress.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshot: ProviderSnapshot,
    private val writer: ProviderWriter,
    private val stateStore: BackupStateStore,
    private val vault: BackupPassphraseVault,
    private val settings: SettingsStore,
    private val senderGroups: SenderMergeRepository,
    private val maintenance: IndexMaintenance,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<BackupOperation>(BackupOperation.Idle)
    private val json = Json { ignoreUnknownKeys = true }

    val operation: StateFlow<BackupOperation> = state.asStateFlow()
    val status: StateFlow<BackupStatus> get() = stateStore.status

    fun hasPassphrase(): Boolean = vault.hasPassphrase()

    /** Sets (or changes) the passphrase; the next backup is a fresh full snapshot with a new recovery code. */
    suspend fun setPassphrase(passphrase: CharArray) {
        withContext(Dispatchers.IO) { vault.store(passphrase) }
        stateStore.resetChain()
    }

    suspend fun setDestination(treeUri: Uri) {
        stateStore.setDestination(treeUri, labelOf(treeUri))
    }

    fun clearResult() {
        if (state.value !is BackupOperation.Running) state.value = BackupOperation.Idle
    }

    fun startBackup() = launchOp { backupNow() }

    fun startRestore(key: RestoreKey) = launchOp { restore(key) }

    fun startExport(uri: Uri, format: ExportFormat) = launchOp { export(uri, format) }

    fun startImport(uri: Uri) = launchOp { import(uri) }

    /** Runs one encrypted backup to the chosen folder (incremental after the first). Used by the worker too. */
    suspend fun backupNow(): BackupOperation = mutex.withLock {
        val kind = BackupOpKind.BACKUP
        val persisted = stateStore.current()
        val tree = persisted.treeUri ?: return@withLock fail(kind, BackupFailure.NO_DESTINATION)
        val passphrase = withContext(Dispatchers.IO) { vault.load() } ?: return@withLock fail(kind, BackupFailure.NO_PASSPHRASE)
        try {
            state.value = BackupOperation.Running(kind, 0, 0)
            val target = SafBackupTarget(context, Uri.parse(tree))
            val snap = snapshot.read(withAttachments = true) { done, total -> state.value = BackupOperation.Running(kind, done, total) }
            val full = persisted.previousManifest == null || persisted.snapshotsSinceFull >= MAX_INCREMENTALS
            val result = BackupEngine(target, BackupEncryption(passphrase)).backup(
                messages = snap.records.asSequence(),
                settingsJson = settingsWithFolds(),
                appVersion = BuildConfig.VERSION_NAME,
                attachmentSource = { sha -> snap.attachmentUris[sha]?.let { snapshot.open(it) } },
                previousManifest = if (full) null else persisted.previousManifest,
                previousDigest = if (full) emptyMap() else persisted.previousDigest,
                knownAttachmentHashes = if (full) emptySet() else persisted.knownAttachmentHashes,
                device = Build.MODEL,
            )
            stateStore.recordSuccess(result.manifest, result.digest, result.knownAttachmentHashes, snap.records.size, full)
            finish(BackupOperation.Finished(kind, snap.records.size, recoveryCode = if (full) result.recoveryCode else null))
        } catch (e: Exception) {
            stateStore.recordFailure(e.message ?: e::class.java.simpleName)
            fail(kind, failureOf(e), e.message)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /**
     * Restores the newest backup additively into the provider. A recovery code unlocks the full snapshot it was
     * issued with (each full backup gets its own code), so with a code the restore covers messages up to that
     * snapshot; the passphrase unlocks the whole incremental chain.
     */
    suspend fun restore(key: RestoreKey): BackupOperation = mutex.withLock {
        val kind = BackupOpKind.RESTORE
        val tree = stateStore.current().treeUri ?: return@withLock fail(kind, BackupFailure.NO_DESTINATION)
        val target = SafBackupTarget(context, Uri.parse(tree))
        val tempDir = File(context.cacheDir, "restore-attachments").apply { mkdirs() }
        try {
            state.value = BackupOperation.Running(kind, 0, 0)
            val existing = snapshot.existingKeys()
            val blobs = candidateBlobs(target, key)
            if (blobs.isEmpty()) return@withLock fail(kind, BackupFailure.NO_BACKUP_FOUND)
            var lastError: Exception? = null
            for (blob in blobs) {
                try {
                    val outcome = restoreFrom(target, blob, key, existing, tempDir)
                    restoreSettings(target, blob, key)
                    maintenance.requestReindex(BackfillReason.RESTORE)
                    return@withLock finish(outcome)
                } catch (e: RecoveryCodeMismatchException) {
                    lastError = e // try the next snapshot: this code belongs to another full backup
                }
            }
            fail(kind, BackupFailure.WRONG_KEY, lastError?.message)
        } catch (e: Exception) {
            fail(kind, failureOf(e), e.message)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Writes an unencrypted export (open Dak format or SMS Backup & Restore XML) to a document the user created. */
    suspend fun export(uri: Uri, format: ExportFormat): BackupOperation = mutex.withLock {
        val kind = if (format == ExportFormat.DAK) BackupOpKind.EXPORT_DAK else BackupOpKind.EXPORT_XML
        try {
            state.value = BackupOperation.Running(kind, 0, 0)
            val snap = snapshot.read(withAttachments = true) { done, total -> state.value = BackupOperation.Running(kind, done, total) }
            val settingsJson = settingsWithFolds()
            withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write to the chosen file")
                out.use { stream ->
                    when (format) {
                        ExportFormat.DAK -> DakExportWriter(stream).use { w ->
                            for ((sha, attachmentUri) in snap.attachmentUris) {
                                snapshot.open(attachmentUri)?.let { w.writeAttachment(sha, it) }
                            }
                            w.writeMessages(snap.records.asSequence())
                            w.writeThreads(emptyList())
                            w.writeSettings(settingsJson)
                            w.finish(
                                ManifestMeta(
                                    createdAt = System.currentTimeMillis(),
                                    appVersion = BuildConfig.VERSION_NAME,
                                    device = Build.MODEL,
                                    id = UUID.randomUUID().toString(),
                                    kind = ManifestKind.FULL,
                                ),
                            )
                        }
                        ExportFormat.SMS_BACKUP_RESTORE_XML -> SmsBackupRestoreXmlExporter.write(
                            output = stream,
                            messages = snap.records.asSequence(),
                            count = snap.records.size,
                            attachmentBytes = { sha -> snap.attachmentUris[sha]?.let { u -> snapshot.open(u)?.use { it.readBytes() } } },
                        )
                    }
                }
            }
            finish(BackupOperation.Finished(kind, snap.records.size))
        } catch (e: Exception) {
            fail(kind, failureOf(e), e.message)
        }
    }

    /** Imports an SMS Backup & Restore XML, Fossify Messages or SMS Organizer file, additively. */
    suspend fun import(uri: Uri): BackupOperation = mutex.withLock {
        val kind = BackupOpKind.IMPORT
        val tempDir = File(context.cacheDir, "import-attachments").apply { mkdirs() }
        try {
            state.value = BackupOperation.Running(kind, 0, 0)
            val name = labelOf(uri)
            val header = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { readHeader(it) } ?: throw IOException("Cannot open the file")
            }
            val detected = ImportDetector.detectOrFallback(header, name) ?: return@withLock fail(kind, BackupFailure.UNRECOGNISED_FILE)
            val existing = snapshot.existingKeys().toHashSet()
            var added = 0
            var skipped = 0
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
                val messages: Sequence<ImportedMessage> = when (detected) {
                    is DetectedImporter.Specific -> detected.importer.import(input)
                    is DetectedImporter.SmsOrganizer -> input.use { detected.importer.import(it, name).messages.asSequence() }
                }
                input.use {
                    for (m in messages) {
                        val dedupe = ProviderSnapshot.dedupeKey(m.kind, m.address, m.dateMillis, Hashing.sha256Hex(m.body))
                        if (dedupe in existing) {
                            skipped++
                        } else if (insert(toMessage(m, tempDir)) != null) {
                            existing += dedupe
                            added++
                        } else {
                            skipped++
                        }
                        if ((added + skipped) % PROGRESS_EVERY == 0) state.value = BackupOperation.Running(kind, added + skipped, 0)
                    }
                }
            }
            if (added > 0) maintenance.requestReindex(BackfillReason.RESTORE)
            finish(BackupOperation.Finished(kind, added, skipped))
        } catch (e: Exception) {
            fail(kind, failureOf(e), e.message)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun restoreFrom(
        target: BackupTarget,
        blob: String?,
        key: RestoreKey,
        existing: Set<String>,
        tempDir: File,
    ): BackupOperation.Finished {
        var added = 0
        var skipped = 0
        var newest = 0L
        val files = HashMap<String, File>()
        BackupEngine(target).restore(
            key = key,
            existingKeys = { kind, address, date, bodyHash -> ProviderSnapshot.dedupeKey(kind, address, date, bodyHash) in existing },
            attachmentSink = { sha, input ->
                val file = File(tempDir, sha)
                file.outputStream().use { input.copyTo(it) }
                files[sha] = file
            },
            fromBlobName = blob,
        ).collect { record ->
            newest = maxOf(newest, record.dateMillis)
            if (insert(toMessage(record, files)) != null) added++ else skipped++
            if ((added + skipped) % PROGRESS_EVERY == 0) state.value = BackupOperation.Running(BackupOpKind.RESTORE, added + skipped, 0)
        }
        return BackupOperation.Finished(BackupOpKind.RESTORE, added, skipped, restoredUpToMillis = newest.takeIf { it > 0 })
    }

    /**
     * The settings JSON plus the user's sender fold rules and group names under [FOLDS_KEY] (they live in the
     * index, which is otherwise rebuildable and not exported).
     */
    private suspend fun settingsWithFolds(): String {
        val base = settings.export()
        val folds = runCatching { senderGroups.exportRules() }.getOrNull() ?: return base
        val obj = runCatching { json.parseToJsonElement(base) as? JsonObject }.getOrNull() ?: return base
        return json.encodeToString(JsonObject.serializer(), JsonObject(obj + (FOLDS_KEY to JsonPrimitive(folds))))
    }

    /** Restores the fold rules saved by [settingsWithFolds], if present. Best effort. */
    private suspend fun importFolds(settingsJson: String) {
        val folds = runCatching { (json.parseToJsonElement(settingsJson) as? JsonObject)?.get(FOLDS_KEY) as? JsonPrimitive }.getOrNull()
        folds?.content?.let { runCatching { senderGroups.importRules(it) } }
    }

    /** Blobs to try: the newest chain for a passphrase; for a recovery code, every snapshot (it opens one of them). */
    private suspend fun candidateBlobs(target: BackupTarget, key: RestoreKey): List<String?> {
        val names = target.list()
        if (LATEST !in names && names.none { it.endsWith(BLOB_SUFFIX) }) return emptyList()
        return when (key) {
            is RestoreKey.Passphrase -> listOf(null)
            is RestoreKey.RecoveryCode -> listOf<String?>(null) + names.filter { it.endsWith(BLOB_SUFFIX) }
        }
    }

    /** Imports the settings saved with the snapshot being restored (or the newest one). Best effort. */
    private suspend fun restoreSettings(target: BackupTarget, blob: String?, key: RestoreKey) {
        runCatching {
            withContext(Dispatchers.IO) {
                val name = blob ?: target.openRead(LATEST)?.use { stream ->
                    (json.parseToJsonElement(stream.readBytes().toString(Charsets.UTF_8)) as? JsonObject)
                        ?.get("blobName")?.let { (it as? JsonPrimitive)?.content }
                } ?: return@withContext
                val raw = target.openRead(name) ?: return@withContext
                val plain = when (key) {
                    is RestoreKey.Passphrase -> BackupCrypto.decryptingInputStream(raw, key.value)
                    is RestoreKey.RecoveryCode -> BackupCrypto.decryptingInputStreamWithRecoveryCode(raw, key.value)
                }
                plain.use { stream ->
                    val reader = DakExportReader(stream)
                    reader.readMessages().forEach { _ -> }
                    reader.settingsJson?.takeIf { it.isNotBlank() && it != "{}" }?.let {
                        settings.import(it)
                        importFolds(it)
                    }
                }
            }
        }
    }

    private suspend fun insert(message: Message) = runCatching {
        val addresses = message.address.split(' ', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val threadId = if (addresses.isEmpty()) message.threadId else writer.threadIdFor(addresses)
        writer.restore(message.copy(threadId = threadId))
    }.getOrNull()

    private fun toMessage(record: MessageRecord, files: Map<String, File>) = Message(
        providerId = 0L,
        kind = record.kind,
        threadId = record.threadId,
        address = record.address,
        body = record.body,
        dateMillis = record.dateMillis,
        subId = record.subId,
        box = record.box,
        read = record.read,
        seen = record.seen,
        attachments = record.attachments.mapNotNull { a ->
            val file = files[a.sha256] ?: return@mapNotNull null
            Attachment(mimeType = a.mimeType, uri = Uri.fromFile(file).toString(), name = a.name, sizeBytes = a.sizeBytes)
        },
    )

    private fun toMessage(m: ImportedMessage, tempDir: File): Message {
        val attachments = m.attachments.mapNotNull { a ->
            val bytes = runCatching { a.bytes?.invoke() }.getOrNull() ?: return@mapNotNull null
            val file = File(tempDir, UUID.randomUUID().toString())
            file.writeBytes(bytes)
            Attachment(mimeType = a.mimeType, uri = Uri.fromFile(file).toString(), name = a.name, sizeBytes = bytes.size.toLong())
        }
        return Message(
            providerId = 0L,
            kind = m.kind,
            threadId = 0L,
            address = m.address,
            body = m.body,
            dateMillis = m.dateMillis,
            box = m.box,
            subId = m.subId,
            read = m.read,
            seen = m.read,
            attachments = attachments,
        )
    }

    private fun readHeader(input: InputStream): ByteArray {
        val buffer = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    private fun labelOf(uri: Uri): String? = runCatching {
        if (uri.scheme == "content" && runCatching { android.provider.DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) {
            return@runCatching android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { null }
                ?: uri.authority
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun launchOp(block: suspend () -> BackupOperation) {
        if (state.value is BackupOperation.Running) return
        scope.launch { block() }
    }

    private fun finish(op: BackupOperation.Finished): BackupOperation {
        state.value = op
        return op
    }

    private fun fail(kind: BackupOpKind, failure: BackupFailure, detail: String? = null): BackupOperation {
        val op = BackupOperation.Failed(kind, failure, detail)
        state.value = op
        return op
    }

    private fun failureOf(e: Exception): BackupFailure = when (e) {
        is WrongPassphraseException, is RecoveryCodeMismatchException -> BackupFailure.WRONG_KEY
        is TamperedException, is MalformedHeaderException -> BackupFailure.DAMAGED
        is BackupCryptoException -> BackupFailure.DAMAGED
        is IllegalStateException -> if (e.message?.contains("No backups") == true) BackupFailure.NO_BACKUP_FOUND else BackupFailure.IO
        else -> BackupFailure.IO
    }

    private companion object {
        const val MAX_INCREMENTALS = 14

        /** Settings-JSON key carrying the sender fold rules (a JSON string, see `SenderMergeRepository.exportRules`). */
        const val FOLDS_KEY = "dak.index.senderFolds"
        const val PROGRESS_EVERY = 100
        const val HEADER_BYTES = 4096
        const val LATEST = "latest.json"
        const val BLOB_SUFFIX = ".dakbackup"
    }
}
