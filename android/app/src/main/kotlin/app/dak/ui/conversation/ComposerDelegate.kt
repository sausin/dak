package app.dak.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import app.dak.automation.ScheduledSendScheduler
import app.dak.core.model.NO_SUB_ID
import app.dak.telephony.SimRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-off results of composer actions, shown as snackbars. */
sealed interface ComposerEvent {
    data class SendFailed(val problem: SendProblem, val detail: String?) : ComposerEvent
    data class Scheduled(val atMillis: Long) : ComposerEvent
    data object ScheduleTextOnly : ComposerEvent
}

/**
 * Composer state and actions shared by the conversation and new-message screens: draft text (kept in the
 * [SavedStateHandle] so it survives process death), attachments, reply SIM, MMS switch, segment counter, roaming
 * chip and normalisation hint, and the send / send-later paths.
 */
class ComposerDelegate(
    private val scope: CoroutineScope,
    private val controller: MessageSendController,
    private val scheduler: ScheduledSendScheduler,
    private val sims: SimRepository,
    private val hints: ComposerHints,
    private val savedState: SavedStateHandle,
    private val recipients: StateFlow<List<String>>,
    /** Sending SIM; [NO_SUB_ID] until known. */
    val subId: MutableStateFlow<Int>,
    private val threadId: () -> Long?,
    private val conversationId: () -> String?,
    private val onSubIdChosen: suspend (Int) -> Unit,
    private val onEvent: (ComposerEvent) -> Unit,
    private val onSent: (List<String>) -> Unit = {},
) : ComposerActions {

    private val text = MutableStateFlow(savedState.get<String>(KEY_DRAFT).orEmpty())

    /**
     * The draft as Compose snapshot state, updated synchronously on every keystroke: bind the text field to this,
     * not to [ui] (which is derived asynchronously and would make fast typing drop characters).
     */
    var draftText: String by mutableStateOf(text.value)
        private set
    private val attachments = MutableStateFlow<List<ComposerAttachment>>(emptyList())
    private val sending = MutableStateFlow(false)
    private var hintVisible = false

    val ui: StateFlow<ComposerUi> = combine(
        combine(text, attachments, sending) { t, a, s -> Triple(t, a, s) },
        recipients,
        subId,
        sims.sims,
    ) { (t, a, s), to, sub, simList ->
        val sim = simList.firstOrNull { it.subId == sub } ?: sims.sim(sub)
        val roaming = sub != NO_SUB_ID && runCatching { sims.isRoaming(sub) }.getOrDefault(false)
        val segments = controller.segments(t)
        val hint = normalizationHint(to, sub)
        hintVisible = hint != null
        ComposerUi(
            text = t,
            attachments = a,
            isMms = controller.isMms(to.size, t, a),
            segments = segments,
            showSegments = a.isEmpty() && (segments.segments > 3 || (roaming && segments.segments > 0)),
            sim = sim,
            canSwitchSim = simList.count { it.isActive } > 1,
            roaming = roaming,
            normalizedHint = hint,
            sending = s,
            enabled = to.isNotEmpty(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, ComposerUi())

    /** Adds media shared from another app (ACTION_SEND). */
    fun addShared(items: List<ComposerAttachment>) {
        if (items.isNotEmpty()) attachments.value = attachments.value + items
    }

    fun setText(value: String) = onTextChange(value)

    override fun onTextChange(text: String) {
        draftText = text
        this.text.value = text
        savedState[KEY_DRAFT] = text
    }

    override fun onAddAttachment(attachment: ComposerAttachment) {
        attachments.value = attachments.value + attachment
    }

    override fun onRemoveAttachment(attachment: ComposerAttachment) {
        attachments.value = attachments.value - attachment
    }

    override fun onSwitchSim() {
        val active = sims.sims.value.filter { it.isActive }
        if (active.size < 2) return
        val index = active.indexOfFirst { it.subId == subId.value }
        val next = active[(index + 1).mod(active.size)].subId
        subId.value = next
        scope.launch { onSubIdChosen(next) }
    }

    override fun onSend() {
        if (sending.value) return
        val body = text.value.trim()
        val files = attachments.value
        val to = recipients.value
        if (body.isEmpty() && files.isEmpty()) return
        sending.value = true
        val showedHint = hintVisible
        scope.launch {
            val outcome = runCatching { controller.send(to, body, files, subId.value, threadId()) }
                .getOrElse { SendOutcome.Failed(SendProblem.PLATFORM, it.message) }
            sending.value = false
            when (outcome) {
                SendOutcome.Sent -> {
                    onTextChange("")
                    attachments.value = emptyList()
                    if (showedHint) hints.normalizationHintShown()
                    onSent(to)
                }
                is SendOutcome.Failed -> onEvent(ComposerEvent.SendFailed(outcome.problem, outcome.detail))
            }
        }
    }

    override fun onScheduleSend(atMillis: Long) {
        val body = text.value.trim()
        if (body.isEmpty()) return
        if (attachments.value.isNotEmpty()) {
            onEvent(ComposerEvent.ScheduleTextOnly)
            return
        }
        val to = recipients.value
        if (to.isEmpty()) return
        scope.launch {
            scheduler.schedule(to, body, subId.value.takeIf { it != NO_SUB_ID }, atMillis, conversationId = conversationId())
            onTextChange("")
            onEvent(ComposerEvent.Scheduled(atMillis))
        }
    }

    private fun normalizationHint(to: List<String>, sub: Int): String? {
        if (to.size != 1 || sub == NO_SUB_ID || !hints.shouldShowNormalizationHint()) return null
        val raw = to.first()
        val normalized = runCatching { controller.normalized(raw, sub) }.getOrDefault(raw)
        return normalized.takeIf { it != raw && it.filter(Char::isDigit) != raw.filter(Char::isDigit) }
    }

    private companion object {
        const val KEY_DRAFT = "composer.draft"
    }
}
