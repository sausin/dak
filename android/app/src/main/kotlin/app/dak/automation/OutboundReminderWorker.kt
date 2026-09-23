package app.dak.automation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.dak.automations.history.RunOutcome
import app.dak.automations.rule.isExpired
import app.dak.automations.rule.sendsOffDevice
import app.dak.automations.safety.OutboundAutomations
import app.dak.index.repo.AutomationRunStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The "Was this you?" reminder for one automation that sends messages off the phone (armed by
 * [OutboundRuleReminders]). Nothing happens if the rule was deleted. Otherwise it posts the security alert with what
 * the rule does, whether it is still on and how many messages it sent since it was turned on; while the rule stays on,
 * it re-arms itself for [OutboundAutomations.REMINDER_REPEAT_MILLIS] later. A rule the app paused by itself (contact
 * removed) that sent nothing is not reported; one whose period simply ended still is, so a short forward set up by
 * someone else does not go unnoticed. WorkManager timing is inexact under Doze.
 */
@HiltWorker
class OutboundReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val rules: RuleRepository,
    private val runs: AutomationRunStore,
    private val alerts: OutboundSecurityNotifier,
    private val reminders: OutboundRuleReminders,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ruleId = inputData.getString(KEY_RULE_ID) ?: return Result.success()
        val now = System.currentTimeMillis()
        val enabledAt = inputData.getLong(KEY_ENABLED_AT, 0L).takeIf { it in 1..now } ?: now
        val rule = runCatching { rules.get(ruleId) }.getOrNull() ?: return Result.success()
        if (!rule.sendsOffDevice()) return Result.success()
        val sent = runCatching { runs.count(ruleId, RunOutcome.SENT.name, enabledAt) }.getOrDefault(0)
        val stillOn = rule.enabled && !rule.isExpired(now)
        if (!rule.enabled && sent == 0 && !rule.isExpired(now)) return Result.success()
        alerts.postReminder(rule, enabledAt, sent, now)
        if (stillOn) reminders.arm(ruleId, enabledAt, OutboundAutomations.REMINDER_REPEAT_MILLIS, append = true)
        return Result.success()
    }

    companion object {
        const val TAG = "outbound-reminder"
        const val KEY_RULE_ID = "ruleId"
        const val KEY_ENABLED_AT = "enabledAt"
    }
}
