package app.dak.index.scam

import android.content.Context
import app.dak.core.model.MessageKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User decisions about fake-credit warnings: the messages marked "Not a scam" (kept outside the index, so they
 * survive a re-index or rebuild) and the "Warn about fake credit alerts" switch mirrored from settings (the index
 * cannot read the app's settings store, so the app pushes the value here; see [setEnabled]).
 *
 * Only message keys (provider ids) are stored, never message content.
 */
@Singleton
class ScamOverrides @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var dismissed: Set<String>? = null

    fun isDismissed(key: MessageKey): Boolean = key.toString() in loadDismissed()

    fun dismiss(key: MessageKey) {
        synchronized(this) {
            val updated = loadDismissed() + key.toString()
            // Bounded: a user dismissing thousands of warnings keeps only the newest entries' effect.
            val trimmed = if (updated.size > MAX_DISMISSED) updated.toList().takeLast(MAX_DISMISSED).toSet() else updated
            prefs.edit().putStringSet(KEY_DISMISSED, HashSet(trimmed)).apply()
            dismissed = trimmed
        }
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun loadDismissed(): Set<String> {
        dismissed?.let { return it }
        synchronized(this) {
            dismissed?.let { return it }
            val loaded = prefs.getStringSet(KEY_DISMISSED, emptySet())?.toSet() ?: emptySet()
            dismissed = loaded
            return loaded
        }
    }

    private companion object {
        const val PREFS = "dak_scam_overrides"
        const val KEY_DISMISSED = "dismissed"
        const val KEY_ENABLED = "enabled"
        const val MAX_DISMISSED = 5_000
    }
}
