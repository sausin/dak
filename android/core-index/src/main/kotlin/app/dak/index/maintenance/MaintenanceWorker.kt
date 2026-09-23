package app.dak.index.maintenance

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.dak.index.sync.BackgroundActivityLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * The one daily housekeeping job: runs every contributed [MaintenanceTask] in order. Stopping (device left idle)
 * cancels the remaining tasks; they run on the next day's pass.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val tasks: Set<@JvmSuppressWildcards MaintenanceTask>,
    private val scheduler: MaintenanceScheduler,
    private val activity: BackgroundActivityLog,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        activity.record(BackgroundActivityLog.MAINTENANCE)
        for (task in tasks.sortedWith(compareBy<MaintenanceTask>({ it.order }, { it.name }))) {
            if (isStopped) break
            try {
                task.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Maintenance task ${task.name} failed", e)
            }
        }
        scheduler.markRan()
        return Result.success()
    }

    private companion object {
        const val TAG = "DakIndex"
    }
}
