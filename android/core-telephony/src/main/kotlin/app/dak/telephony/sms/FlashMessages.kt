package app.dak.telephony.sms

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dak.telephony.R
import app.dak.telephony.di.TelephonyEntryPoints
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.runAsync
import app.dak.telephony.provider.IncomingSms
import app.dak.telephony.provider.TelephonyProviderWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Class 0 ("flash") SMS (3GPP TS 23.038 message class 0: "immediate display", storage optional).
 *
 * Stock Android behaviour, kept: the message is shown at once and written to the inbox only when the user taps Save.
 * Dak shows it as a high-importance (heads-up) notification with the full text and Save / Dismiss actions; tapping it
 * opens [FlashMessageActivity], a dialog like AOSP's ClassZeroActivity. A full-screen intent is not used: Android 14+
 * restricts USE_FULL_SCREEN_INTENT to calling and alarm apps, and background activity starts are blocked since
 * Android 10, so the heads-up notification is the reliable "display immediately" path.
 *
 * The message lives only in the notification's PendingIntent extras (our own non-exported components) until saved.
 * One notification per sender: a newer flash from the same sender replaces the older one, so a flood cannot bury the
 * shade. If the notification cannot be shown (notifications off for Dak or for the channel), [show] returns false and
 * the caller stores the message as a normal SMS, so it is never lost unseen.
 *
 * A saved flash message is inserted read (the user has seen it) and is not re-announced: no second notification and
 * no automation run; the index picks it up through the provider change observer.
 */
