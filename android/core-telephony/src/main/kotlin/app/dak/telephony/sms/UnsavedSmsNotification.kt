package app.dak.telephony.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dak.telephony.R
import app.dak.telephony.internal.TAG

/**
 * Tells the user that an incoming SMS could not be saved and was given up on ([SmsJournal] quarantine): losing a
 * message silently is worse than admitting it. Carries no message content (the PDUs may be hostile).
 */
internal object UnsavedSmsNotification {
    private const val CHANNEL_ID = "dak_sms_problems"
    private const val NOTIFICATION_ID = 0x0D4D

    fun post(context: Context, count: Int) {
        if (count <= 0) return
        try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            ensureChannel(context)
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val content = launch?.let { PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.dak_telephony_sms_unsaved_title))
                .setContentText(context.resources.getQuantityString(R.plurals.dak_telephony_sms_unsaved_text, count, count))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked.
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not post unsaved-SMS notification: ${e.javaClass.simpleName}")
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.dak_telephony_channel_problems), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}
