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
 * (`goAsync`); only if that fails is [ScheduledSendWorker] enqueued to retry (the per-send WorkManager fallback armed
 * by [ScheduledSendScheduler] covers a killed process), so a due send normally costs one wakeup.
 *
 * Also the target of the birthday prompt's Send / Skip actions ([ACTION_BIRTHDAY_SEND], [ACTION_BIRTHDAY_SKIP]).
 * After a reboot or clock change (`BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`) it re-arms pending sends and
 * re-posts the "Forwarding active" notification (ongoing notifications do not survive a reboot) — each only when a
 * SharedPreferences flag says there is something to do, so an idle install never opens the index database here.
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
                        val ok = runCatching { deps.executor().runDue() }.isSuccess
                        if (!ok) enqueueRetry(app)
                        deps.scheduler().rearmPending()
                    }
                    ACTION_BIRTHDAY_SEND -> {
                        deps.birthdayNotifications().cancel(intent.getIntExtra(BirthdayNotifications.EXTRA_NOTIFICATION_ID, 0))
                        val tag = intent.getStringExtra(BirthdayNotifications.EXTRA_TAG)
                        val number = intent.getStringExtra(BirthdayNotifications.EXTRA_NUMBER)
                        val body = intent.getStringExtra(BirthdayNotifications.EXTRA_BODY)
                        if (tag != null && !number.isNullOrBlank() && !body.isNullOrBlank()) {
                            val subId = intent.getIntExtra(BirthdayNotifications.EXTRA_SUB_ID, -1)
                            if (deps.birthdayGate().sendFromPrompt(tag, number, body, subId, System.currentTimeMillis())) {
                                val ok = runCatching { deps.executor().runDue() }.isSuccess
                                if (!ok) enqueueRetry(app)
                            }
                        }
                    }
                    ACTION_BIRTHDAY_SKIP -> {
                        deps.birthdayNotifications().cancel(intent.getIntExtra(BirthdayNotifications.EXTRA_NOTIFICATION_ID, 0))
                        intent.getStringExtra(BirthdayNotifications.EXTRA_TAG)
                            ?.let { deps.birthdayGate().skipFromPrompt(it, System.currentTimeMillis()) }
                    }
                    else -> {
                        // Boot / time set / time zone change: only touch the database when there is something to do.
                        if (deps.scheduler().mightHavePending()) deps.scheduler().rearmPending()
                        if (deps.forwardingStatus().wasShowing()) deps.forwardingStatus().refresh()
                    }
                }
            } catch (e: Exception) {
                // The per-send WorkManager fallback (or the retry enqueued above) runs it later.
            } finally {
                pending.finish()
            }
        }
    }

    private fun enqueueRetry(app: Context) {
        runCatching {
            WorkManager.getInstance(app).enqueueUniqueWork(
                RUN_NOW_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScheduledSendWorker>().addTag(ScheduledSendWorker.TAG).build(),
            )
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
