package app.dak.index.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.dak.index.IndexSchedule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager scheduling of the stage-2 backfill / re-index: unique one-shot work under the user's [IndexSchedule].
 * Periodic housekeeping (bin purge, safety-net reconcile, app-hash refresh) runs in the single daily maintenance
 * job instead (`app.dak.index.maintenance`).
 */
@Singleton
class BackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val night = NightWindow()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val runningState = MutableStateFlow(false)

    /** True while a backfill worker is executing in this process. */
    val running: StateFlow<Boolean> = runningState.asStateFlow()

    /** The user's last chosen schedule (kept outside the database so it survives an index rebuild). */
    var schedule: IndexSchedule
        get() = prefs.getString(KEY_SCHEDULE, null)?.let { name -> IndexSchedule.entries.firstOrNull { it.name == name } }
            ?: IndexSchedule.WHEN_CHARGING
        set(value) {
            prefs.edit().putString(KEY_SCHEDULE, value.name).apply()
        }

    /** True once the user picked a schedule (onboarding done). */
    val hasChosenSchedule: Boolean get() = prefs.contains(KEY_SCHEDULE)

    /**
     * Enqueues the stage-2 worker under [schedule]. [replace] = true restarts it (a new choice, a re-index);
     * false keeps an already queued run.
     */
    fun enqueueBackfill(schedule: IndexSchedule, replace: Boolean, now: ZonedDateTime = ZonedDateTime.now()) {
        val delayMillis = if (schedule == IndexSchedule.TONIGHT) night.delayUntilOpen(now).toMillis() else 0L
        enqueue(schedule, delayMillis, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    /** Re-enqueues a "Tonight" backfill for the next window after this one closed before finishing. */
    fun enqueueNextNight(now: ZonedDateTime = ZonedDateTime.now()) {
        enqueue(IndexSchedule.TONIGHT, night.delayUntilNextStart(now).toMillis(), ExistingWorkPolicy.REPLACE)
    }

    fun cancelBackfill() {
        WorkManager.getInstance(context).cancelUniqueWork(BACKFILL_WORK)
    }

    internal fun setRunning(value: Boolean) {
        runningState.value = value
    }

    /** Whether [now] is inside the "Tonight" window. */
    internal fun insideNightWindow(now: ZonedDateTime = ZonedDateTime.now()): Boolean = night.contains(now)

    private fun enqueue(schedule: IndexSchedule, delayMillis: Long, policy: ExistingWorkPolicy) {
        val constraints = when (schedule) {
            IndexSchedule.NOW -> Constraints.Builder().build()
            IndexSchedule.WHEN_CHARGING -> Constraints.Builder().setRequiresCharging(true).build()
            IndexSchedule.TONIGHT -> Constraints.Builder()
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .build()
        }
        val request = OneTimeWorkRequestBuilder<BackfillWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(BackfillWorker.KEY_SCHEDULE to schedule.name))
            .addTag(BACKFILL_WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BACKFILL_WORK, policy, request)
    }

    companion object {
        const val BACKFILL_WORK = "dak-index-backfill"
        private const val PREFS = "dak_index_sync"
        private const val KEY_SCHEDULE = "schedule"
    }
}
