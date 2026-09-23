package app.dak.telephony.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.mms.pdu.MessageType
import app.dak.mms.pdu.MmsLimits
import app.dak.mms.pdu.MmsPduDecoder
import app.dak.mms.pdu.MmsSafety
import app.dak.mms.pdu.NotificationInd
import app.dak.mms.pdu.PduDecodeResult
import app.dak.mms.pdu.RetrieveConf
import app.dak.mms.pdu.RetrieveStatus
import app.dak.telephony.IncomingDispatcher
import app.dak.telephony.MmsDownloadState
import app.dak.telephony.SimRepository
import app.dak.telephony.TelephonySettings
import app.dak.telephony.internal.PendingIntentFlags
import app.dak.telephony.internal.SmsManagers
import app.dak.telephony.internal.TAG
import app.dak.telephony.provider.TelephonyProviderReader
import app.dak.telephony.send.RetryPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a download attempt tells WorkManager. */
enum class DownloadAttemptResult { SUCCESS, RETRY, FAILURE }

/**
 * The MMS download pipeline:
 * 1. [onNotification] (WAP push): persist the m-notification-ind in the inbox and enqueue [MmsDownloadWorker]
 *    (unique per content location, exponential backoff, expedited).
 * 2. [runAttempt] (worker): `SmsManager.downloadMultimediaMessage` on the message's subscription (the platform
 *    picks that SIM's MMS APN / MMSC) into a [MmsFileProvider] file, then waits for the result.
 * 3. [onDownloaded] ([MmsDownloadedReceiver]): parse m-retrieve-conf, store it (pdu + parts + addr), delete the
 *    notification row, notify handlers; on failure keep the notification row and record
 *    [MmsDownloadState.Failed] with the reason and attempt count.
 *
 * No network constraint is set on the work: MMS travels over the carrier's MMS APN, which Android brings up
 * even when mobile data is off or the default network is Wi-Fi, so a "connected" constraint would wrongly block
 * downloads. Failures caused by no data network are retried with backoff instead.
 */
