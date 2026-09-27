package app.dak.automation

import android.content.Context
import android.util.Log
import app.dak.automations.MessageEvent
import app.dak.automations.PlaceholderFormatter
import app.dak.automations.TemplateRenderer
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.history.RunHistory
import app.dak.automations.history.RunOutcome
import app.dak.automations.history.RunRecord
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Rule
import app.dak.index.db.entity.AutomationRunRow
import app.dak.index.repo.AutomationRunStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the automation run log ([AutomationRunStore]) for [AutomationRunner]: one row per outbound action that ran or
 * was skipped for a message, with the destination as the user knows it (the contact name of a forwarding recipient)
 * and a one-line preview of what was (or would have been) sent, OTP codes masked. Never throws: a failed write must not
 * stop the automation.
 */
@Singleton
class AutomationRunLog @Inject constructor(
    private val store: AutomationRunStore,
    @ApplicationContext private val context: Context,
) {

    suspend fun record(rule: Rule, action: ActionSpec, event: MessageEvent, outcome: RunOutcome, reason: String? = null) {
        runCatching {
            // The preview renders `{time}`, `{amount}` and `{sim}` as the sent text does: in the app language.
            store.add(rowFor(rule, action, event, outcome, reason, System.currentTimeMillis(), formatter = AppPlaceholderFormatter(context)))
        }
            .onFailure { Log.w(TAG, "could not write the run log", it) }
    }

    /**
     * Logs a queued unattended send (auto-reply, held auto-forward) that the scheduled-send executor dropped at send
     * time. Such a send carries only its origin tag, not the rule, so the row is filed under the tag with [label] as
     * its name.
     */
    suspend fun recordQueued(origin: ScheduledSendOrigin, tag: String, label: String, address: String?, body: String, outcome: RunOutcome, reason: String?) {
        runCatching { store.add(rowForQueued(origin, tag, label, address, body, outcome, reason, System.currentTimeMillis())) }
            .onFailure { Log.w(TAG, "could not write the run log", it) }
    }

    companion object {
        private const val TAG = "DakRunLog"

        /** The row for a queued send dropped at send time (see [recordQueued]). */
        fun rowForQueued(
            origin: ScheduledSendOrigin,
            tag: String,
            label: String,
            address: String?,
            body: String,
            outcome: RunOutcome,
            reason: String?,
            nowMillis: Long,
        ): AutomationRunRow = AutomationRunRow(
            ruleId = tag,
            ruleName = label,
            atMillis = nowMillis,
            messageKey = null,
            conversationId = null,
            sourceLabel = null,
            actionKind = when (origin) {
                ScheduledSendOrigin.AUTO_FORWARD -> ActionSpec.ForwardSms::class.simpleName ?: "ForwardSms"
                ScheduledSendOrigin.AUTO_REPLY -> ActionSpec.ScheduleReply::class.simpleName ?: "ScheduleReply"
                else -> origin.name
            },
            destinationLabel = null,
            destination = address?.trim()?.takeIf { it.isNotEmpty() },
            outcome = outcome.name,
            reason = reason,
            // A held forward carries someone else's message (possibly an OTP) and its code is not known here to mask:
            // no preview. An auto-reply is the user's own text.
            textPreview = if (origin == ScheduledSendOrigin.AUTO_FORWARD) null else RunHistory.preview(body),
        )

        /** The row for one run (pure apart from the default zone used to render `{time}`). */
        fun rowFor(
            rule: Rule,
            action: ActionSpec,
            event: MessageEvent,
            outcome: RunOutcome,
            reason: String?,
            nowMillis: Long,
            zoneId: String = ZoneId.systemDefault().id,
            formatter: PlaceholderFormatter = PlaceholderFormatter.Default,
        ): AutomationRunRow = AutomationRunRow(
            ruleId = rule.id,
            ruleName = rule.name,
            atMillis = nowMillis,
            messageKey = event.messageKey,
            conversationId = event.conversationId,
            sourceLabel = event.address,
            actionKind = RunHistory.kindOf(action),
            destinationLabel = labelOf(rule, action),
            destination = RunHistory.destinationOf(action, replyTo = event.address),
            outcome = outcome.name,
            reason = reason,
            textPreview = sentText(action, event, zoneId, formatter)?.let { RunHistory.preview(it, otpCode = event.otp?.code) },
        )

        /** The contact name of a forwarding recipient, when the rule knows it. */
        fun labelOf(rule: Rule, action: ActionSpec): String? = when (action) {
            is ActionSpec.ForwardSms -> ForwardingSpec.fromRule(rule)?.recipients
                ?.firstOrNull { it.number.trim() == action.to.trim() }
                ?.name?.takeIf { it.isNotBlank() }
            else -> null
        }

        /** What [action] sends for [event]; null for an "open" intent (its URI is not message text). */
        fun sentText(
            action: ActionSpec,
            event: MessageEvent,
            zoneId: String,
            formatter: PlaceholderFormatter = PlaceholderFormatter.Default,
        ): String? = when (action) {
            is ActionSpec.ForwardSms -> TemplateRenderer.render(action.template, event, zoneId, formatter)
            is ActionSpec.Webhook -> TemplateRenderer.render(action.template, event, zoneId, formatter)
            is ActionSpec.RelayToWebClient -> TemplateRenderer.render(action.template, event, zoneId, formatter)
            is ActionSpec.RelayRule -> TemplateRenderer.render(action.template, event, zoneId, formatter)
            is ActionSpec.ScheduleReply -> action.text
            else -> null
        }

        /** The UI model of a stored row. */
        fun toRecord(row: AutomationRunRow): RunRecord = RunRecord(
            id = row.id,
            ruleId = row.ruleId,
            ruleName = row.ruleName,
            atMillis = row.atMillis,
            messageKey = row.messageKey,
            conversationId = row.conversationId,
            sourceLabel = row.sourceLabel,
            actionKind = row.actionKind,
            destinationLabel = row.destinationLabel,
            destination = row.destination,
            outcome = RunOutcome.fromName(row.outcome),
            reason = row.reason,
            textPreview = row.textPreview,
        )
    }
}
