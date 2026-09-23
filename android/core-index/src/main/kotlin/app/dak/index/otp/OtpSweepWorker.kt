package app.dak.index.otp

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
 * The single OTP auto-delete job (unique work [OtpLifecycle.SWEEP_WORK]): moves every due OTP to the bin in one
 * batch, then re-arms itself for the next deadline (or not at all when nothing is pending).
 */
@HiltWorker
class OtpSweepWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val lifecycle: OtpLifecycle,
    private val activity: BackgroundActivityLog,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        activity.record(BackgroundActivityLog.OTP_SWEEP)
        lifecycle.sweep()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("DakIndex", "OTP sweep failed", e)
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
