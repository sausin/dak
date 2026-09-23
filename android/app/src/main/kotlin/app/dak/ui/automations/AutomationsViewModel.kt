package app.dak.ui.automations

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.automation.ForwardingStatusNotifier
import app.dak.automation.OtpForwardConfirmations
import app.dak.automation.RuleEntry
import app.dak.automation.RuleRepository
import app.dak.automation.ScheduledSendScheduler
import app.dak.automations.presets.Presets
import app.dak.automations.rule.Rule
import app.dak.automations.safety.RuleValidator
import app.dak.automations.safety.ValidationIssue
import app.dak.core.model.SimInfo
import app.dak.index.repo.ScheduledSend
import app.dak.index.repo.ScheduledSendStore
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
    data object Saved : SaveCheck
}

/**
 * Automations: rules list with enable toggles, the simple rule editor, presets, scheduled sends and the exact
 * alarm permission prompt. OTP-forwarding rules need a biometric confirmation to be created, to change recipient
 * and to be re-enabled ([OtpForwardConfirmations]).
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
) : ViewModel() {

    val entries: StateFlow<List<RuleEntry>?> = rules.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scheduled: StateFlow<List<ScheduledSend>> = scheduledSends.pending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        if (confirmations.needsConfirmation(rule) && !confirmations.isConfirmed(rule)) return SaveCheck.NeedsConfirmation(rule)
        viewModelScope.launch { rules.save(rule) }
        return SaveCheck.Saved
    }

    /** Called after a successful biometric check for [rule]. */
    fun confirmAndSave(rule: Rule) {
        confirmations.confirm(rule)
        viewModelScope.launch { rules.save(rule) }
    }

    /** True when enabling [entry] needs a biometric confirmation first. */
    fun needsConfirmationToEnable(entry: RuleEntry): Boolean {
        val rule = entry.rule ?: return false
        return confirmations.needsConfirmation(rule) && !confirmations.isConfirmed(rule)
    }

    fun setEnabled(entry: RuleEntry, enabled: Boolean, confirmed: Boolean = false) {
        if (confirmed) entry.rule?.let { confirmations.confirm(it) }
        viewModelScope.launch {
            rules.setEnabled(entry.stored.id, enabled)
            forwardingStatus.refresh()
        }
    }

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

    fun cancelScheduled(send: ScheduledSend) {
        viewModelScope.launch { scheduler.cancel(send.id) }
    }
}
