package app.dak.incognito

import android.content.Context
import android.util.Log
import app.dak.core.model.MessageKey
import app.dak.di.ApplicationScope
import app.dak.index.bin.RecycleBin
import app.dak.index.repo.ConversationRepository
import app.dak.index.repo.IncognitoScope
import app.dak.telephony.OutgoingSentListener
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * A send that fails (after the automatic retries) is never deleted behind the user's back: the thread asks "Retry" or
 * "Delete" ([FailedSendStep.FIRST_FAILURE], deleted when that prompt times out); a retry that fails again offers
 * "Keep" ([FailedSendStep.SECOND_FAILURE]), which turns it into an ordinary failed message kept for a later retry
 * ([keep]). A kept message that is later sent still vanishes.
 *
 * Only this phone's copy vanishes: SMS has no way to delete the recipient's copy. The first time incognito is turned on
 * the user is told so ([introSeen]).
 */
@Singleton
class IncognitoVanisher @Inject constructor(
    @ApplicationContext context: Context,
    private val conversations: ConversationRepository,
    private val bin: RecycleBin,
    @ApplicationScope private val scope: CoroutineScope,
) : OutgoingSentListener {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val mutableVanishing = MutableStateFlow<Set<MessageKey>>(emptySet())
    private val mutableFailedState = MutableStateFlow(loadFailedState())

    /** Per failed-send decision state, for the thread's prompt (keys as [MessageKey.toString]). */
    val failedState: StateFlow<FailedSendState> = mutableFailedState.asStateFlow()

    /** True once the user has seen the first-time explanation (only their own copy vanishes). */
    val introSeen: Boolean get() = prefs.getBoolean(KEY_INTRO_SEEN, false)

    fun markIntroSeen() {
        prefs.edit().putBoolean(KEY_INTRO_SEEN, true).apply()
    }

    /** Where [key]'s failed-send prompt is: first failure, failed again after a retry, or kept by the user. */
    fun failedStep(key: MessageKey): FailedSendStep = mutableFailedState.value.stepOf(key.toString())

    /** The user chose "Retry" on a failed incognito send: a second failure then offers "Keep". */
    fun markRetried(key: MessageKey) = updateFailed { it.copy(retried = it.retried + key.toString()) }

    /** The user chose "Keep": it stays as an ordinary failed message, for a retry later. */
    fun keep(key: MessageKey) = updateFailed { it.copy(kept = it.kept + key.toString()) }

    /** Deletes a failed incognito send now (the prompt's Delete, or its timeout). */
    fun discardFailed(key: MessageKey) {
        vanish(listOf(key), animate = true)
    }

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
                forgetFailed(fresh)
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

    private fun forgetFailed(keys: Collection<MessageKey>) {
        val names = keys.mapTo(HashSet()) { it.toString() }
        if (mutableFailedState.value.retried.none { it in names } && mutableFailedState.value.kept.none { it in names }) return
        updateFailed { FailedSendState(retried = it.retried - names, kept = it.kept - names) }
    }

    @Synchronized
    private fun updateFailed(change: (FailedSendState) -> FailedSendState) {
        val next = change(mutableFailedState.value).bounded()
        prefs.edit()
            .putStringSet(KEY_RETRIED, HashSet(next.retried))
            .putStringSet(KEY_KEPT, HashSet(next.kept))
            .apply()
        mutableFailedState.value = next
    }

    private fun loadFailedState() = FailedSendState(
        retried = prefs.getStringSet(KEY_RETRIED, emptySet()).orEmpty().toSet(),
        kept = prefs.getStringSet(KEY_KEPT, emptySet()).orEmpty().toSet(),
    )

    private suspend fun sweep(incognito: IncognitoScope) {
        val sent = conversations.sentSince(incognito)
        if (sent.isNotEmpty()) bin.deleteWithoutBin(sent - mutableVanishing.value, REASON)
    }

    companion object {
        /** How long a bubble takes to dissolve before it is deleted. */
        const val ANIMATION_MILLIS = 1_400L

        /** How long a received message stays readable in the open thread before it dissolves. */
        const val READ_WINDOW_MILLIS = 10_000L

        /** How long a failed send's Retry / Delete prompt waits before the message is deleted. */
        const val FAILED_PROMPT_MILLIS = 30_000L

        private const val PREFS = "dak_incognito"
        private const val KEY_INTRO_SEEN = "intro_seen"
        private const val KEY_RETRIED = "failed_retried"
        private const val KEY_KEPT = "failed_kept"

        private const val SENT_LOOKUPS = 4
        private const val SENT_LOOKUP_DELAY_MILLIS = 1_500L
        private const val REASON = "incognito"
        private const val TAG = "DakIncognito"
    }
}

/** Where a failed incognito send stands with the user (see [IncognitoVanisher]). */
enum class FailedSendStep {
    /** Failed: offer Retry / Delete; deleted when the prompt times out. */
    FIRST_FAILURE,

    /** Failed again after the user's retry: offer Keep / Delete; deleted when the prompt times out. */
    SECOND_FAILURE,

    /** The user kept it: an ordinary failed message (tap to retry), never deleted until it is sent. */
    KEPT,
}

/** Decisions on failed incognito sends, by message key. Only keys are stored, never content. */
data class FailedSendState(val retried: Set<String> = emptySet(), val kept: Set<String> = emptySet()) {
    fun stepOf(key: String): FailedSendStep = when (key) {
        in kept -> FailedSendStep.KEPT
        in retried -> FailedSendStep.SECOND_FAILURE
        else -> FailedSendStep.FIRST_FAILURE
    }

    /** Keeps the stored sets small: a user never has hundreds of failed incognito sends pending a decision. */
    fun bounded(max: Int = MAX_KEYS): FailedSendState =
        if (retried.size <= max && kept.size <= max) this else FailedSendState(retried.toList().takeLast(max).toSet(), kept.toList().takeLast(max).toSet())

    private companion object {
        const val MAX_KEYS = 200
    }
}
