package app.dak.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
 * Needs a manifest entry (see app/README.md, "Screens"): `<receiver android:name=".automation.ScheduledSendReceiver"
 * android:exported="false">` plus an intent filter for BOOT_COMPLETED if reboot re-arming is wanted.
 */
class ScheduledSendReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun executor(): ScheduledSendExecutor
        fun scheduler(): ScheduledSendScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val deps = EntryPointAccessors.fromApplication(app, Deps::class.java)
        val action = intent.action
        val pending = goAsync()
        scope.launch {
            try {
                if (action == ACTION_SEND_DUE) {
                    WorkManager.getInstance(app).enqueueUniqueWork(
                        RUN_NOW_WORK,
                        ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<ScheduledSendWorker>().addTag(ScheduledSendWorker.TAG).build(),
                    )
                    deps.executor().runDue()
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
        private const val RUN_NOW_WORK = "scheduled-send-now"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
