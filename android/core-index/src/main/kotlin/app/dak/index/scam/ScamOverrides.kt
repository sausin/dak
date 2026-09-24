package app.dak.index.scam

import android.content.Context
import android.content.SharedPreferences
import app.dak.core.model.MessageKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The messages the user marked "Not a scam" (kept outside the index, so the decision survives a re-index or
 * rebuild).
 *
 * Only message keys (provider ids) are stored, never message content. They are kept in the order they were dismissed
 * (oldest first), so the bound ([MAX_DISMISSED] keys) drops the oldest decisions, never a recent one.
 */
@Singleton
class ScamOverrides internal constructor(
    private val prefs: SharedPreferences,
    private val maxDismissed: Int = MAX_DISMISSED,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    @Volatile
    private var dismissed: LinkedHashSet<String>? = null

    fun isDismissed(key: MessageKey): Boolean = key.toString() in loadDismissed()

    fun dismiss(key: MessageKey) {
        synchronized(this) {
            val updated = LinkedHashSet(loadDismissed())
            updated.remove(key.toString()) // dismissing again makes it the newest
            updated.add(key.toString())
            val trimmed = if (updated.size > maxDismissed) LinkedHashSet(updated.toList().takeLast(maxDismissed)) else updated
            prefs.edit()
                .putString(KEY_ORDERED, trimmed.joinToString(SEPARATOR))
                .remove(KEY_LEGACY)
                .apply()
            dismissed = trimmed
        }
    }

    private fun loadDismissed(): Set<String> {
        dismissed?.let { return it }
        synchronized(this) {
            dismissed?.let { return it }
            val ordered = prefs.getString(KEY_ORDERED, null)
            // Before ordering was kept, keys were a plain (unordered) string set: read them once, oldest unknown.
            val loaded = if (ordered != null) {
                ordered.split(SEPARATOR).filterTo(LinkedHashSet()) { it.isNotEmpty() }
            } else {
                LinkedHashSet(prefs.getStringSet(KEY_LEGACY, emptySet()).orEmpty().sorted())
            }
            dismissed = loaded
            return loaded
        }
    }

    internal companion object {
        const val PREFS = "dak_scam_overrides"
        const val KEY_LEGACY = "dismissed"
        const val KEY_ORDERED = "dismissed_ordered"
        const val MAX_DISMISSED = 5_000
        private const val SEPARATOR = "\n"
    }
}
