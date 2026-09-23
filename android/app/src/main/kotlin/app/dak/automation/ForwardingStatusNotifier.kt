package app.dak.automation

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dak.R
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.forwarding.ForwardingStatus
import app.dak.navigation.IntentRoutes
import app.dak.notifications.NotificationChannels
import app.dak.navigation.Routes
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps forwarding visible: while any forwarding rule is active (or scheduled to start), a silent, low-priority,
 * ongoing notification says so ("Forwarding active: HDFC Bank → Sharma CA (until 31 Jul)"); tapping it opens the
 * Forwarding rules screen. [refresh] is called whenever rules change, when one expires, after a reboot and by the
 * daily housekeeping — there is no timer of its own.
 */
@Singleton
class ForwardingStatusNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rules: RuleRepository,
) {

    private val flags by lazy { context.getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE) }

    /** True when the last [refresh] found live forwarding rules (one SharedPreferences read, no database). */
    fun wasShowing(): Boolean = flags.getBoolean(KEY_SHOWING, false)

    private fun setShowing(showing: Boolean) {
        if (wasShowing() != showing) flags.edit().putBoolean(KEY_SHOWING, showing).apply()
    }

    /** Live forwarding rules (active or scheduled), for the notification and the Settings/Automations summary. */
    suspend fun liveSpecs(nowMillis: Long = System.currentTimeMillis()): List<ForwardingSpec> =
        rules.enabledRules()
            .mapNotNull { ForwardingSpec.fromRule(it) }
            .filter { it.status(nowMillis) == ForwardingStatus.ACTIVE || it.status(nowMillis) == ForwardingStatus.SCHEDULED }

    /** Posts, updates or removes the status notification to match the current rules. */
    suspend fun refresh(nowMillis: Long = System.currentTimeMillis()) {
        val live = runCatching { liveSpecs(nowMillis) }.getOrDefault(emptyList())
        val manager = NotificationManagerCompat.from(context)
        if (live.isEmpty()) {
            manager.cancel(TAG, ID)
            setShowing(false)
            return
        }
        setShowing(true)
        if (!canNotify()) return
        ensureChannel(manager)
        val lines = live.map { summaryLine(context, it, nowMillis) }
        val title = if (live.size == 1) {
            context.getString(R.string.fw_status_title_one)
        } else {
            context.getString(R.string.fw_status_title_many, live.size)
        }
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            IntentRoutes.open(context, Routes.FORWARDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = NotificationCompat.InboxStyle().also { s -> lines.forEach { s.addLine(it) } }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .build()
        try {
            manager.notify(TAG, ID, notification)
        } catch (e: SecurityException) {
            // Notifications revoked between the check and the call; the Forwarding screen still shows the rules.
        }
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.fw_channel_status_name))
            .setDescription(context.getString(R.string.fw_channel_status_description))
            .setGroup(NotificationChannels.GROUP_APP)
            .setShowBadge(false)
            .setVibrationEnabled(false)
            .setSound(null, null)
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
        const val CHANNEL_ID = "forwarding_status"
        private const val TAG = "forwarding-status"
        private const val ID = 41_001
        private const val REQUEST_CODE = 41_001
        private const val FLAGS_PREFS = "dak_forwarding_status"
        private const val KEY_SHOWING = "showing"

        /** "HDFC Bank, Zerodha → Sharma CA (until 31 Jul 2026)" / "(from 1 Jul 2026)" / "(until you stop it)". */
        fun summaryLine(context: Context, spec: ForwardingSpec, nowMillis: Long): String {
            val from = spec.sources.joinToString(", ") { it.name }
            val to = spec.recipients.joinToString(", ") { it.label }
            val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
            val end = spec.endMillis
            val period = when {
                nowMillis < spec.startMillis -> context.getString(R.string.fw_period_from, format.format(Date(spec.startMillis)))
                end != null -> context.getString(R.string.fw_period_until, format.format(Date(end)))
                else -> context.getString(R.string.fw_period_until_stopped)
            }
            val line = context.getString(R.string.fw_status_line, from, to, period)
            return if (spec.includeOtp) line + context.getString(R.string.fw_status_otp_suffix) else line
        }
    }
}
