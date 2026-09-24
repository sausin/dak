package app.dak.ui.forwarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import app.dak.automation.ForwardingContacts
import app.dak.automation.ForwardingHold
import app.dak.automation.ForwardingHolds
import app.dak.automation.ForwardingStatusNotifier
import app.dak.automation.OtpForwardConfirmations
import app.dak.automation.OutboundAutomationGuard
import app.dak.automation.RecipientContactCheck
import app.dak.automation.RuleRepository
import app.dak.automations.forwarding.ForwardingPolicy
import app.dak.automations.forwarding.ForwardingRecipient
import app.dak.automations.forwarding.ForwardingSource
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.forwarding.RecipientRisk
import app.dak.automations.forwarding.RecipientRiskHeuristic
import app.dak.automations.rule.Rule
import app.dak.automations.rule.conditionsCanMatchOtp
import app.dak.automations.rule.sendsOffDevice
import app.dak.automations.rule.withRestartedWindow
import app.dak.automations.safety.RuleValidator
import app.dak.automations.safety.ValidationIssue
import app.dak.core.model.SimInfo
import app.dak.index.ConversationSummary
import app.dak.index.InboxTab
import app.dak.index.enrich.ConversationIds
import app.dak.index.repo.ConversationRepository
import app.dak.premium.Entitlements
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * One row of the "Forwarding rules" list. [hold] is why the app paused it (recipient left contacts, no contacts
 * access); [unconfirmed] is an enabled rule that forwards nothing until the user confirms it (e.g. a long rule saved
 * before the 1-hour limit).
 */
data class ForwardingRow(
    val id: String,
    val spec: ForwardingSpec,
    val rule: Rule,
    val hold: ForwardingHold? = null,
    val unconfirmed: Boolean = false,
)

/** Why a save or enable needs the high-risk warning and a biometric check. Empty = no extra step. */
data class ForwardingRisk(
    val longPeriod: Boolean = false,
    val openEnded: Boolean = false,
    val extends: Boolean = false,
    val includesOtp: Boolean = false,
    val riskyRecipients: Map<ForwardingRecipient, Set<RecipientRisk>> = emptyMap(),
)

/** Outcome of [ForwardingViewModel.checkSave] / [ForwardingViewModel.checkEnable] / [ForwardingViewModel.checkReuse]. */
sealed interface ForwardingCheck {
    /** A required field is missing, or a recipient was not picked from contacts. */
    data object Incomplete : ForwardingCheck
    /** No app lock is set up: forwarding stays off ("Set up app lock first", see [OutboundAutomationGuard]). */
    data object NeedsAppLock : ForwardingCheck
    data class Invalid(val issues: List<ValidationIssue>) : ForwardingCheck
    /** Contacts access is needed to verify recipients. */
    data object NeedsContactsAccess : ForwardingCheck
    /** A recipient is no longer a contact ([recipient] is its label). */
    data class NotAContact(val recipient: String) : ForwardingCheck
    /** Show the risk warning for [risk], then a biometric check, then [ForwardingViewModel.confirmAndSave]. */
    data class NeedsConfirmation(val rule: Rule, val risk: ForwardingRisk) : ForwardingCheck
    /** Nothing extra needed: call [ForwardingViewModel.save] / [ForwardingViewModel.setEnabled]. */
    data class Ready(val rule: Rule) : ForwardingCheck
}

