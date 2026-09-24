package app.dak.incognito

import android.util.Log
import app.dak.core.model.MessageKey
import app.dak.di.ApplicationScope
import app.dak.index.bin.RecycleBin
import app.dak.index.repo.ConversationRepository
import app.dak.index.repo.IncognitoScope
import app.dak.telephony.OutgoingSentListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Incognito ("vanishing") chats, Snapchat style, for the conversations the user switched it on for (only messages
 * from then on; older history is never touched):
 * - a sent message is deleted as soon as the radio confirms it left the phone ([onSent]); one still queued or failed
 *   stays, so it can be retried;
 * - a received message is deleted once it has been read in the thread (the screen calls [vanish] after the reading
 *   window, and again for everything seen when the thread is left). Notifications never show the text, and marking
 *   read from outside the thread does not delete anything;
 * - deletes skip the recycle bin, so nothing can be restored.
 *
 * While the thread is on screen ([visibleConversation]) the doomed bubbles are published in [vanishing] first so the
 * screen can play the dissolve animation, then deleted [ANIMATION_MILLIS] later. [sweep] catches anything a missed
 * callback left behind (the thread opening or closing, the daily housekeeping).
 *
 * Only this phone's copy vanishes: SMS has no way to delete the recipient's copy, and the screens say so.
 */
@Singleton
class IncognitoVanisher @Inject constructor(
    private val conversations: ConversationRepository,
    private val bin: RecycleBin,
    @ApplicationScope private val scope: CoroutineScope,
) : OutgoingSentListener {

    private val mutableVanishing = MutableStateFlow<Set<MessageKey>>(emptySet())

    /** Messages that are dissolving on screen right now (deleted when their animation ends). */
    val vanishing: StateFlow<Set<MessageKey>> = mutableVanishing.asStateFlow()

    /** Fold-resolved id of the incognito thread on screen, or null; set by the conversation screen. */
    @Volatile
    var visibleConversation: String? = null

    override suspend fun onSent(key: MessageKey) {
        // The index may not have caught up with a message sent a moment ago: look again a few times (off the sent
        // broadcast's budget) before leaving it to the next sweep. A message that is not in an incognito chat just
        // never matches.
        scope.launch {
            repeat(SENT_LOOKUPS) { attempt ->
                if (attempt > 0) delay(SENT_LOOKUP_DELAY_MILLIS)
                val incognito = runCatching { conversations.incognitoScopeOf(key) }.getOrNull()
                if (incognito != null) {
                    vanish(listOf(key), animate = incognito.conversationId == visibleConversation)
                    return@launch
                }
                if (runCatching { conversations.conversationIdOf(key) }.getOrNull() != null) return@launch // indexed, not incognito
            }
        }
    }

    /** Deletes [keys] for good; with [animate] they are shown dissolving for [ANIMATION_MILLIS] first. */
    fun vanish(keys: Collection<MessageKey>, animate: Boolean) {
        val fresh = keys.toSet() - mutableVanishing.value
        if (fresh.isEmpty()) return
        scope.launch {
            if (animate) {
                mutableVanishing.update { it + fresh }
                delay(ANIMATION_MILLIS)
            }
            try {
                runCatching { bin.deleteWithoutBin(fresh, REASON) }.onFailure { Log.w(TAG, "vanish failed", it) }
            } finally {
                if (animate) mutableVanishing.update { it - fresh }
            }
        }
    }

    /** Deletes what [conversationId] has sent since it went incognito (a missed sent callback, a restart). */
    suspend fun sweep(conversationId: String) {
        conversations.incognitoScope(conversationId)?.let { sweep(it) }
    }

    /** [sweep] for every incognito conversation; from the daily housekeeping. */
    suspend fun sweepAll() {
        for (incognito in conversations.incognitoScopes()) sweep(incognito)
    }

    private suspend fun sweep(incognito: IncognitoScope) {
        val sent = conversations.sentSince(incognito)
        if (sent.isNotEmpty()) bin.deleteWithoutBin(sent - mutableVanishing.value, REASON)
    }

    companion object {
        /** How long a bubble takes to dissolve before it is deleted. */
        const val ANIMATION_MILLIS = 1_400L

        /** How long a received message stays readable in the open thread before it dissolves. */
        const val READ_WINDOW_MILLIS = 10_000L

        private const val SENT_LOOKUPS = 4
        private const val SENT_LOOKUP_DELAY_MILLIS = 1_500L
        private const val REASON = "incognito"
        private const val TAG = "DakIncognito"
    }
}
