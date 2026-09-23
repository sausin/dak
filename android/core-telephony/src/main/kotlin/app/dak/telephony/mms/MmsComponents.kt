package app.dak.telephony.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.dak.telephony.di.TelephonyEntryPoints
import app.dak.telephony.internal.runAsync

/**
 * `android.provider.Telephony.WAP_PUSH_DELIVER` receiver for `application/vnd.wap.mms-message` (default SMS app
 * only; guarded by BROADCAST_WAP_PUSH in the manifest).
 */
class WapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        runAsync(context) { it.wapPushProcessor().process(intent) }
    }
}

/** Internal receiver for `downloadMultimediaMessage` results. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_DOWNLOADED) return
        val code = resultCode
        runAsync(context) { it.mmsDownloadManager().onDownloaded(intent, code) }
    }

    companion object {
        const val ACTION_MMS_DOWNLOADED = "app.dak.telephony.action.MMS_DOWNLOADED"
        internal const val EXTRA_MESSAGE_ID = "app.dak.telephony.extra.MESSAGE_ID"
        internal const val EXTRA_FILE = "app.dak.telephony.extra.FILE"
        internal const val EXTRA_SUB_ID = "app.dak.telephony.extra.SUB_ID"
        internal const val EXTRA_ATTEMPT = "app.dak.telephony.extra.ATTEMPT"
    }
}

/** Internal receiver for `sendMultimediaMessage` results (sends and m-notifyresp-ind). */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_SENT) return
        val code = resultCode
        runAsync(context) { it.mmsSendManager().onSent(intent, code) }
    }

    companion object {
        const val ACTION_MMS_SENT = "app.dak.telephony.action.MMS_SENT"
        internal const val EXTRA_MESSAGE_ID = "app.dak.telephony.extra.MESSAGE_ID"
        internal const val EXTRA_FILE = "app.dak.telephony.extra.FILE"
        internal const val EXTRA_ATTEMPT = "app.dak.telephony.extra.ATTEMPT"
        internal const val EXTRA_IS_NOTIFY_RESPONSE = "app.dak.telephony.extra.IS_NOTIFY_RESPONSE"
    }
}

/**
 * One MMS download attempt; WorkManager's exponential backoff drives retries. Expedited, so on API < 31 it runs
 * as a short dataSync foreground service ([getForegroundInfo]). Not a @HiltWorker on purpose (works with any
 * WorkerFactory).
 */
class MmsDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_MESSAGE_ID, -1L)
        val location = inputData.getString(KEY_CONTENT_LOCATION)
        if (id < 0 || location.isNullOrEmpty()) return Result.failure()
        val subId = inputData.getInt(KEY_SUB_ID, -1)
        val manager = TelephonyEntryPoints.get(applicationContext).mmsDownloadManager()
        return when (manager.runAttempt(id, location, subId, runAttemptCount)) {
            DownloadAttemptResult.SUCCESS -> Result.success()
            DownloadAttemptResult.RETRY -> Result.retry()
            DownloadAttemptResult.FAILURE -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = TransferNotifications.foregroundInfo(applicationContext)

    companion object {
        const val TAG = "dak.mms.download"
        internal const val KEY_MESSAGE_ID = "messageId"
        internal const val KEY_CONTENT_LOCATION = "contentLocation"
        internal const val KEY_SUB_ID = "subId"
    }
}
