package app.dak.safety.helplines

import android.content.Context
import android.util.Log
import app.dak.classify.BundleVerifier
import app.dak.classify.RejectAllVerifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The helplines in use: the bundled asset (trusted), replaced by a verified over-the-air copy with a higher
 * revision once one has been applied with [applyUpdate]. Fetching updates is future work (like template-bundle
 * OTA); until a signing key is configured, [verifier] rejects everything, so only the bundled copy is used.
 */
@Singleton
class HelplineRepository @Inject constructor(@ApplicationContext private val context: Context) {
    /** Verifier for OTA copies. No signing key is provisioned yet, so OTA copies are rejected (fail closed). */
    private val verifier: BundleVerifier = RejectAllVerifier

    private val mutex = Mutex()
    @Volatile private var cached: HelplinePayload? = null

    private val otaFile: File get() = File(context.filesDir, OTA_FILE)

    /** The current bundle (loaded once, off the main thread). Empty only if the bundled asset is unreadable. */
    suspend fun current(): HelplinePayload = cached ?: mutex.withLock {
        cached ?: withContext(Dispatchers.IO) { load() }.also { cached = it }
    }

    /**
     * Verifies [json] (a signed bundle) and, if it is newer than the one in use, stores it for future launches.
     * Returns true when it was applied.
     */
    suspend fun applyUpdate(json: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val update = HelplineBundle.parseSigned(json, verifier) ?: return@withContext false
            val inUse = cached ?: load()
            if (update.revision <= inUse.revision) return@withContext false
            runCatching {
                val tmp = File(context.filesDir, "$OTA_FILE.tmp")
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(otaFile)) error("rename failed")
            }.onFailure { Log.w(TAG, "could not store helplines update", it) }.isSuccess.also { ok ->
                if (ok) cached = update
            }
        }
    }

    private fun load(): HelplinePayload {
        val bundled = runCatching {
            context.assets.open(HelplineBundle.ASSET_NAME).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let(HelplineBundle::parseBundled)
        // An OTA copy is re-verified on every load: a tampered file on disk is ignored.
        val ota = runCatching { if (otaFile.exists()) otaFile.readText(Charsets.UTF_8) else null }.getOrNull()
            ?.let { HelplineBundle.parseSigned(it, verifier) }
        return listOfNotNull(bundled, ota).maxByOrNull { it.revision } ?: EMPTY
    }

    private companion object {
        const val TAG = "DakHelplines"
        const val OTA_FILE = "helplines-ota.json"
        val EMPTY = HelplinePayload(HelplineBundle.FORMAT, HelplineBundle.VERSION, revision = 0, issuedAt = "")
    }
}
