package app.dak.automation

import android.content.Context
import app.dak.R
import app.dak.automations.undo.UndoToken
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One undoable automation effect, announced to whichever screen is showing (the inbox shows a snackbar). */
data class AutomationUndo(val id: Long, val token: UndoToken, val ruleName: String) {
    /**
     * Snackbar text in the app language. [UndoToken.description] is the executor's English text (kept for logs);
     * the known action types are shown from resources, anything else falls back to it.
     */
    fun text(context: Context): String = when (token.actionType) {
        IndexArchiver.ACTION_TYPE -> context.getString(R.string.undo_rule_archived, ruleName)
        IndexBinner.ACTION_TYPE -> context.getString(R.string.undo_rule_binned, ruleName)
        else -> token.description
    }
}

/**
 * Keeps the few-seconds undo for archive/delete actions run by automations. Executors [register] a reversal;
 * the UI collects [events] and calls [undo] when the user taps "Undo" before [UndoToken.expiresAtMillis].
 */
@Singleton
class AutomationUndoCenter @Inject constructor() {
    private val reversals = ConcurrentHashMap<Long, Pair<Long, suspend () -> Unit>>()
    private val flow = MutableSharedFlow<AutomationUndo>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var nextId = 0L

    val events: SharedFlow<AutomationUndo> = flow.asSharedFlow()

    /** Registers [reverse] for [token] and announces it. */
    fun register(token: UndoToken, ruleName: String, reverse: suspend () -> Unit) {
        val id = synchronized(this) { ++nextId }
        val now = System.currentTimeMillis()
        reversals.entries.removeAll { it.value.first < now }
        reversals[id] = token.expiresAtMillis to reverse
        flow.tryEmit(AutomationUndo(id, token, ruleName))
    }

    /** Reverses [undo] if it has not expired; returns true when reversed. */
    suspend fun undo(undo: AutomationUndo, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val (expiresAt, reverse) = reversals.remove(undo.id) ?: return false
        if (nowMillis > expiresAt) return false
        return runCatching { reverse() }.isSuccess
    }

    companion object {
        /** How long the undo stays available. */
        const val UNDO_WINDOW_MILLIS = 8_000L
    }
}
