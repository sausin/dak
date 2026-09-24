package app.dak.ui.forwarding

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.R
import app.dak.automation.AutomationRunLog
import app.dak.automation.RuleRepository
import app.dak.automations.history.RunRecord
import app.dak.automations.history.SkipReason
import app.dak.core.model.MessageKey
import app.dak.index.repo.AutomationRunStore
import app.dak.index.repo.ConversationRepository
import app.dak.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the automation history screen: the run log ([AutomationRunStore]) of one rule ([Routes.ARG_RULE_ID]) or, with
 * no rule, of every rule including deleted ones (their name is a snapshot in each row). Newest first.
 */
@HiltViewModel
class AutomationHistoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    runs: AutomationRunStore,
    rules: RuleRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {

    /** The rule whose history this is, or null for the history of all rules. */
    val ruleId: String? = savedState.get<String>(Routes.ARG_RULE_ID)?.let(Uri::decode)?.takeIf { it.isNotBlank() }

    val records: StateFlow<List<RunRecord>?> = (if (ruleId != null) runs.forRule(ruleId) else runs.all())
        .map { rows -> rows.map(AutomationRunLog::toRecord) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The rule's current name (null for all rules, or when it was deleted: the rows' snapshot is used then). */
    val ruleName: StateFlow<String?> = flow { emit(ruleId?.let { runCatching { rules.get(it) }.getOrNull()?.name }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Route to the source message of [record] in its conversation (highlighted), or null when it is gone. */
    suspend fun routeTo(record: RunRecord): String? {
        val key = record.messageKey?.let { MessageKey.parse(it) }
        val conversationId = key?.let { runCatching { conversations.conversationIdOf(it) }.getOrNull() } ?: record.conversationId
        return conversationId?.let { Routes.conversation(it, highlight = key?.toString()) }
    }

    companion object {
        /** The sentence for a skip / failure reason code, or null to show none (a raw failure text is not shown). */
        fun reasonText(reason: String?): Int? = when (reason) {
            SkipReason.NOT_CONFIRMED -> R.string.fw_skip_not_confirmed
            SkipReason.NO_APP_LOCK -> R.string.fw_skip_no_app_lock
            SkipReason.CONTACT_REMOVED -> R.string.fw_skip_contact_removed
            SkipReason.NO_CONTACTS_ACCESS -> R.string.fw_skip_no_contacts_access
            SkipReason.POSSIBLE_SCAM -> R.string.fw_skip_possible_scam
            SkipReason.PREMIUM_LOCKED -> R.string.fw_skip_premium
            SkipReason.INCOGNITO_CHAT -> R.string.fw_skip_incognito
            else -> null
        }
    }
}
