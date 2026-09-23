package app.dak.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily WorkManager job that runs an encrypted backup when one is due under Settings → Backup and data →
 * Backup schedule ("daily", "weekly" or "manual"). Deciding inside the job means a schedule change made anywhere
 * (this screen or the settings screen) takes effect without re-enqueueing.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: BackupManager,
    private val settings: SettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val status = manager.status.value
        if (status.destination == null || !manager.hasPassphrase()) return Result.success()
        val interval = when (settings.get(DakSettings.backupSchedule)) {
            "daily" -> DAY
            "weekly" -> 7 * DAY
            else -> return Result.success()
        }
        val last = status.lastBackupMillis ?: 0L
        // Allow a little slack so a daily job that runs slightly early still backs up.
        if (System.currentTimeMillis() - last < interval - SLACK) return Result.success()
        return when (manager.backupNow()) {
            is BackupOperation.Finished -> Result.success()
            else -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
        const val SLACK = 2 * 60 * 60_000L
        const val MAX_ATTEMPTS = 3
    }
}