@Singleton
class MmsDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val persister: MmsPersister,
    private val reader: TelephonyProviderReader,
    private val states: MmsDownloadStateStore,
    private val bus: MmsResultBus,
    private val dispatcher: IncomingDispatcher,
    private val settings: TelephonySettings,
    private val sims: SimRepository,
    private val sendManager: MmsSendManager,
) {
    private val resultLock = Mutex()

    /** Handles a received m-notification-ind. */
    suspend fun onNotification(n: NotificationInd, subId: Int) {
        // Anyone can send a WAP push. A notification whose content location is not a plain http(s) URL (file:,
        // content:, loopback, …) cannot come from an MMSC: drop it without storing or fetching anything.
        if (!MmsSafety.isDownloadableContentLocation(n.contentLocation)) {
            Log.w(TAG, "dropping MMS notification with an unsafe content location")
            return
        }
        val existing = persister.findNotification(n.contentLocation, n.transactionId)
        val id = existing ?: persister.insertNotification(n, subId) ?: run {
            Log.e(TAG, "MMS notification could not be written to the provider")
            return
        }
        val roaming = sims.isRoaming(subId)
        val tooLarge = n.messageSize > MmsLimits.MAX_PDU_BYTES
        if (!settings.autoDownloadMms || (roaming && !settings.autoDownloadMmsWhenRoaming) || tooLarge) {
            if (existing == null) {
                val reason = when {
                    tooLarge -> "Very large message: tap to download"
                    roaming -> "Roaming: tap to download"
                    else -> "Tap to download"
                }
                states.set(id, MmsDownloadState.Failed(reason, 0))
                notifyHandlers(id)
            }
            return
        }
        if (existing == null) states.set(id, MmsDownloadState.Pending)
        enqueue(id, n.contentLocation, subId, ExistingWorkPolicy.KEEP)
    }

    /** User-initiated retry ("tap to retry"). */
    suspend fun retry(id: Long) {
        val info = persister.notificationInfo(id) ?: return
        val location = info.contentLocation ?: return
        if (info.messageType != MessageType.NOTIFICATION_IND) return
        states.set(id, MmsDownloadState.Pending)
        enqueue(id, location, info.subId, ExistingWorkPolicy.REPLACE)
    }

    /** Re-enqueues downloads for notification rows that are not done (after boot / app update). */
    suspend fun resumePending() {
        for (info in persister.pendingNotifications()) {
            val location = info.contentLocation ?: continue
            when (states.get(info.id)) {
                MmsDownloadState.Pending, MmsDownloadState.Downloading -> enqueue(info.id, location, info.subId, ExistingWorkPolicy.KEEP)
                else -> Unit
            }
        }
    }

    fun enqueue(id: Long, contentLocation: String, subId: Int, policy: ExistingWorkPolicy) {
        val data = Data.Builder()
            .putLong(MmsDownloadWorker.KEY_MESSAGE_ID, id)
            .putString(MmsDownloadWorker.KEY_CONTENT_LOCATION, contentLocation)
            .putInt(MmsDownloadWorker.KEY_SUB_ID, subId)
            .build()
        val request = OneTimeWorkRequestBuilder<MmsDownloadWorker>()
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(MmsDownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("dak.mms.download.$contentLocation", policy, request)
    }

    /** One download attempt; [previousAttempts] is WorkManager's `runAttemptCount`. */
    suspend fun runAttempt(id: Long, contentLocation: String, subId: Int, previousAttempts: Int): DownloadAttemptResult {
        val info = persister.notificationInfo(id)
        if (info == null || info.messageType != MessageType.NOTIFICATION_IND) {
            // Already downloaded (row replaced) or deleted by the user.
            if (states.get(id) != MmsDownloadState.Done) states.setDone(id, null)
            return DownloadAttemptResult.SUCCESS
        }
        val attempt = previousAttempts + 1
        val nowSeconds = System.currentTimeMillis() / 1000
        if (info.expirySeconds in 1 until nowSeconds) {
            states.set(id, MmsDownloadState.Failed("Expired on the carrier's server", attempt))
            notifyHandlers(id)
            return DownloadAttemptResult.FAILURE
        }
        if (!MmsSafety.isDownloadableContentLocation(contentLocation)) {
            states.set(id, MmsDownloadState.Failed("Invalid download link", attempt))
            notifyHandlers(id)
            return DownloadAttemptResult.FAILURE
        }
        states.set(id, MmsDownloadState.Downloading)

        val waiter = bus.register(id)
        val outcome = try {
            val started = withContext(Dispatchers.IO) { startDownload(id, contentLocation, subId, attempt) }
            if (!started) {
                DownloadOutcome.Failed(MmsResultCodes.describe(MmsResultCodes.UNSPECIFIED), retryable = true)
            } else {
                withTimeoutOrNull(RESULT_TIMEOUT_MILLIS) { waiter.await() }
                    ?: DownloadOutcome.Failed("Timed out waiting for the carrier", retryable = true)
            }
        } finally {
            bus.unregister(id, waiter)
        }

        return when (outcome) {
            is DownloadOutcome.Success -> DownloadAttemptResult.SUCCESS
            is DownloadOutcome.Failed -> {
                states.set(id, MmsDownloadState.Failed(outcome.reason, attempt))
                if (RetryPolicy.shouldRetry(attempt, MAX_ATTEMPTS, outcome.retryable)) {
                    DownloadAttemptResult.RETRY
                } else {
                    notifyHandlers(id)
                    DownloadAttemptResult.FAILURE
                }
            }
        }
    }

    /** Result of `downloadMultimediaMessage` (called by [MmsDownloadedReceiver]). */
    suspend fun onDownloaded(intent: Intent, resultCode: Int) {
        val id = intent.getLongExtra(MmsDownloadedReceiver.EXTRA_MESSAGE_ID, -1L)
        if (id < 0) return
        val subId = intent.getIntExtra(MmsDownloadedReceiver.EXTRA_SUB_ID, -1)
        val attempt = intent.getIntExtra(MmsDownloadedReceiver.EXTRA_ATTEMPT, 1)
        val file = MmsFiles.resolve(context, intent.getStringExtra(MmsDownloadedReceiver.EXTRA_FILE))
        val outcome = resultLock.withLock {
            try {
                store(id, subId, file, resultCode, intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "storing downloaded MMS failed", e)
                DownloadOutcome.Failed("Could not save the message", retryable = true)
            } finally {
                file?.delete()
            }
        }
        if (outcome is DownloadOutcome.Failed) states.set(id, MmsDownloadState.Failed(outcome.reason, attempt))
        bus.complete(id, outcome)
    }

    private suspend fun store(id: Long, subId: Int, file: File?, resultCode: Int, intent: Intent): DownloadOutcome {
        if (resultCode != Activity.RESULT_OK) {
            val http = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
            return DownloadOutcome.Failed(MmsResultCodes.describe(resultCode, http), MmsResultCodes.isRetryable(resultCode))
        }
        val info = persister.notificationInfo(id)
        if (info == null || info.messageType != MessageType.NOTIFICATION_IND) {
            return DownloadOutcome.Success(states.replacement(id)?.let { MessageKey(MessageKind.MMS, it) })
        }
        val length = withContext(Dispatchers.IO) { file?.takeIf { it.exists() }?.length() ?: 0L }
        if (length > MmsLimits.MAX_PDU_BYTES) return DownloadOutcome.Failed("The message is too large", retryable = false)
        val bytes = withContext(Dispatchers.IO) { file?.takeIf { length > 0 }?.readBytes() }
            ?: return DownloadOutcome.Failed("The carrier returned an empty message", retryable = true)
        val retrieved = when (val decoded = MmsPduDecoder.decode(bytes)) {
            is PduDecodeResult.Failure -> return DownloadOutcome.Failed("Unreadable MMS: ${decoded.error.message}", retryable = false)
            is PduDecodeResult.Success -> decoded.pdu as? RetrieveConf
                ?: return DownloadOutcome.Failed("Unexpected response from the carrier", retryable = false)
        }
        if (!retrieved.isRetrieveOk) {
            val status = retrieved.retrieveStatus ?: RetrieveStatus.ERROR_PERMANENT_FAILURE
            return DownloadOutcome.Failed(
                retrieved.retrieveText?.takeIf { it.isNotBlank() } ?: "The carrier could not deliver this message",
                retryable = RetrieveStatus.isTransient(status),
            )
        }
        val effectiveSub = if (subId >= 0) subId else info.subId
        val newId = persister.insertRetrieved(retrieved, effectiveSub)
            ?: return DownloadOutcome.Failed("Could not save the message", retryable = true)
        persister.delete(id)
        states.setDone(id, newId)
        val newKey = MessageKey(MessageKind.MMS, newId)

        val transactionId = info.transactionId ?: retrieved.transactionId
        if (settings.sendMmsNotifyResponse && transactionId != null) sendManager.sendNotifyResponse(transactionId, effectiveSub)
        reader.message(newKey)?.let { dispatcher.dispatch(it) }
        return DownloadOutcome.Success(newKey)
    }

    private fun startDownload(id: Long, contentLocation: String, subId: Int, attempt: Int): Boolean = try {
        // Rows can predate Dak (written by another SMS app) or be retried later: re-check before every fetch.
        require(MmsSafety.isDownloadableContentLocation(contentLocation)) { "unsafe content location" }
        val file = MmsFiles.newFile(context, "download-$id")
        val intent = Intent(context, MmsDownloadedReceiver::class.java)
            .setAction(MmsDownloadedReceiver.ACTION_MMS_DOWNLOADED)
            .setData(Uri.parse("dak-mms://download/$id/$attempt"))
            .putExtra(MmsDownloadedReceiver.EXTRA_MESSAGE_ID, id)
            .putExtra(MmsDownloadedReceiver.EXTRA_FILE, file.absolutePath)
            .putExtra(MmsDownloadedReceiver.EXTRA_SUB_ID, subId)
            .putExtra(MmsDownloadedReceiver.EXTRA_ATTEMPT, attempt)
        val pi = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntentFlags.mutableResult)
        SmsManagers.forSubscription(context, subId)
            .downloadMultimediaMessage(context, contentLocation, MmsFiles.contentUri(context, file), null, pi)
        true
    } catch (e: Exception) {
        Log.w(TAG, "downloadMultimediaMessage rejected: ${e.javaClass.simpleName}")
        false
    }

    /** Lets handlers (notifications) know about a message that will not download on its own. */
    private suspend fun notifyHandlers(id: Long) {
        reader.message(MessageKey(MessageKind.MMS, id))?.let { dispatcher.dispatch(it) }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_SECONDS = 30L
        const val RESULT_TIMEOUT_MILLIS = 2 * 60_000L
    }
}
