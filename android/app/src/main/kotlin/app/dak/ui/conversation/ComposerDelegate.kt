package app.dak.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import app.dak.automation.EmergencyScheduleRefusedException
import app.dak.automation.ScheduledSendScheduler
import app.dak.core.model.NO_SUB_ID
import app.dak.telephony.SimRepository
import app.dak.telephony.carrier.SendBlock
import app.dak.telephony.carrier.SendMode
import app.dak.telephony.carrier.SendPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-off results of composer actions, shown as snackbars. */
sealed interface ComposerEvent {
    data class SendFailed(val problem: SendProblem, val detail: String?) : ComposerEvent
    data class Scheduled(val atMillis: Long) : ComposerEvent
    data object ScheduleTextOnly : ComposerEvent

    /** Texts to emergency numbers cannot be scheduled: "send it instead". */
    data object ScheduleEmergencyRefused : ComposerEvent
}

/**
 * Composer state and actions shared by the conversation and new-message screens: draft text (kept in the
 * [SavedStateHandle] so it survives process death), attachments, reply SIM, MMS switch, segment counter, roaming
 * chip and normalisation hint, and the send / send-later paths (with the SMS cost confirmation, [CostPrompt]).
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
    private val costPrompt = MutableStateFlow<CostPrompt?>(null)
    private var hintVisible = false

    val ui: StateFlow<ComposerUi> = combine(
        combine(text, attachments, sending, costPrompt) { t, a, s, p -> DraftState(t, a, s, p) },
        recipients,
        subId,
        sims.sims,
        controller.isDefaultSmsApp,
    ) { (t, a, s, prompt), to, sub, simList, isDefault ->
        val sim = simList.firstOrNull { it.subId == sub } ?: sims.sim(sub)
        val roaming = sub != NO_SUB_ID && runCatching { sims.isRoaming(sub) }.getOrDefault(false)
        val segments = controller.segments(t)
        val hint = normalizationHint(to, sub)
        hintVisible = hint != null
        val plan = runCatching { controller.plan(to, t, a, sub) }.getOrNull()
        // Without the SMS role the composer is read-only, except for a text to emergency numbers only.
        val readOnly = !isDefault && !(a.isEmpty() && controller.sendableWithoutRole(to, sub))
        ComposerUi(
            text = t,
            attachments = a,
            isMms = plan?.isMms ?: a.isNotEmpty(),
            segments = segments,
            showSegments = a.isEmpty() && (segments.segments > 3 || (roaming && segments.segments > 0)),
            sim = sim,
            canSwitchSim = simList.count { it.isActive } > 1,
            roaming = roaming,
            normalizedHint = hint,
            sending = s,
            enabled = to.isNotEmpty() && !readOnly,
            costPrompt = prompt,
            notDefaultApp = !isDefault,
            carrierNotice = plan?.let { carrierNoticeOf(it, to.size, sub) },
            recipientLimit = plan?.takeIf { it.block == SendBlock.TOO_MANY_RECIPIENTS }?.let { controller.carrierConfig(sub).recipientLimit },
        )
    }.stateIn(scope, SharingStarted.Eagerly, ComposerUi())

    /** The system role dialog closed (or the app resumed): re-read the default-SMS role. */
    override fun onRoleResult() {
        controller.refreshRole()
    }

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
        if (sending.value || costPrompt.value != null) return
        val body = text.value.trim()
        val to = recipients.value
        if (body.isEmpty() && attachments.value.isEmpty()) return
        sending.value = true
        val sub = subId.value
        scope.launch {
            val warnings = costWarnings(to, sub)
            if (warnings.isNotEmpty()) {
                sending.value = false
                costPrompt.value = CostPrompt(warnings, sub)
            } else {
                sendNow()
            }
        }
    }

    override fun onConfirmCost(dontAskAgain: Boolean) {
        val prompt = costPrompt.value ?: return
        costPrompt.value = null
        if (dontAskAgain) runCatching { controller.approveCost(prompt.verdicts, prompt.subId) }
        val at = prompt.scheduleAtMillis
        if (at != null) {
            scope.launch { scheduleNow(at) }
        } else {
            if (sending.value) return
            sending.value = true
            scope.launch { sendNow() }
        }
    }

    override fun onDismissCost() {
        costPrompt.value = null
    }

    private suspend fun costWarnings(to: List<String>, sub: Int) =
        runCatching { withContext(Dispatchers.Default) { controller.costWarnings(to, sub) } }.getOrDefault(emptyList())

    /** Sends the current draft; [sending] must already be true. */
    private suspend fun sendNow() {
        val body = text.value.trim()
        val files = attachments.value
        val to = recipients.value
        if (body.isEmpty() && files.isEmpty()) {
            sending.value = false
            return
        }
        val showedHint = hintVisible
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

    override fun onScheduleSend(atMillis: Long) {
        if (costPrompt.value != null) return
        val body = text.value.trim()
        if (body.isEmpty()) return
        if (attachments.value.isNotEmpty()) {
            onEvent(ComposerEvent.ScheduleTextOnly)
            return
        }
        val to = recipients.value
        if (to.isEmpty()) return
        val sub = subId.value
        scope.launch {
            // Refused before the cost dialog: emergency services need the text now, not later.
            if (refusesEmergency(to, sub)) {
                onEvent(ComposerEvent.ScheduleEmergencyRefused)
                return@launch
            }
            val warnings = costWarnings(to, sub)
            if (warnings.isNotEmpty()) costPrompt.value = CostPrompt(warnings, sub, scheduleAtMillis = atMillis) else scheduleNow(atMillis)
        }
    }

    private suspend fun scheduleNow(atMillis: Long) {
        val body = text.value.trim()
        val to = recipients.value
        if (body.isEmpty() || to.isEmpty()) return
        try {
            scheduler.schedule(to, body, subId.value.takeIf { it != NO_SUB_ID }, atMillis, conversationId = conversationId())
        } catch (e: EmergencyScheduleRefusedException) {
            onEvent(ComposerEvent.ScheduleEmergencyRefused)
            return
        }
        onTextChange("")
        onEvent(ComposerEvent.Scheduled(atMillis))
    }

    private suspend fun refusesEmergency(to: List<String>, sub: Int): Boolean = runCatching {
        withContext(Dispatchers.Default) { scheduler.refusesEmergency(to, sub.takeIf { it != NO_SUB_ID }) }
    }.getOrDefault(false)

    private fun normalizationHint(to: List<String>, sub: Int): String? {
        if (to.size != 1 || sub == NO_SUB_ID || !hints.shouldShowNormalizationHint()) return null
        val raw = to.first()
        val normalized = runCatching { controller.normalized(raw, sub) }.getOrDefault(raw)
        return normalized.takeIf { it != raw && it.filter(Char::isDigit) != raw.filter(Char::isDigit) }
    }

    private fun carrierNoticeOf(plan: SendPlan, recipientCount: Int, sub: Int): CarrierNotice? = when {
        plan.block == SendBlock.MMS_DISABLED -> CarrierNotice.MMS_DISABLED
        plan.block == SendBlock.TOO_MANY_RECIPIENTS -> CarrierNotice.TOO_MANY_RECIPIENTS
        plan.block == SendBlock.TEXT_TOO_LONG -> CarrierNotice.TEXT_TOO_LONG
        recipientCount > 1 && plan.mode != SendMode.MMS -> CarrierNotice.GROUP_AS_INDIVIDUAL.takeIf { !controller.carrierConfig(sub).groupMmsEnabled }
        else -> null
    }

    /** Draft-side inputs of [ui]. */
    private data class DraftState(
        val text: String,
        val attachments: List<ComposerAttachment>,
        val sending: Boolean,
        val costPrompt: CostPrompt?,
    )

    private companion object {
        const val KEY_DRAFT = "composer.draft"
    }
}
