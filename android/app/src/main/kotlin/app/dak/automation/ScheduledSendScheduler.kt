package app.dak.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.dak.automations.action.ReplyScheduler
import app.dak.core.model.NO_SUB_ID
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import app.dak.telephony.SimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules sends for later: the automation "schedule reply" action, rate-limited forwards and user-scheduled
 * messages. Each send is recorded in [ScheduledSendStore] and armed twice:
 * - an `AlarmManager` alarm (exact when the exact-alarm permission is granted on 12+, else inexact) that fires
 *   [ScheduledSendReceiver];
 * - a WorkManager one-time job with the same delay as a safety net that survives reboots and alarm loss.
 * [ScheduledSendExecutor] is idempotent (it only sends rows still pending), so whichever fires first wins.
 */
@Singleton
class ScheduledSendScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ScheduledSendStore,
    private val sims: SimRepository,
    private val limits: UnattendedSendLimits,
) : ReplyScheduler {

    private val flags by lazy { context.getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE) }

    override suspend fun scheduleReply(to: String, subId: Int?, text: String, atMillis: Long): Boolean {
        if (to.isBlank()) return false
        // Unattended: one reply per sender per cooldown (no ping-pong between two auto-repliers) within the daily cap,
        // and tagged as rule-driven so the executor applies the premium-rate guard.
        if (!limits.allowReply(to) || !limits.tryConsume("auto-reply")) return false
        schedule(addresses = listOf(to), body = text, subId = subId, atMillis = atMillis, ruleId = AUTO_REPLY_TAG)
        return true
    }

    /** Records and arms a scheduled send; returns its id. A null or unknown [subId] uses the default SMS SIM. */
    suspend fun schedule(
        addresses: List<String>,
        body: String,
        subId: Int?,
        atMillis: Long,
        conversationId: String? = null,
        ruleId: String? = null,
    ): Long {
        val sub = subId?.takeIf { it != NO_SUB_ID } ?: sims.defaultSmsSubId()
        val id = store.schedule(addresses, body, sub, atMillis, conversationId = conversationId, ruleId = ruleId)
        flags.edit().putBoolean(KEY_MIGHT_HAVE_PENDING, true).apply()
        arm(id, atMillis)
        return id
    }

    /** Moves a pending send to [atMillis] and re-arms it. */
    suspend fun reschedule(id: Long, atMillis: Long) {
        store.edit(id, sendAtMillis = atMillis)
        arm(id, atMillis)
    }

    /** Cancels a pending send (kept in the store as CANCELLED so the list can show it). */
    suspend fun cancel(id: Long) {
        store.markStatus(id, ScheduledSendStatus.CANCELLED)
        disarm(id)
    }

    /** Re-arms every pending send (after a reboot, a time change, or when a run finished). */
    suspend fun rearmPending() {
        val pending = store.pending().first()
        if (pending.isEmpty()) flags.edit().putBoolean(KEY_MIGHT_HAVE_PENDING, false).apply()
        for (send in pending) arm(send.id, send.sendAtMillis)
    }

    /**
     * Cheap check (one SharedPreferences read, no database) for whether any send may still be pending: false only
     * after [rearmPending] saw none and nothing was scheduled since. True on a fresh install's first check.
     */
    fun mightHavePending(): Boolean = flags.getBoolean(KEY_MIGHT_HAVE_PENDING, true)

    /** True when alarms can fire at the exact minute (always below Android 12). */
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarms.canScheduleExactAlarms()
    }

    /** Intent to the system screen that grants exact alarms (Android 12+), or null below 12. */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(android.net.Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }

    /** Arms both the alarm and the WorkManager safety net for [id]. */
    fun arm(id: Long, atMillis: Long) {
        armAlarm(id, atMillis)
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(ScheduledSendWorker.TAG)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(TAG, "could not enqueue fallback work for $id", it) }
    }

    private fun disarm(id: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms?.cancel(alarmIntent(id))
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(id)) }
    }

    private fun armAlarm(id: Long, atMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(id)
        try {
            if (canScheduleExact()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        } catch (e: SecurityException) {
            // Exact-alarm permission revoked between the check and the call: fall back to inexact.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    private fun alarmIntent(id: Long): PendingIntent {
        val intent = Intent(context, ScheduledSendReceiver::class.java)
            .setAction(ScheduledSendReceiver.ACTION_SEND_DUE)
            .putExtra(ScheduledSendReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            (id % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** [ScheduledSend.ruleId] of automation auto-replies and throttled forwards (never a birthday/broadcast tag). */
        const val AUTO_REPLY_TAG = "auto-reply"
        const val AUTO_FORWARD_TAG = "auto-forward"
        private const val TAG = "DakScheduledSend"
        private const val FLAGS_PREFS = "dak_scheduled_send_flags"
        private const val KEY_MIGHT_HAVE_PENDING = "might_have_pending"
        private fun workName(id: Long) = "scheduled-send-$id"
    }
}