@Singleton
class FlashMessages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writer: TelephonyProviderWriter,
) {
    /** Shows [sms] as a flash message; false when it could not be shown (store it normally instead). */
    fun show(sms: IncomingSms): Boolean = try {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel()
        if (!manager.areNotificationsEnabled() || channelBlocked()) {
            false
        } else {
            val id = notificationId(sms.address)
            val sender = displaySafe(sms.address)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(context.getString(R.string.dak_telephony_flash_title, sender))
                .setContentText(sms.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(sms.body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setWhen(sms.dateMillis)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentTitle(context.getString(R.string.dak_telephony_flash_public))
                        .build(),
                )
                .setContentIntent(activityIntent(sms, id))
                .addAction(0, context.getString(R.string.dak_telephony_flash_save), actionIntent(FlashMessageReceiver.ACTION_SAVE, sms, id))
                .addAction(0, context.getString(R.string.dak_telephony_flash_dismiss), actionIntent(FlashMessageReceiver.ACTION_DISMISS, sms, id))
                .build()
            manager.notify(TAG_FLASH, id, notification)
            true
        }
    } catch (e: SecurityException) {
        false // POST_NOTIFICATIONS revoked
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not show flash message: ${e.javaClass.simpleName}")
        false
    }

    /** Writes a flash message the user chose to keep to the inbox (read). Returns true when it was stored. */
    suspend fun save(sms: IncomingSms): Boolean {
        val key = writer.insertIncoming(sms) ?: return false
        writer.markRead(key)
        return true
    }

    fun cancel(notificationId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(TAG_FLASH, notificationId) }
    }

    private fun channelBlocked(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        return manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.dak_telephony_channel_flash), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.dak_telephony_channel_flash_description) },
        )
    }

    private fun activityIntent(sms: IncomingSms, id: Int): PendingIntent {
        val intent = FlashExtras.put(Intent(context, FlashMessageActivity::class.java), sms, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(action: String, sms: IncomingSms, id: Int): PendingIntent {
        val intent = FlashExtras.put(Intent(context, FlashMessageReceiver::class.java).setAction(action), sms, id)
        val requestCode = id * 31 + action.hashCode()
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal companion object {
        const val CHANNEL_ID = "dak_flash_messages"
        private const val TAG_FLASH = "dak_flash"

        fun notificationId(address: String): Int = ("flash:" + address.trim().lowercase()).hashCode()

        /** Sender for display: bidi overrides / isolates and other invisible controls removed, length bounded. */
        fun displaySafe(address: String): String =
            address.filterNot { Character.getType(it) == Character.FORMAT.toInt() || it.isISOControl() }.take(64).ifBlank { "?" }
    }
}

/** Carries a flash message in Intent extras (only between our own non-exported components). */
internal object FlashExtras {
    private const val ADDRESS = "app.dak.telephony.flash.ADDRESS"
    private const val BODY = "app.dak.telephony.flash.BODY"
    private const val DATE_SENT = "app.dak.telephony.flash.DATE_SENT"
    private const val DATE = "app.dak.telephony.flash.DATE"
    private const val SUB_ID = "app.dak.telephony.flash.SUB_ID"
    private const val PROTOCOL = "app.dak.telephony.flash.PROTOCOL"
    private const val SERVICE_CENTER = "app.dak.telephony.flash.SERVICE_CENTER"
    private const val NOTIFICATION_ID = "app.dak.telephony.flash.NOTIFICATION_ID"

    fun put(intent: Intent, sms: IncomingSms, notificationId: Int): Intent = intent
        .putExtra(ADDRESS, sms.address)
        .putExtra(BODY, sms.body)
        .putExtra(DATE_SENT, sms.dateSentMillis)
        .putExtra(DATE, sms.dateMillis)
        .putExtra(SUB_ID, sms.subId)
        .putExtra(PROTOCOL, sms.protocol ?: -1)
        .putExtra(SERVICE_CENTER, sms.serviceCenter)
        .putExtra(NOTIFICATION_ID, notificationId)

    fun sms(intent: Intent): IncomingSms? {
        val address = intent.getStringExtra(ADDRESS) ?: return null
        val body = intent.getStringExtra(BODY) ?: return null
        return IncomingSms(
            address = address,
            body = body,
            dateSentMillis = intent.getLongExtra(DATE_SENT, 0L),
            dateMillis = intent.getLongExtra(DATE, System.currentTimeMillis()),
            subId = intent.getIntExtra(SUB_ID, -1),
            protocol = intent.getIntExtra(PROTOCOL, -1).takeIf { it >= 0 },
            serviceCenter = intent.getStringExtra(SERVICE_CENTER),
        )
    }

    fun notificationId(intent: Intent): Int = intent.getIntExtra(NOTIFICATION_ID, 0)
}

/** Save / Dismiss actions of the flash notification. Not exported: only our own PendingIntents reach it. */
class FlashMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_SAVE && action != ACTION_DISMISS) return
        runAsync(context) { entry ->
            val flash = entry.flashMessages()
            flash.cancel(FlashExtras.notificationId(intent))
            if (action == ACTION_SAVE) {
                val sms = FlashExtras.sms(intent) ?: return@runAsync
                if (!flash.save(sms)) Log.w(TAG, "flash message could not be saved")
            }
        }
    }

    companion object {
        const val ACTION_SAVE = "app.dak.telephony.action.FLASH_SAVE"
        const val ACTION_DISMISS = "app.dak.telephony.action.FLASH_DISMISS"
    }
}

/**
 * Dialog for a flash message (opened from its notification): sender, full text, Save / Dismiss. A plain framework
 * Activity + AlertDialog so :core-telephony needs no UI toolkit; the text is shown as plain text (links are not
 * clickable: flash SMS are a favourite phishing vector).
 */
class FlashMessageActivity : Activity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showFor(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showFor(intent)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun showFor(intent: Intent) {
        val sms = FlashExtras.sms(intent)
        if (sms == null) {
            finish()
            return
        }
        val notificationId = FlashExtras.notificationId(intent)
        val entry = TelephonyEntryPoints.get(this)
        entry.flashMessages().cancel(notificationId)
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dak_telephony_flash_title, FlashMessages.displaySafe(sms.address)))
            .setMessage(sms.body)
            .setPositiveButton(R.string.dak_telephony_flash_save) { _, _ ->
                val app = applicationContext
                entry.telephonyScope().launch {
                    val saved = entry.flashMessages().save(sms)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, if (saved) R.string.dak_telephony_flash_saved else R.string.dak_telephony_flash_not_saved, Toast.LENGTH_SHORT).show()
                    }
                }
                finish()
            }
            .setNegativeButton(R.string.dak_telephony_flash_dismiss) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
