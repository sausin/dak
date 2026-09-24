package app.dak.automation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs at the next start or end of a live forwarding rule (armed by [ForwardingStatusNotifier.refresh]): disables
 * rules whose period ended and refreshes the "Forwarding active" notification, which re-arms the next boundary.
 * WorkManager timing is inexact under Doze; forwarding itself is enforced per message, not by this job.
 */
@HiltWorker
class ForwardingBoundaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val rules: RuleRepository,
    private val housekeeping: DailyHousekeeping,
    private val status: ForwardingStatusNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        runCatching { housekeeping.expire(rules.enabledRules(), now) }
        runCatching { status.refresh(now) }
        return Result.success()
    }

    companion object {
        const val NAME = "forwarding-boundary"
    }
}
