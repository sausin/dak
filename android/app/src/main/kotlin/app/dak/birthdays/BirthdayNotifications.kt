package app.dak.birthdays

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dak.R
import app.dak.automation.ScheduledSendReceiver
import app.dak.automations.birthdays.OccasionKind
import app.dak.automations.birthdays.WishTag
import app.dak.navigation.IntentRoutes
import app.dak.navigation.Routes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Ask me first" prompt on the day: "Ravi's birthday today" with the prepared wish and Send / Edit / Skip.
 * Send and Skip go to [ScheduledSendReceiver] (already registered, not exported); Edit opens the composer
 * pre-filled.
 */
@Singleton
class BirthdayNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    /** Posts the prompt; false when notifications are not allowed. */
    fun prompt(tag: WishTag, name: String, number: String, body: String, subId: Int): Boolean {
        if (!canNotify()) return false
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)
        val id = notificationId(tag)
        val title = context.getString(
            if (tag.kind == OccasionKind.ANNIVERSARY) R.string.fw_bd_prompt_title_anniversary else R.string.fw_bd_prompt_title,
            name,
        )
        val send = broadcast(ScheduledSendReceiver.ACTION_BIRTHDAY_SEND, tag, number, body, subId, id)
        val skip = broadcast(ScheduledSendReceiver.ACTION_BIRTHDAY_SKIP, tag, number, body, subId, id)
        val edit = PendingIntent.getActivity(
            context,
            id,
            IntentRoutes.open(context, Routes.compose(to = number, body = body)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(edit)
            .addAction(0, context.getString(R.string.fw_bd_action_send), send)
            .addAction(0, context.getString(R.string.fw_bd_action_edit), edit)
            .addAction(0, context.getString(R.string.fw_bd_action_skip), skip)
            .build()
        return try {
            manager.notify(TAG, id, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(TAG, notificationId)
    }

    private fun broadcast(action: String, tag: WishTag, number: String, body: String, subId: Int, id: Int): PendingIntent {
        val intent = Intent(context, ScheduledSendReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_TAG, tag.encode())
            .putExtra(EXTRA_NUMBER, number)
            .putExtra(EXTRA_BODY, body)
            .putExtra(EXTRA_SUB_ID, subId)
            .putExtra(EXTRA_NOTIFICATION_ID, id)
        val requestCode = id * 2 + if (action == ScheduledSendReceiver.ACTION_BIRTHDAY_SEND) 0 else 1
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.fw_channel_birthdays_name))
                .setDescription(context.getString(R.string.fw_channel_birthdays_description))
                .build(),
        )
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
        const val CHANNEL_ID = "birthday_wishes"
        const val EXTRA_TAG = "app.dak.extra.BIRTHDAY_TAG"
        const val EXTRA_NUMBER = "app.dak.extra.BIRTHDAY_NUMBER"
        const val EXTRA_BODY = "app.dak.extra.BIRTHDAY_BODY"
        const val EXTRA_SUB_ID = "app.dak.extra.BIRTHDAY_SUB_ID"
        const val EXTRA_NOTIFICATION_ID = "app.dak.extra.BIRTHDAY_NOTIFICATION_ID"
        private const val TAG = "birthday"
        private const val BASE_ID = 42_000

        /** Stable per contact + occasion, within a 20k range that other Dak notifications do not use. */
        fun notificationId(tag: WishTag): Int =
            BASE_ID + ((tag.contactId * 2 + tag.kind.ordinal) % 20_000L).toInt().let { if (it < 0) -it else it }
    }
}
