package app.dak.index.otp

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.dak.core.model.MessageKey
import app.dak.index.bin.DeletedBy
import app.dak.index.bin.RecycleBin
import app.dak.index.db.DakIndexDatabase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Moves one OTP message to the bin when its auto-delete time comes (skipped if starred or already gone). */
@HiltWorker
class OtpDeleteWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: DakIndexDatabase,
    private val recycleBin: RecycleBin,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_MESSAGE)?.let { MessageKey.parse(it) } ?: return Result.failure()
        val deletedBy = DeletedBy.decode(inputData.getString(KEY_DELETED_BY) ?: DeletedBy.AutoOtp.encoded)
        val row = db.messageDao().get(key.kind.name, key.providerId) ?: return Result.success()
        if (row.starred) return Result.success()
        // A failed provider delete (e.g. no longer the default SMS app) is not retried: the OTP simply stays.
        recycleBin.moveToBin(listOf(key), deletedBy)
        return Result.success()
    }

    companion object {
        const val KEY_MESSAGE = "message"
        const val KEY_DELETED_BY = "deletedBy"
    }
}
