package app.dak.index.bin

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.dak.index.repo.AuditLogRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Daily job: purges expired recycle-bin entries and trims the audit log. */
@HiltWorker
class BinPurgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recycleBin: RecycleBin,
    private val audit: AuditLogRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        recycleBin.purgeExpired()
        audit.trim()
        return Result.success()
    }
}
