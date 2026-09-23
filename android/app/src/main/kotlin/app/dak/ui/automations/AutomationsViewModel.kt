package app.dak.ui.automations

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.automation.ForwardingHold
import app.dak.automation.ForwardingHolds
import app.dak.automation.ForwardingStatusNotifier
import app.dak.automation.OtpForwardConfirmations
import app.dak.automation.OutboundAutomationGuard
import app.dak.automation.RuleEntry
import app.dak.automation.RuleRepository
import app.dak.automation.ScheduledSendHeadsUpActions
import app.dak.automation.ScheduledSendScheduler
import app.dak.automations.presets.Presets
import app.dak.automations.rule.Rule
import app.dak.automations.rule.activeWindow
import app.dak.automations.rule.isExpired
import app.dak.automations.rule.sendsOffDevice
import app.dak.automations.rule.withRestartedWindow
import app.dak.automations.safety.RuleValidator
import app.dak.automations.safety.ValidationIssue
import app.dak.core.model.SimInfo
import app.dak.index.repo.ScheduledSend
import app.dak.index.repo.ScheduledSendStore
import app.dak.navigation.Routes
import app.dak.premium.Entitlements
import app.dak.premium.Feature
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What saving a draft needs from the screen. */
sealed interface SaveCheck {
    /** Fix these first. */
    data class Invalid(val issues: List<ValidationIssue>) : SaveCheck
    /** A required field is empty. */
    data object Incomplete : SaveCheck
    /** Forwards/relays OTP-capable messages: confirm with a biometric check, then call [AutomationsViewModel.confirmAndSave]. */
    data class NeedsConfirmation(val rule: Rule) : SaveCheck
    /** Sends messages off the phone while no app lock is set up: "Set up app lock first". */
    data object NeedsAppLock : SaveCheck
    data object Saved : SaveCheck
}

/** What turning a rule on needs from the screen ([AutomationsViewModel.checkEnable]). */
sealed interface EnableCheck {
    /** The rule could not be read. */
    data object Unreadable : EnableCheck
    /** It sends messages off the phone and no app lock is set up. */
    data object NeedsAppLock : EnableCheck
    /** Confirm with a biometric check, then [AutomationsViewModel.enable] with `confirmed = true`. */
    data class NeedsConfirmation(val rule: Rule) : EnableCheck
    /** Call [AutomationsViewModel.enable]. */
    data class Ready(val rule: Rule) : EnableCheck
}

/**
 * Automations: rules list with enable toggles, the simple rule editor, presets, scheduled sends and the exact
 * alarm permission prompt. OTP-forwarding rules need a biometric confirmation to be created, to change recipient
 * and to be re-enabled ([OtpForwardConfirmations]). Rules that send messages off the phone need an app lock
 * ([OutboundAutomationGuard]), checked again when the change is applied. A rule whose period ended is used again with a
 * fresh period of the same length when switched on.
 */