/**
 * Forwarding rules: list, editor and source-channel picker. Rules are ordinary automation rules (see [ForwardingSpec]).
 * Recipients can only be picked from contacts. Saving (or enabling) a rule that runs longer than an hour or until
 * stopped, extends an existing rule, includes OTPs or goes to a risky-looking contact ([RecipientRiskHeuristic]) shows
 * a scam warning and needs a biometric / screen-lock confirmation ([OtpForwardConfirmations]). Turning forwarding on
 * in any way needs an app lock ([OutboundAutomationGuard]); the check runs again when the change is applied, after
 * the warning and the biometric check. An ended rule stays saved and can be used again ([checkReuse]): a fresh period
 * of the same length from now, through the same checks. Every change refreshes the "Forwarding active" notification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForwardingViewModel @Inject constructor(
    private val rules: RuleRepository,
    private val confirmations: OtpForwardConfirmations,
    private val entitlements: Entitlements,
    private val conversations: ConversationRepository,
    private val status: ForwardingStatusNotifier,
    private val contacts: ForwardingContacts,
    private val holds: ForwardingHolds,
    private val outboundGuard: OutboundAutomationGuard,
    sims: SimRepository,
) : ViewModel() {

    private val simRepository = sims

    val rows: StateFlow<List<ForwardingRow>?> = rules.observe().map { entries ->
        entries.mapNotNull { entry ->
            val rule = entry.rule ?: return@mapNotNull null
            val spec = ForwardingSpec.fromRule(rule)?.copy(enabled = entry.stored.enabled) ?: return@mapNotNull null
            ForwardingRow(
                id = entry.stored.id,
                spec = spec,
                rule = rule,
                hold = if (entry.stored.enabled) null else holds.reason(entry.stored.id),
                unconfirmed = entry.stored.enabled && !confirmations.isConfirmed(rule),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val simList: StateFlow<List<SimInfo>> = sims.sims

    private val risks = MutableStateFlow<Map<String, Set<RecipientRisk>>>(emptyMap())

    /** Risk flags of recipients assessed so far, by number (see [assessRecipients]). */
    val recipientRisks: StateFlow<Map<String, Set<RecipientRisk>>> = risks.asStateFlow()

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

    fun canReadContacts(): Boolean = contacts.canReadContacts()

    /** The recipient behind a contact-picker result, or null when it has no usable number. */
    suspend fun recipientFromPicker(uri: Uri): ForwardingRecipient? = contacts.readPicked(uri)

    /** Assesses [recipients] not assessed yet, for the editor's inline warnings. */
    fun assessRecipients(recipients: List<ForwardingRecipient>, subId: Int?) {
        val pending = recipients.filter { it.fromContacts && it.number !in risks.value }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            for (r in pending) {
                val flags = riskOf(r, subId)
                risks.update { it + (r.number to flags) }
            }
        }
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

    /**
     * Validates [spec] (edited from [original], null for a new rule) and decides whether saving needs the warning and
     * a biometric check. A start in the past is moved to now, so the "1 hour" limit counts from when forwarding can
     * actually happen.
     */
    suspend fun checkSave(spec: ForwardingSpec, original: ForwardingSpec?): ForwardingCheck {
        val now = System.currentTimeMillis()
        if (!spec.recipientsFromContacts) return ForwardingCheck.Incomplete
        val rebased = if (spec.startMillis < now) spec.copy(startMillis = now) else spec
        val rule = rebased.toRule(now) { UUID.randomUUID().toString() } ?: return ForwardingCheck.Incomplete
        if (rule.enabled && rule.sendsOffDevice() && !outboundGuard.securityReady()) return ForwardingCheck.NeedsAppLock
        val own = simRepository.sims.value.mapNotNull { it.number }.filter { it.isNotBlank() }.toSet()
        val issues = RuleValidator.validate(rule, entitlements, own)
        if (issues.isNotEmpty()) return ForwardingCheck.Invalid(issues)
        contactProblem(rule.id, rebased.recipients)?.let { return it }
        val risk = riskOf(rebased, rule, extends = ForwardingPolicy.extends(original, rebased))
        val needsAuth = (confirmations.needsConfirmation(rule) && !confirmations.isConfirmed(rule)) ||
            risk.extends || risk.riskyRecipients.isNotEmpty()
        return if (needsAuth) ForwardingCheck.NeedsConfirmation(rule, risk) else ForwardingCheck.Ready(rule)
    }

    /** Turning [row] on (or confirming a rule that is on but unconfirmed): recipients must still be contacts. */
    suspend fun checkEnable(row: ForwardingRow): ForwardingCheck {
        if (!outboundGuard.securityReady()) return ForwardingCheck.NeedsAppLock
        contactProblem(row.id, row.spec.recipients)?.let { return it }
        if (!confirmations.needsConfirmation(row.rule) || confirmations.isConfirmed(row.rule)) return ForwardingCheck.Ready(row.rule)
        return ForwardingCheck.NeedsConfirmation(row.rule, riskOf(row.spec, row.rule, extends = false))
    }

    /**
     * "Use again" for an ended rule: the same rule with a fresh period of the same length starting now
     * ([withRestartedWindow]), through the same checks as a save (app lock, contacts, the warning and biometric check
     * for a long period, OTPs or a risky recipient). Not an extension: the period is no longer than before.
     */
    suspend fun checkReuse(row: ForwardingRow): ForwardingCheck {
        if (!outboundGuard.securityReady()) return ForwardingCheck.NeedsAppLock
        val now = System.currentTimeMillis()
        val rule = row.rule.withRestartedWindow(now).copy(enabled = true)
        val spec = ForwardingSpec.fromRule(rule) ?: return ForwardingCheck.Incomplete
        if (!spec.recipientsFromContacts) return ForwardingCheck.Incomplete
        contactProblem(rule.id, spec.recipients)?.let { return it }
        val risk = riskOf(spec, rule, extends = false)
        val needsAuth = (confirmations.needsConfirmation(rule) && !confirmations.isConfirmed(rule)) || risk.riskyRecipients.isNotEmpty()
        return if (needsAuth) ForwardingCheck.NeedsConfirmation(rule, risk) else ForwardingCheck.Ready(rule)
    }

    /** Saves [rule]; false (nothing saved) when it would turn forwarding on while no app lock is set up. */
    fun save(rule: Rule): Boolean {
        if (rule.enabled && rule.sendsOffDevice() && !outboundGuard.securityReady()) return false
        holds.clear(rule.id)
        viewModelScope.launch {
            rules.save(rule)
            status.refresh()
        }
        return true
    }

    /** Called after the warning and a successful biometric check for [rule]; false as for [save]. */
    fun confirmAndSave(rule: Rule): Boolean {
        if (rule.enabled && rule.sendsOffDevice() && !outboundGuard.securityReady()) return false
        confirmations.confirm(rule)
        return save(rule)
    }

    /** Turns [row] on or off; false (nothing changed) when turning it on while no app lock is set up. */
    fun setEnabled(row: ForwardingRow, enabled: Boolean, confirmed: Boolean = false): Boolean {
        if (enabled && !outboundGuard.securityReady()) return false
        if (confirmed) confirmations.confirm(row.rule)
        if (enabled) holds.clear(row.id)
        viewModelScope.launch {
            rules.setEnabled(row.id, enabled)
            status.refresh()
        }
        return true
    }

    fun delete(row: ForwardingRow) {
        viewModelScope.launch {
            rules.delete(row.id)
            status.refresh()
        }
    }

    private suspend fun contactProblem(ruleId: String, recipients: List<ForwardingRecipient>): ForwardingCheck? {
        for (r in recipients) {
            when (contacts.check(ruleId, r)) {
                RecipientContactCheck.OK -> Unit
                RecipientContactCheck.NO_ACCESS -> return ForwardingCheck.NeedsContactsAccess
                RecipientContactCheck.NOT_A_CONTACT -> return ForwardingCheck.NotAContact(r.label)
            }
        }
        return null
    }

    private suspend fun riskOf(spec: ForwardingSpec, rule: Rule, extends: Boolean): ForwardingRisk {
        val risky = spec.recipients.associateWith { riskOf(it, spec.subId) }.filterValues { it.isNotEmpty() }
        risks.update { current -> current + spec.recipients.map { it.number to (risky[it] ?: emptySet()) } }
        return ForwardingRisk(
            longPeriod = spec.endMillis != null && spec.isLongPeriod,
            openEnded = spec.endMillis == null,
            extends = extends,
            includesOtp = conditionsCanMatchOtp(rule.conditions),
            riskyRecipients = risky,
        )
    }

    private suspend fun riskOf(recipient: ForwardingRecipient, subId: Int?): Set<RecipientRisk> {
        val sims = simRepository.sims.value
        val country = (sims.firstOrNull { it.subId == subId } ?: sims.firstOrNull { it.isActive })?.countryIso
        return RecipientRiskHeuristic.assess(contacts.factsFor(recipient, country), System.currentTimeMillis())
    }

    private companion object {
        const val MAX_ADDRESSES = 50
    }
}
