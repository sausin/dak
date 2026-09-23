package app.dak.automation

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.dak.automations.rule.Rule
import app.dak.automations.rule.sendsOffDevice
import app.dak.automations.safety.OutboundAutomations
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the "Was this you?" reminder ([OutboundReminderWorker]) for an automation that sends messages off the phone:
 * [OutboundAutomations.REMINDER_DELAY_MILLIS] (3 hours) after it is turned on, then daily while it stays on. The
 * owner of a phone someone tampered with (a forward of bank SMS / OTPs set up in a minute on the unlocked phone) finds
 * out the same day. One unique WorkManager job per rule id ([nameFor]); arming again replaces it.
 *
 * Armed centrally by [RuleRepository] (every save/enable that turns outbound sending on) and by
 * [OtpForwardConfirmations.confirm] (confirming or extending a high-risk rule); cancelled when the user turns the rule
 * off or deletes it. Deliberately not cancelled when a time-boxed rule simply ends: a one-hour forward set up by
 * someone else must still be reported.
 */
@Singleton
class OutboundRuleReminders @Inject constructor(@ApplicationContext private val context: Context) {

    private val flags by lazy { context.getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE) }

    /**
     * False only when no automation that sends off the phone can be on (none was turned on since the guard last found
     * none): lets the app-start check skip opening the index database. One SharedPreferences read.
     */
    fun mayHaveOutbound(): Boolean = flags.getBoolean(KEY_MAY_HAVE_OUTBOUND, true)

    fun setMayHaveOutbound(value: Boolean) {
        if (mayHaveOutbound() != value) flags.edit().putBoolean(KEY_MAY_HAVE_OUTBOUND, value).apply()
    }

    /** Arms (or re-arms) the reminder for [rule] when it is enabled and sends off the phone. */
    fun armIfOutbound(rule: Rule, nowMillis: Long = System.currentTimeMillis()) {
        if (rule.enabled && rule.sendsOffDevice()) arm(rule.id, enabledAtMillis = nowMillis)
    }

    /**
     * Schedules the reminder for [ruleId] [delayMillis] from now. [enabledAtMillis] is when outbound sending was turned
     * on (shown in the notification and used to count what was sent since); [append] chains it after a running
     * reminder (the worker re-arming itself) instead of replacing it.
     */
    fun arm(
        ruleId: String,
        enabledAtMillis: Long,
        delayMillis: Long = OutboundAutomations.REMINDER_DELAY_MILLIS,
        append: Boolean = false,
    ) {
        setMayHaveOutbound(true)
        runCatching {
            val input = Data.Builder()
                .putString(OutboundReminderWorker.KEY_RULE_ID, ruleId)
                .putLong(OutboundReminderWorker.KEY_ENABLED_AT, enabledAtMillis)
                .build()
            val request = OneTimeWorkRequestBuilder<OutboundReminderWorker>()
                .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setInputData(input)
                .addTag(OutboundReminderWorker.TAG)
                .build()
            val policy = if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
            WorkManager.getInstance(context).enqueueUniqueWork(nameFor(ruleId), policy, request)
        }.onFailure { Log.w(TAG, "could not arm the outbound reminder", it) }
    }

    fun cancel(ruleId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(nameFor(ruleId)) }
            .onFailure { Log.w(TAG, "could not cancel the outbound reminder", it) }
    }

    companion object {
        private const val TAG = "DakOutboundReminder"
        private const val FLAGS_PREFS = "dak_outbound_reminders"
        private const val KEY_MAY_HAVE_OUTBOUND = "may_have_outbound"

        /** Unique work name of [ruleId]'s reminder. */
        fun nameFor(ruleId: String): String = "outbound-reminder:$ruleId"
    }
}
