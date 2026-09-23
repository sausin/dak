package app.dak.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.dak.birthdays.BirthdayNotifications
import app.dak.birthdays.BirthdaySendGate
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Alarm target for scheduled sends. Runs the due sends straight away inside the alarm's idle-whitelist window
 * (`goAsync`), and also enqueues [ScheduledSendWorker] as a backup in case the process is killed mid-way.
 * Also re-arms pending sends after a reboot or clock change (`BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`).
 *
 * Also the target of the birthday prompt's Send / Skip actions ([ACTION_BIRTHDAY_SEND], [ACTION_BIRTHDAY_SKIP]), and
 * on boot/clock changes it re-posts the "Forwarding active" notification (ongoing notifications do not survive a
 * reboot) and gives [DailyHousekeeping] its daily chance.
 *
 * Needs a manifest entry (see app/README.md, "Screens"): `<receiver android:name=".automation.ScheduledSendReceiver"
 * android:exported="false">` plus an intent filter for BOOT_COMPLETED if reboot re-arming is wanted.
 */
class ScheduledSendReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun executor(): ScheduledSendExecutor
        fun scheduler(): ScheduledSendScheduler
        fun birthdayGate(): BirthdaySendGate
        fun birthdayNotifications(): BirthdayNotifications
        fun forwardingStatus(): ForwardingStatusNotifier
        fun housekeeping(): DailyHousekeeping
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val deps = EntryPointAccessors.fromApplication(app, Deps::class.java)
        val action = intent.action
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_SEND_DUE -> {
                        WorkManager.getInstance(app).enqueueUniqueWork(
                            RUN_NOW_WORK,
                            ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<ScheduledSendWorker>().addTag(ScheduledSendWorker.TAG).build(),
                        )
                        deps.executor().runDue()
                    }
                    ACTION_BIRTHDAY_SEND -> {
                        deps.birthdayNotifications().cancel(intent.getIntExtra(BirthdayNotifications.EXTRA_NOTIFICATION_ID, 0))
                        val tag = intent.getStringExtra(BirthdayNotifications.EXTRA_TAG)
                        val number = intent.getStringExtra(BirthdayNotifications.EXTRA_NUMBER)
                        val body = intent.getStringExtra(BirthdayNotifications.EXTRA_BODY)
                        if (tag != null && !number.isNullOrBlank() && !body.isNullOrBlank()) {
                            val subId = intent.getIntExtra(BirthdayNotifications.EXTRA_SUB_ID, -1)
                            if (deps.birthdayGate().sendFromPrompt(tag, number, body, subId, System.currentTimeMillis())) {
                                deps.executor().runDue()
                            }
                        }
                    }
                    ACTION_BIRTHDAY_SKIP -> {
                        deps.birthdayNotifications().cancel(intent.getIntExtra(BirthdayNotifications.EXTRA_NOTIFICATION_ID, 0))
                        intent.getStringExtra(BirthdayNotifications.EXTRA_TAG)
                            ?.let { deps.birthdayGate().skipFromPrompt(it, System.currentTimeMillis()) }
                    }
                    else -> {
                        // Boot / time set / time zone change.
                        deps.forwardingStatus().refresh()
                        deps.housekeeping().runIfDue()
                    }
                }
                deps.scheduler().rearmPending()
            } catch (e: Exception) {
                // The WorkManager job enqueued above (or the per-send fallback) retries.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SEND_DUE = "app.dak.action.SCHEDULED_SEND_DUE"
        const val EXTRA_ID = "app.dak.extra.SCHEDULED_SEND_ID"
        const val ACTION_BIRTHDAY_SEND = "app.dak.action.BIRTHDAY_SEND"
        const val ACTION_BIRTHDAY_SKIP = "app.dak.action.BIRTHDAY_SKIP"
        private const val RUN_NOW_WORK = "scheduled-send-now"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
