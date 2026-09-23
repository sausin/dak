package app.dak.security

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local app-lock secrets and counters, kept in `noBackupFilesDir` so they are never part of Android's cloud
 * backup, device-to-device transfer or Dak's own settings export: the salted PIN hash ([PinRecord]), the wrong-PIN
 * [LockoutState], and the "fingerprint instead of PIN" choice (biometrics are per device).
 *
 * Small synchronous file I/O; writes are atomic ([AtomicFile]). Callers keep it off the main thread where they can.
 */
@Singleton
class AppLockStore @Inject constructor(@ApplicationContext context: Context) {

    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val lock = Any()
    @Volatile private var cache: Properties? = null

    /** The stored PIN hash, or null when no app PIN is set (or the stored value is unreadable). */
    fun pinRecord(): PinRecord? = PinRecord.decode(read().getProperty(KEY_PIN))

    fun hasPin(): Boolean = pinRecord() != null

    fun setPinRecord(record: PinRecord) = edit {
        setProperty(KEY_PIN, record.encode())
        remove(KEY_LOCKOUT)
    }

    /** Removes the app PIN together with its failure counter and the fingerprint shortcut. */
    fun clearPin() = edit {
        remove(KEY_PIN)
        remove(KEY_LOCKOUT)
        remove(KEY_FINGERPRINT)
    }

    fun lockout(): LockoutState = LockoutState.decode(read().getProperty(KEY_LOCKOUT))

    fun setLockout(state: LockoutState) = edit {
        if (state == LockoutState.NONE) remove(KEY_LOCKOUT) else setProperty(KEY_LOCKOUT, state.encode())
    }

    fun fingerprintInsteadOfPin(): Boolean = read().getProperty(KEY_FINGERPRINT) == "true"

    fun setFingerprintInsteadOfPin(enabled: Boolean) = edit {
        if (enabled) setProperty(KEY_FINGERPRINT, "true") else remove(KEY_FINGERPRINT)
    }

    private fun read(): Properties {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val props = Properties()
            runCatching { file.openRead().use { props.load(it) } }
            cache = props
            return props
        }
    }

    private fun edit(block: Properties.() -> Unit) {
        synchronized(lock) {
            val next = Properties().apply { putAll(read()) }
            next.block()
            val out = file.startWrite()
            try {
                next.store(out, null)
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
            cache = next
        }
    }

    private companion object {
        const val FILE_NAME = "app_lock.properties"
        const val KEY_PIN = "pin"
        const val KEY_LOCKOUT = "lockout"
        const val KEY_FINGERPRINT = "fingerprintInsteadOfPin"
    }
}
