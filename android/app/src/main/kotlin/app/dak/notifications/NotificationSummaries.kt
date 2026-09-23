package app.dak.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dak.R
import app.dak.core.model.Category
import app.dak.navigation.IntentRoutes
import app.dak.navigation.Routes
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.ui.common.text.BidiText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One group per message category. Every message notification carries [groupKey]; once more than
 * [SUMMARY_THRESHOLD] are active in a category, an InboxStyle summary bundles them. The summary never alerts
 * (children do) and is refreshed whenever a child is posted or cancelled; it is only cancelled once it has no
 * children left (cancelling a summary would cancel its children too).
 */
@Singleton
class NotificationSummaries @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {

    companion object {
        const val SUMMARY_THRESHOLD = 3
        private const val GROUP_PREFIX = "app.dak.group."
        private const val SUMMARY_TAG_PREFIX = "summary:"
        private const val ID_SUMMARY = 3
        private const val MAX_LINES = 6

        fun groupKey(category: Category): String = GROUP_PREFIX + category.name.lowercase()
    }

    /** Posts, updates or clears the summary of [category]'s group after a child changed. */
    fun refresh(category: Category) {
        val platform = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { platform.activeNotifications.toList() }.getOrDefault(emptyList())
        refresh(category, active)
    }

    /** Refreshes every category's summary (after notifications were cancelled in bulk). */
    fun refreshAll() {
        val platform = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { platform.activeNotifications.toList() }.getOrDefault(emptyList())
        Category.entries.forEach { refresh(it, active) }
    }

    private fun refresh(category: Category, active: List<StatusBarNotification>) {
        val key = groupKey(category)
        val tag = SUMMARY_TAG_PREFIX + key
        val inGroup = active.filter { it.notification.group == key }
        val children = inGroup.filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
        val hasSummary = inGroup.any { it.tag == tag && it.id == ID_SUMMARY }
        val manager = NotificationManagerCompat.from(context)
        when {
            children.isEmpty() -> if (hasSummary) manager.cancel(tag, ID_SUMMARY)
            children.size > SUMMARY_THRESHOLD || hasSummary -> post(manager, category, key, tag, children)
        }
    }

    private fun post(manager: NotificationManagerCompat, category: Category, key: String, tag: String, children: List<StatusBarNotification>) {
        val newest = children.maxByOrNull { it.notification.`when` } ?: return
        val title = context.getString(titleOf(category))
        val count = context.resources.getQuantityString(R.plurals.ch_summary_count, children.size, children.size)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title).setSummaryText(count)
        children.sortedByDescending { it.notification.`when` }.take(MAX_LINES).forEach { sbn ->
            val extras = sbn.notification.extras
            val who = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)
            val what = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)
            // Each part isolated (FSI…PDI): an Arabic or Urdu sender name cannot swap places with the ": " and body.
            style.addLine(listOfNotNull(who, what).joinToString(": ") { BidiText.isolate(it.toString()) })
        }
        val open = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            IntentRoutes.open(context, Routes.INBOX),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = newest.notification.channelId ?: NotificationChannels.forCategory(category)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(if (category == Category.OTP) R.drawable.ic_stat_otp else R.drawable.ic_stat_dak)
            .setColor(ContextCompat.getColor(context, R.color.dak_notification_accent))
            .setContentTitle(title)
            .setContentText(count)
            .setStyle(style)
            .setNumber(children.size)
            .setWhen(newest.notification.`when`)
            .setShowWhen(true)
            .setGroup(key)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (settings.get(DakSettings.lockScreenPrivacy) == "full") {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_stat_dak)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(count)
                    .build(),
            )
        }
        try {
            manager.notify(tag, ID_SUMMARY, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked meanwhile; children could not be shown either.
        }
    }

    private fun titleOf(category: Category): Int = when (category) {
        Category.PERSONAL -> R.string.channel_personal
        Category.OTP -> R.string.channel_otp
        Category.TRANSACTION -> R.string.channel_alerts
        Category.PROMOTION -> R.string.channel_promotions
        Category.SPAM -> R.string.channel_spam
        Category.UNKNOWN -> R.string.channel_other
    }
}
