package app.dak.telephony.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dak.core.model.MessageKey
import app.dak.telephony.di.TelephonyEntryPoints
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues deferred sends: rate-limited bulk sends and automatic retries after radio / network failures.
 * One unique work per message, so a newer schedule replaces an older one.
 */
@Singleton
class SendScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    fun enqueue(
        key: MessageKey,
        attempt: Int,
        delayMillis: Long,
        slotReserved: Boolean,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val data = Data.Builder()
            .putString(SendRetryWorker.KEY_MESSAGE, key.toString())
            .putInt(SendRetryWorker.KEY_ATTEMPT, attempt)
            .putBoolean(SendRetryWorker.KEY_SLOT_RESERVED, slotReserved)
            .build()
        val request = OneTimeWorkRequestBuilder<SendRetryWorker>()
            .setInputData(data)
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(SendRetryWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(key), policy, request)
    }

    fun cancel(key: MessageKey) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(key))
    }

    private fun uniqueName(key: MessageKey) = "dak.send.$key"
}

/**
 * Runs one scheduled send attempt. The attempt only hands the message to the platform; its outcome arrives
 * through the sent/delivered receivers, which schedule the next retry if needed. Not a @HiltWorker on purpose:
 * it works with the default WorkManager factory and with HiltWorkerFactory alike.
 */
class SendRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val key = MessageKey.parse(inputData.getString(KEY_MESSAGE).orEmpty()) ?: return Result.failure()
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)
        val reserved = inputData.getBoolean(KEY_SLOT_RESERVED, false)
        TelephonyEntryPoints.get(applicationContext).messageSender().runScheduled(key, attempt, reserved)
        return Result.success()
    }

    companion object {
        const val TAG = "dak.send"
        internal const val KEY_MESSAGE = "message"
        internal const val KEY_ATTEMPT = "attempt"
        internal const val KEY_SLOT_RESERVED = "slotReserved"
    }
}
