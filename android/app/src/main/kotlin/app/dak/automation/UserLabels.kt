package app.dak.automation

import android.content.Context
import app.dak.automations.action.Labeler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User/automation labels on single messages, kept outside the derived index so they survive re-indexing.
 * (:core-index exposes classifier labels on `MessageItem.labels` but has no write API for user labels yet; the
 * conversation screen shows both.) Backs the automation "label" action.
 */
@Singleton
class UserLabels @Inject constructor(@ApplicationContext context: Context) : Labeler {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    @Volatile private var loaded = false

    /** Every labelled message key → its labels; loaded lazily on first access. */
    val all: StateFlow<Map<String, Set<String>>> get() {
        ensureLoaded()
        return state.asStateFlow()
    }

    override suspend fun label(messageKey: String, label: String): Boolean = withContext(Dispatchers.IO) {
        val clean = label.trim()
        if (clean.isEmpty() || messageKey.isBlank()) return@withContext false
        ensureLoaded()
        synchronized(this@UserLabels) {
            val next = (state.value[messageKey].orEmpty() + clean)
            prefs.edit().putString(messageKey, next.joinToString(SEPARATOR)).apply()
            state.value = state.value + (messageKey to next)
        }
        true
    }

    suspend fun remove(messageKey: String, label: String): Unit = withContext(Dispatchers.IO) {
        ensureLoaded()
        synchronized(this@UserLabels) {
            val next = state.value[messageKey].orEmpty() - label
            if (next.isEmpty()) prefs.edit().remove(messageKey).apply()
            else prefs.edit().putString(messageKey, next.joinToString(SEPARATOR)).apply()
            state.value = if (next.isEmpty()) state.value - messageKey else state.value + (messageKey to next)
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            state.value = prefs.all.mapNotNull { (key, value) ->
                (value as? String)?.split(SEPARATOR)?.filter { it.isNotBlank() }?.toSet()?.let { key to it }
            }.toMap()
            loaded = true
        }
    }

    private companion object {
        const val PREFS = "dak_user_labels"
        const val SEPARATOR = "\u001f"
    }
}
