package app.dak.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues the periodic [BackupWorker] (idempotent). Called when a backup destination is set up.
 *
 * Battery: the daily check runs only while charging, on a network, with battery not low - the same window
 * WhatsApp-style backups use - so encrypting and uploading never drains a phone on the move. The worker itself
 * returns at once on days when no backup is due (weekly schedule, manual).
 */
@Singleton
class BackupScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    fun cancel() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }

    private companion object {
        const val WORK_NAME = "dak-periodic-backup"
    }
}
