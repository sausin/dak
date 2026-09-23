package app.dak.automation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager safety net for the heads-up wakeup (armed next to the alarm by [ScheduledSendHeadsUp], like
 * [ScheduledSendWorker] for sends): posts whatever heads-ups are due. Announcing is idempotent, so the alarm and this
 * job can both run.
 */
@HiltWorker
class ScheduledSendHeadsUpWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val headsUp: ScheduledSendHeadsUp,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        headsUp.refresh()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        const val TAG = "scheduled-heads-up"
        private const val MAX_ATTEMPTS = 3
    }
}
