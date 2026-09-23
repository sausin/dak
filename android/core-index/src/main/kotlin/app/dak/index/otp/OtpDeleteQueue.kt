package app.dak.index.otp

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable list of pending OTP auto-deletes (a few dozen entries at most: one per OTP of the last day), kept in a
 * small SharedPreferences file outside the index so it costs no schema change and survives an index rebuild.
 * Call from background threads only: writes are synchronous (`commit`) so an entry is on disk before the
 * receiver's process may die.
 */
@Singleton
class OtpDeleteQueue @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Adds [entry] unless one exists for the same message (the first schedule wins). Returns true if added. */
    @Synchronized
    fun add(entry: OtpDeleteEntry): Boolean {
        val name = ENTRY_PREFIX + entry.key
        if (prefs.contains(name)) return false
        prefs.edit().putString(name, entry.encodeValue()).commit()
        return true
    }

    /** Removes the entries for [keys] (`MessageKey.toString()` form). */
    @Synchronized
    fun remove(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach { editor.remove(ENTRY_PREFIX + it) }
        editor.commit()
    }

    /** All pending entries. */
    @Synchronized
    fun all(): List<OtpDeleteEntry> = prefs.all.mapNotNull { (name, value) ->
        if (!name.startsWith(ENTRY_PREFIX) || value !is String) null
        else OtpDeleteEntry.decode(name.removePrefix(ENTRY_PREFIX), value)
    }

    /** When the sweep job is currently armed to run (0 = not armed). */
    var armedAtMillis: Long
        @Synchronized get() = prefs.getLong(KEY_ARMED_AT, 0L)
        @Synchronized set(value) {
            prefs.edit().putLong(KEY_ARMED_AT, value).commit()
        }

    private companion object {
        const val PREFS = "dak_otp_delete_queue"
        const val ENTRY_PREFIX = "m:"
        const val KEY_ARMED_AT = "armedAt"
    }
}
