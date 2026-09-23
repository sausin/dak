package app.dak.automation

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dak.R
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.forwarding.ForwardingStatus
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Rule
import app.dak.automations.rule.isExpired
import app.dak.automations.rule.sendsOffDevice
import app.dak.navigation.IntentRoutes
import app.dak.navigation.Routes
import app.dak.notifications.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Security alerts" notifications about automations that send messages off the phone, on their own high-importance
 * channel (created here, like the forwarding status channel, in the app group):
 * - [postReminder]: "Was this you?" some hours after one was turned on ([OutboundReminderWorker]), with "Turn off"
 *   ([OutboundRuleReceiver]) and "See what was sent" (the run history);
 * - [postDisabled]: automations were turned off because no app lock is set up ([OutboundAutomationGuard]);
 * - [postTurnedOff]: confirmation after "Turn off", replacing the reminder.
 */
@Singleton
class OutboundSecurityNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * "Was this you? Auto-forwarding is on": when it was turned on ([enabledAtMillis]), what it does, whether it is
     * still active and how many messages it sent since ([sentCount]).
     */
    fun postReminder(rule: Rule, enabledAtMillis: Long, sentCount: Int, nowMillis: Long = System.currentTimeMillis()) {
        if (!canNotify()) return
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)
        val forwarding = ForwardingSpec.isForwarding(rule)
        val spec = ForwardingSpec.fromRule(rule)
        val active = rule.enabled && !rule.isExpired(nowMillis) &&
            spec?.status(nowMillis) != ForwardingStatus.ENDED
        val title = context.getString(if (forwarding) R.string.fw_remind_title_forwarding else R.string.fw_remind_title_automation)
        val summary = spec?.let { ForwardingStatusNotifier.summaryLine(context, it, nowMillis) } ?: describe(context, rule)
        val text = context.getString(
            R.string.fw_remind_body,
            ForwardingStatusNotifier.formatInstant(context, enabledAtMillis),
            summary,
            context.getString(if (active) R.string.fw_remind_still_active else R.string.fw_remind_ended),
            context.resources.getQuantityString(R.plurals.fw_remind_sent_count, sentCount, sentCount),
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openRoute(routeFor(rule), rule.id.hashCode()))
            .addAction(0, context.getString(R.string.fw_remind_action_history), openRoute(Routes.automationHistory(rule.id), rule.id.hashCode()))
        if (active) builder.addAction(0, context.getString(R.string.fw_remind_action_turn_off), turnOffIntent(rule.id))
        notify(tagFor(rule.id), builder.build())
    }

    /** "N automations that send messages were turned off because <reason>." */
    fun postDisabled(rules: List<Rule>, reason: ForwardingHold) {
        if (rules.isEmpty() || !canNotify()) return
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)
        val text = context.resources.getQuantityString(R.plurals.fw_guard_disabled_body, rules.size, rules.size, reasonText(reason)) +
            " " + context.getString(R.string.fw_guard_disabled_why)
        val route = if (rules.all { ForwardingSpec.isForwarding(it) }) Routes.FORWARDING else Routes.AUTOMATIONS
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setContentTitle(context.getString(R.string.fw_guard_disabled_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openRoute(route, DISABLED_REQUEST_CODE))
            .addAction(0, context.getString(R.string.fw_lock_needed_action), openRoute(Routes.APP_LOCK, DISABLED_REQUEST_CODE + 1))
            .build()
        notify(TAG_DISABLED, notification)
    }

    /** Replaces [ruleId]'s reminder with "Turned off" and what to check next. */
    fun postTurnedOff(rule: Rule) {
        if (!canNotify()) return
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)
        val text = context.getString(R.string.fw_remind_turned_off_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setContentTitle(context.getString(R.string.fw_remind_turned_off_title, rule.name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openRoute(Routes.automationHistory(rule.id), rule.id.hashCode()))
            .build()
        notify(tagFor(rule.id), notification)
    }

    fun cancelReminder(ruleId: String) {
        NotificationManagerCompat.from(context).cancel(tagFor(ruleId), ID)
    }

    /** Row / notification text for an app-lock hold ("app lock was switched off"). */
    fun reasonText(reason: ForwardingHold): String = context.getString(
        when (reason) {
            ForwardingHold.LOCK_OFF -> R.string.fw_guard_reason_lock_off
            ForwardingHold.SCREEN_LOCK_REMOVED -> R.string.fw_guard_reason_screen_lock
            else -> R.string.fw_guard_reason_lock_needed
        },
    )

    private fun turnOffIntent(ruleId: String): PendingIntent {
        val intent = Intent(context, OutboundRuleReceiver::class.java)
            .setAction(OutboundRuleReceiver.ACTION_TURN_OFF)
            // A distinct data URI per rule keeps each rule's PendingIntent separate (extras do not count).
            .setData(Uri.parse("dak://outbound-rule/" + Uri.encode(ruleId)))
            .putExtra(OutboundRuleReceiver.EXTRA_RULE_ID, ruleId)
        return PendingIntent.getBroadcast(
            context,
            ruleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openRoute(route: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        IntentRoutes.open(context, route),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(tag: String, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(tag, ID, notification)
        } catch (e: SecurityException) {
            // Notifications revoked between the check and the call; the screens still show the rules' state.
        }
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.fw_channel_security_name))
            .setDescription(context.getString(R.string.fw_channel_security_description))
            .setGroup(NotificationChannels.GROUP_APP)
            .setShowBadge(true)
            .build()
        manager.createNotificationChannel(channel)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    companion object {
        const val CHANNEL_ID = "security_alerts"
        private const val ID = 41_101
        private const val TAG_DISABLED = "outbound-disabled"
        private const val DISABLED_REQUEST_CODE = 41_101

        private fun tagFor(ruleId: String) = "outbound-reminder:$ruleId"

        /** Where a rule is managed: the Forwarding screen for forwarding rules, Automations otherwise. */
        fun routeFor(rule: Rule): String = if (ForwardingSpec.isForwarding(rule)) Routes.FORWARDING else Routes.AUTOMATIONS

        /** "Night replies: auto-reply by SMS, send to hooks.example.com" for a rule that is not a forwarding rule. */
        fun describe(context: Context, rule: Rule): String {
            val parts = rule.actions.filter { it.sendsOffDevice() }.map { action ->
                when (action) {
                    is ActionSpec.ForwardSms -> context.getString(R.string.fw_kind_forward, action.to)
                    is ActionSpec.ScheduleReply -> context.getString(R.string.fw_kind_reply)
                    is ActionSpec.Webhook -> context.getString(R.string.fw_kind_webhook, runCatching { Uri.parse(action.url).host }.getOrNull() ?: action.url)
                    is ActionSpec.RelayToWebClient -> context.getString(R.string.fw_kind_web_client)
                    is ActionSpec.RelayRule -> context.getString(R.string.fw_kind_relay, action.recipient)
                    is ActionSpec.LaunchIntent -> context.getString(R.string.fw_kind_open)
                    else -> context.getString(R.string.fw_kind_unknown)
                }
            }.distinct()
            return context.getString(R.string.fw_generic_summary, rule.name, parts.joinToString(", "))
        }
    }
}
