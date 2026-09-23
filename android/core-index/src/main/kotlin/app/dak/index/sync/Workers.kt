package app.dak.index.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.dak.index.IndexSchedule
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Stage-2 backfill / re-index worker. Processes batches until done; a stopped run (constraints lost, process
 * killed) resumes from the persisted cursor. A "Tonight" run whose window closes re-enqueues itself for the next
 * night. Reports progress through [setProgress] (`done`, `total`) and `IndexMaintenance.progress`.
 *
 * Battery: one-shot unique work (never periodic), so nothing lingers once the backfill is done. Each batch is a
 * short CPU burst with its cursor persisted, so a stop (constraints lost, the 10-minute execution limit) loses at
 * most one batch.
 */
@HiltWorker
class BackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val maintenance: IndexMaintenance,
    private val scheduler: BackfillScheduler,
    private val activity: BackgroundActivityLog,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        activity.record(BackgroundActivityLog.BACKFILL)
        val schedule = inputData.getString(KEY_SCHEDULE)
            ?.let { name -> IndexSchedule.entries.firstOrNull { it.name == name } }
            ?: IndexSchedule.NOW
        scheduler.setRunning(true)
        return try {
            val outcome = maintenance.runStageTwo(schedule) { done, total ->
                setProgress(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
            }
            if (outcome == IndexMaintenance.StageTwoOutcome.WINDOW_CLOSED) scheduler.enqueueNextNight()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Backfill batch failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            scheduler.setRunning(false)
        }
    }

    companion object {
        const val KEY_SCHEDULE = "schedule"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
        private const val MAX_ATTEMPTS = 8
        private const val TAG = "DakIndex"
    }
}
