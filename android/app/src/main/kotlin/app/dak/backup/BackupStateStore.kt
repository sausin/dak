package app.dak.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.dak.backup.format.Manifest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the backup screen shows about the configured destination and the last run. */
data class BackupStatus(
    val destination: Uri?,
    val destinationLabel: String?,
    val lastBackupMillis: Long?,
    val lastMessageCount: Int?,
    /** Why the last scheduled or manual backup failed (shown translated); null after a success. */
    val lastFailure: BackupFailure?,
    /**
     * The failure text stored by versions before [lastFailure] existed (an English exception message), shown as is
     * until the next backup replaces it. New failures never set it.
     */
    val lastError: String?,
    val snapshotsSinceFull: Int,
)

/**
 * Local bookkeeping for incremental backups: the chosen folder (with its persisted SAF permission), the previous
 * manifest and content digest the engine needs for the next incremental snapshot, and the last outcome.
 * Stored in the app's private files; none of it is secret (message content never lands here).
 */
@Singleton
class BackupStateStore @Inject constructor(@ApplicationContext private val context: Context) {

    @Serializable
    internal data class Persisted(
        val treeUri: String? = null,
        val treeLabel: String? = null,
        val previousManifest: Manifest? = null,
        val previousDigest: Map<String, String> = emptyMap(),
        val knownAttachmentHashes: Set<String> = emptySet(),
        val lastBackupMillis: Long? = null,
        val lastMessageCount: Int? = null,
        /** Legacy: the English exception message older versions stored. Never written any more. */
        val lastError: String? = null,
        val snapshotsSinceFull: Int = 0,
        /** [BackupFailure] name of the last failure; null after a success. */
        val lastFailure: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "backup/state.json")
    private val state = MutableStateFlow(load())

    val status: StateFlow<BackupStatus> get() = statusFlow.asStateFlow()
    private val statusFlow = MutableStateFlow(toStatus(state.value))

    internal fun current(): Persisted = state.value

    /** Takes a persistable permission on [treeUri] and makes it the destination (starts a fresh full chain). */
    suspend fun setDestination(treeUri: Uri, label: String?) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
        val previous = state.value.treeUri?.let(Uri::parse)
        if (previous != null && previous != treeUri) {
            runCatching { context.contentResolver.releasePersistableUriPermission(previous, flags) }
        }
        update { Persisted(treeUri = treeUri.toString(), treeLabel = label) }
    }

    suspend fun clearDestination() {
        update { it.copy(treeUri = null, treeLabel = null, previousManifest = null, previousDigest = emptyMap()) }
    }

    /** Forgets the incremental chain, so the next backup is a full snapshot (e.g. after a passphrase change). */
    suspend fun resetChain() {
        update { it.copy(previousManifest = null, previousDigest = emptyMap(), knownAttachmentHashes = emptySet(), snapshotsSinceFull = 0) }
    }

    internal suspend fun recordSuccess(manifest: Manifest, digest: Map<String, String>, hashes: Set<String>, messageCount: Int, full: Boolean) {
        update {
            it.copy(
                previousManifest = manifest,
                previousDigest = digest,
                knownAttachmentHashes = hashes,
                lastBackupMillis = manifest.createdAt,
                lastMessageCount = messageCount,
                lastError = null,
                lastFailure = null,
                snapshotsSinceFull = if (full) 0 else it.snapshotsSinceFull + 1,
            )
        }
    }

    internal suspend fun recordFailure(failure: BackupFailure) {
        // A code, not text: the screen shows it in the app language (and the language may change later).
        update { it.copy(lastFailure = failure.name, lastError = null) }
    }

    private suspend fun update(change: (Persisted) -> Persisted) = withContext(Dispatchers.IO) {
        synchronized(this@BackupStateStore) {
            val next = change(state.value)
            state.value = next
            statusFlow.value = toStatus(next)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "state.json.tmp")
            tmp.writeText(json.encodeToString(Persisted.serializer(), next))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun load(): Persisted = runCatching {
        if (file.exists()) json.decodeFromString(Persisted.serializer(), file.readText()) else Persisted()
    }.getOrDefault(Persisted())

    private fun toStatus(p: Persisted) = BackupStatus(
        destination = p.treeUri?.let(Uri::parse),
        destinationLabel = p.treeLabel,
        lastBackupMillis = p.lastBackupMillis,
        lastMessageCount = p.lastMessageCount,
        lastFailure = p.lastFailure?.let { name -> BackupFailure.entries.firstOrNull { it.name == name } },
        lastError = p.lastError,
        snapshotsSinceFull = p.snapshotsSinceFull,
    )
}
