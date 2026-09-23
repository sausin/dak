package app.dak.automation

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dak.R
import app.dak.notifications.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the notifications automations produce: the "notify" action, and the tap-to-open prompt that stands in
 * for launching an activity from the background (Android blocks background activity starts, and WhatsApp
 * one-tap relays must end in the user's own tap by design).
 */
@Singleton
class AutomationNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    private val ids = AtomicInteger(BASE_ID)

    /** Posts a plain notification; false when notifications are not allowed. */
    fun post(title: String, text: String, contentIntent: PendingIntent? = null): Boolean {
        if (!canNotify()) return false
        val notification = NotificationCompat.Builder(context, NotificationChannels.AUTOMATION)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(TAG, nextId(), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Posts "tap to open" for [uri]; false when the URI is unusable or notifications are off. */
    fun postOpen(uri: String, title: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        if (parsed.scheme.isNullOrBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            uri.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return post(title, context.getString(R.string.scr_auto_tap_to_open, parsed.scheme ?: uri), pending)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun nextId(): Int {
        val id = ids.incrementAndGet()
        if (id > BASE_ID + 10_000) ids.set(BASE_ID)
        return id
    }

    private companion object {
        const val TAG = "automation"
        const val BASE_ID = 40_000
    }
}
