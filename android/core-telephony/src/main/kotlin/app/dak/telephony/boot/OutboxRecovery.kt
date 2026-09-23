package app.dak.telephony.boot

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.telephony.di.TelephonyEntryPoints
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.long
import app.dak.telephony.internal.safeQuery
import app.dak.telephony.mms.MmsDownloadManager
import app.dak.telephony.provider.MmsColumns
import app.dak.telephony.provider.ProviderUris
import app.dak.telephony.provider.SmsColumns
import app.dak.telephony.send.SendFailureStore
import app.dak.telephony.send.SendScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repairs send / download state after a reboot or app update, when in-flight platform callbacks were lost:
 * - SMS stuck in OUTBOX (sending when the process died) become FAILED with a visible reason (never auto-resent,
 *   the platform may have sent them already);
 * - QUEUED SMS are re-enqueued (KEEP: WorkManager usually still has them);
 * - MMS stuck in the outbox become FAILED; pending MMS downloads are re-enqueued.
 */
@Singleton
class OutboxRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: SendScheduler,
    private val failures: SendFailureStore,
    private val downloads: MmsDownloadManager,
) {
    suspend fun recover() {
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val cutoffMillis = System.currentTimeMillis() - STALE_AFTER_MILLIS

            resolver.safeQuery(
                ProviderUris.SMS, arrayOf(SmsColumns.ID, SmsColumns.DATE),
                "${SmsColumns.TYPE} = ${SmsColumns.TYPE_OUTBOX} AND ${SmsColumns.DATE} < ?", arrayOf(cutoffMillis.toString()),
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.long(SmsColumns.ID)
                    val values = ContentValues().apply { put(SmsColumns.TYPE, SmsColumns.TYPE_FAILED) }
                    runCatching { resolver.update(ProviderUris.sms(id), values, null, null) }
                    failures.set(MessageKey(MessageKind.SMS, id), "Sending was interrupted; tap to retry")
                }
            }

            resolver.safeQuery(ProviderUris.SMS, arrayOf(SmsColumns.ID), "${SmsColumns.TYPE} = ${SmsColumns.TYPE_QUEUED}")?.use { c ->
                while (c.moveToNext()) {
                    val key = MessageKey(MessageKind.SMS, c.long(SmsColumns.ID))
                    scheduler.enqueue(key, attempt = 1, delayMillis = 0L, slotReserved = false, policy = ExistingWorkPolicy.KEEP)
                }
            }

            resolver.safeQuery(
                ProviderUris.MMS, arrayOf(MmsColumns.ID),
                "${MmsColumns.MESSAGE_BOX} = ${MmsColumns.BOX_OUTBOX} AND ${MmsColumns.DATE} < ?",
                arrayOf((cutoffMillis / 1000).toString()),
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.long(MmsColumns.ID)
                    val values = ContentValues().apply { put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_FAILED) }
                    runCatching { resolver.update(ProviderUris.mms(id), values, null, null) }
                    failures.set(MessageKey(MessageKind.MMS, id), "Sending was interrupted; tap to retry")
                }
            }
        }
        downloads.resumePending()
    }

    private companion object {
        const val STALE_AFTER_MILLIS = 2 * 60_000L
    }
}

/** Runs [OutboxRecovery] once, off the boot broadcast. */
class OutboxRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TelephonyEntryPoints.get(applicationContext).outboxRecovery().recover()
        return Result.success()
    }
}

/** BOOT_COMPLETED / MY_PACKAGE_REPLACED: schedules [OutboxRecoveryWorker]. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    val request = OneTimeWorkRequestBuilder<OutboxRecoveryWorker>().build()
                    WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
                } catch (e: Exception) {
                    Log.w(TAG, "could not schedule recovery: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private companion object {
        const val UNIQUE_WORK = "dak.telephony.recovery"
    }
}
