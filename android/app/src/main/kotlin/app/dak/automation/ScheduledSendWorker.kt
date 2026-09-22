package app.dak.automation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager side of scheduled sends: the fallback that fires when no exact alarm could be set (or the alarm was
 * lost to a reboot), and the runner the alarm receiver enqueues as a backup. Sends whatever is due, then re-arms
 * the remaining pending sends.
 */
@HiltWorker
class ScheduledSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val executor: ScheduledSendExecutor,
    private val scheduler: ScheduledSendScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        executor.runDue()
        scheduler.rearmPending()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        const val TAG = "scheduled-send"
        private const val MAX_ATTEMPTS = 5
    }
}
