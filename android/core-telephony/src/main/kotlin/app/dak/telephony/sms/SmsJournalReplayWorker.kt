package app.dak.telephony.sms

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dak.telephony.di.TelephonyEntryPoints
import app.dak.telephony.internal.TAG
import java.util.concurrent.TimeUnit

/**
 * Replays incoming SMS left in the [SmsJournal] (insert failed, or the receiver died before the inbox row existed).
 * Scheduled after a failed insert, on boot / app update ([app.dak.telephony.boot.BootReceiver]) and at app start
 * ([scheduleIfPending]). Retries with exponential backoff while entries remain; the journal itself quarantines an
 * entry after [SmsJournal.DEFAULT_MAX_ATTEMPTS] failures, so a poison message cannot keep this job alive forever.
 * Not a @HiltWorker on purpose (works with any WorkerFactory).
 */
class SmsJournalReplayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val done = TelephonyEntryPoints.get(applicationContext).incomingSmsProcessor().replayJournal()
        return when {
            done -> Result.success()
            runAttemptCount >= MAX_RUNS -> Result.failure() // the next receive / boot / app start schedules again
            else -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "dak.sms.journal.replay"
        private const val BACKOFF_SECONDS = 30L
        private const val MAX_RUNS = 20

        /** Enqueues a replay (no-op when one is already queued). */
        fun schedule(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<SmsJournalReplayWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
            } catch (e: Exception) {
                Log.w(TAG, "could not schedule SMS journal replay: ${e.javaClass.simpleName}")
            }
        }

        /** Enqueues a replay only when the journal holds something (one directory listing; safe at app start). */
        fun scheduleIfPending(context: Context) {
            val pending = runCatching { IncomingSmsProcessor.journalFor(context.applicationContext).hasPending() }.getOrDefault(false)
            if (pending) schedule(context)
        }
    }
}