@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val rules: RuleRepository,
    private val confirmations: OtpForwardConfirmations,
    private val entitlements: Entitlements,
    private val scheduler: ScheduledSendScheduler,
    scheduledSends: ScheduledSendStore,
    private val sims: SimRepository,
    private val forwardingStatus: ForwardingStatusNotifier,
    private val outboundGuard: OutboundAutomationGuard,
    private val holds: ForwardingHolds,
    private val scheduledActions: ScheduledSendHeadsUpActions,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** The scheduled send a heads-up notification opened this screen on ([Routes.SCHEDULED_SENDS]), if any. */
    val focusScheduledId: Long? = savedState.get<String>(Routes.ARG_SCHEDULED_ID)?.toLongOrNull()

    /** True once: the heads-up's "Pick time" opens the date and time pickers for [focusScheduledId]. */
    fun consumePickTime(): Boolean {
        if (savedState.get<String>(Routes.ARG_PICK_TIME) != "1" || savedState.get<Boolean>(KEY_PICK_CONSUMED) == true) return false
        savedState[KEY_PICK_CONSUMED] = true
        return true
    }

    val entries: StateFlow<List<RuleEntry>?> = rules.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scheduled: StateFlow<List<ScheduledSend>> = scheduledSends.pending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True when the send the screen was opened on is no longer pending (sent or cancelled meanwhile). */
    val focusGone: StateFlow<Boolean> = scheduledSends.pending()
        .map { list -> focusScheduledId != null && list.none { it.id == focusScheduledId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val simList: StateFlow<List<SimInfo>> = sims.sims

    val granted: StateFlow<Set<Feature>> = entitlements.granted.map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun canScheduleExact(): Boolean = scheduler.canScheduleExact()

    fun exactAlarmIntent(): Intent? = scheduler.exactAlarmSettingsIntent()

    fun isLocked(kind: ActionKind): Boolean = kind.premium?.let { !entitlements.has(it) } ?: false

    /** Validates [draft]; saves it right away unless it needs a biometric confirmation first. */
    fun trySave(draft: RuleDraft): SaveCheck {
        val rule = draft.toRule(System.currentTimeMillis()) ?: return SaveCheck.Incomplete
        val own = sims.sims.value.mapNotNull { it.number }.filter { it.isNotBlank() }.toSet()
        val issues = RuleValidator.validate(rule, entitlements, own)
        if (issues.isNotEmpty()) return SaveCheck.Invalid(issues)
        if (lockMissingFor(rule)) return SaveCheck.NeedsAppLock
        if (confirmations.needsConfirmation(rule) && !confirmations.isConfirmed(rule)) return SaveCheck.NeedsConfirmation(rule)
        holds.clear(rule.id)
        viewModelScope.launch { rules.save(rule) }
        return SaveCheck.Saved
    }

    /** Called after a successful biometric check for [rule]; false (nothing saved) when the app lock went meanwhile. */
    fun confirmAndSave(rule: Rule): Boolean {
        if (lockMissingFor(rule)) return false
        confirmations.confirm(rule)
        holds.clear(rule.id)
        viewModelScope.launch { rules.save(rule) }
        return true
    }

    /** Why [entry] is off when the app turned it off by itself (no app lock, recipient left contacts), else null. */
    fun holdOf(entry: RuleEntry): ForwardingHold? = if (entry.stored.enabled) null else holds.reason(entry.stored.id)

    /**
     * What turning [entry] on needs. A rule whose period ended comes back with a fresh period of the same length from
     * now ([withRestartedWindow]), so it is checked (and confirmed) as that new rule.
     */
    fun checkEnable(entry: RuleEntry): EnableCheck {
        val stored = entry.rule ?: return EnableCheck.Unreadable
        val now = System.currentTimeMillis()
        val rule = (if (stored.isExpired(now)) stored.withRestartedWindow(now) else stored).copy(enabled = true)
        if (lockMissingFor(rule)) return EnableCheck.NeedsAppLock
        return if (confirmations.needsConfirmation(rule) && !confirmations.isConfirmed(rule)) {
            EnableCheck.NeedsConfirmation(rule)
        } else {
            EnableCheck.Ready(rule)
        }
    }

    /**
     * Turns [rule] (from [checkEnable]) on; false (nothing changed) when it sends messages off the phone and no app
     * lock is set up any more. A re-used rule is saved with its new period.
     */
    fun enable(entry: RuleEntry, rule: Rule, confirmed: Boolean = false): Boolean {
        if (lockMissingFor(rule)) return false
        if (confirmed) confirmations.confirm(rule)
        holds.clear(entry.stored.id)
        val restarted = rule.activeWindow() != entry.rule?.activeWindow()
        viewModelScope.launch {
            if (restarted) rules.save(rule) else rules.setEnabled(entry.stored.id, true)
            forwardingStatus.refresh()
        }
        return true
    }

    fun disable(entry: RuleEntry) {
        viewModelScope.launch {
            rules.setEnabled(entry.stored.id, false)
            forwardingStatus.refresh()
        }
    }

    /** True when [rule] would send messages off the phone while no app lock is set up. */
    private fun lockMissingFor(rule: Rule): Boolean = rule.enabled && rule.sendsOffDevice() && !outboundGuard.securityReady()

    fun delete(entry: RuleEntry) {
        viewModelScope.launch {
            rules.delete(entry.stored.id)
            forwardingStatus.refresh()
        }
    }

    /** Adds the built-in example rules (disabled, so nothing happens until the user turns one on). */
    fun addPresets() {
        viewModelScope.launch {
            val existing = entries.value.orEmpty().map { it.stored.id }.toSet()
            Presets.all(System.currentTimeMillis())
                .filter { it.id !in existing }
                .forEach { rules.save(it.copy(enabled = false)) }
        }
    }

    /** Cancels one scheduled send (a broadcast copy alone); a birthday wish counts as handled for this year. */
    fun cancelScheduled(send: ScheduledSend) {
        viewModelScope.launch { scheduledActions.cancel(send, wholeBroadcast = false) }
    }

    /** "Change time": moves [send] to [atMillis]; [onResult] gets false for a time in the past or a send that is gone. */
    fun moveScheduled(send: ScheduledSend, atMillis: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(scheduledActions.moveTo(send.id, atMillis)) }
    }

    private companion object {
        const val KEY_PICK_CONSUMED = "scheduled_pick_consumed"
    }
}
