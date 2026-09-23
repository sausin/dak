package app.dak.ui.forwarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import app.dak.automation.ForwardingStatusNotifier
import app.dak.automation.OtpForwardConfirmations
import app.dak.automation.RuleRepository
import app.dak.automations.forwarding.ForwardingSource
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.rule.Rule
import app.dak.automations.safety.RuleValidator
import app.dak.core.model.SimInfo
import app.dak.index.ConversationSummary
import app.dak.index.InboxTab
import app.dak.index.enrich.ConversationIds
import app.dak.index.repo.ConversationRepository
import app.dak.premium.Entitlements
import app.dak.telephony.SimRepository
import app.dak.ui.automations.SaveCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** One row of the "Forwarding rules" list. */
data class ForwardingRow(val id: String, val spec: ForwardingSpec, val rule: Rule)

/**
 * Forwarding rules: list, editor and source-channel picker. Rules are ordinary automation rules
 * (see [ForwardingSpec]); saving one that includes OTPs needs the same biometric confirmation as any OTP-forwarding
 * rule ([OtpForwardConfirmations]). Every change refreshes the "Forwarding active" notification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForwardingViewModel @Inject constructor(
    private val rules: RuleRepository,
    private val confirmations: OtpForwardConfirmations,
    private val entitlements: Entitlements,
    private val conversations: ConversationRepository,
    private val status: ForwardingStatusNotifier,
    sims: SimRepository,
) : ViewModel() {

    private val simRepository = sims

    val rows: StateFlow<List<ForwardingRow>?> = rules.observe().map { entries ->
        entries.mapNotNull { entry ->
            val rule = entry.rule ?: return@mapNotNull null
            ForwardingSpec.fromRule(rule)?.let { ForwardingRow(entry.stored.id, it.copy(enabled = entry.stored.enabled), rule) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val simList: StateFlow<List<SimInfo>> = sims.sims

    private val sourceQuery = MutableStateFlow("")

    /** Conversations for the channel picker (folded sender groups and people), filtered by [setSourceQuery]. */
    val sourceCandidates: Flow<PagingData<ConversationSummary>> = sourceQuery.flatMapLatest { query ->
        val q = query.trim()
        conversations.conversations(InboxTab.ALL).map { page ->
            if (q.isEmpty()) page else page.filter { it.title.contains(q, ignoreCase = true) || it.address.contains(q, ignoreCase = true) }
        }
    }.cachedIn(viewModelScope)

    fun setSourceQuery(query: String) {
        sourceQuery.value = query
    }

    /** Builds the source for a picked conversation, with its raw sender addresses (for loop checks). */
    suspend fun sourceOf(summary: ConversationSummary): ForwardingSource = ForwardingSource(
        conversationId = summary.conversationId,
        name = summary.title,
        mergeKey = ConversationIds.mergeKeyOf(summary.conversationId),
        addresses = runCatching { conversations.addressesFor(summary.conversationId) }.getOrDefault(emptyList())
            .ifEmpty { listOf(summary.address) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_ADDRESSES),
    )

    /** Validates [spec]; saves it right away unless including OTPs needs a biometric confirmation first. */
    fun trySave(spec: ForwardingSpec): SaveCheck {
        val rule = spec.toRule(System.currentTimeMillis()) { UUID.randomUUID().toString() } ?: return SaveCheck.Incomplete
        val own = simRepository.sims.value.mapNotNull { it.number }.filter { it.isNotBlank() }.toSet()
        val issues = RuleValidator.validate(rule, entitlements, own)
        if (issues.isNotEmpty()) return SaveCheck.Invalid(issues)
        if (confirmations.needsConfirmation(rule) && !confirmations.isConfirmed(rule)) return SaveCheck.NeedsConfirmation(rule)
        save(rule)
        return SaveCheck.Saved
    }

    /** Called after a successful biometric check for [rule]. */
    fun confirmAndSave(rule: Rule) {
        confirmations.confirm(rule)
        save(rule)
    }

    /** True when turning [row] on needs a biometric confirmation first (it includes OTPs). */
    fun needsConfirmationToEnable(row: ForwardingRow): Boolean =
        confirmations.needsConfirmation(row.rule) && !confirmations.isConfirmed(row.rule)

    fun setEnabled(row: ForwardingRow, enabled: Boolean, confirmed: Boolean = false) {
        if (confirmed) confirmations.confirm(row.rule)
        viewModelScope.launch {
            rules.setEnabled(row.id, enabled)
            status.refresh()
        }
    }

    fun delete(row: ForwardingRow) {
        viewModelScope.launch {
            rules.delete(row.id)
            status.refresh()
        }
    }

    private fun save(rule: Rule) {
        viewModelScope.launch {
            rules.save(rule)
            status.refresh()
        }
    }

    private companion object {
        const val MAX_ADDRESSES = 50
    }
}
