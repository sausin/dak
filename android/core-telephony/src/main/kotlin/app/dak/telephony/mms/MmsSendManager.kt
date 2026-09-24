package app.dak.telephony.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.mms.pdu.MmsMessageBuilder
import app.dak.mms.pdu.MmsPdu
import app.dak.mms.pdu.MmsPduDecoder
import app.dak.mms.pdu.MmsPduEncoder
import app.dak.mms.pdu.MmsSafety
import app.dak.mms.pdu.NotifyRespInd
import app.dak.mms.pdu.ResponseStatus
import app.dak.mms.pdu.SendConf
import app.dak.telephony.Failure
import app.dak.telephony.FailureReasons
import app.dak.telephony.OutgoingMmsPart
import app.dak.telephony.SendResult
import app.dak.telephony.SentDispatcher
import app.dak.telephony.TelephonySettings
import app.dak.telephony.carrier.CarrierConfigRepository
import app.dak.telephony.carrier.ReportPolicy
import app.dak.telephony.carrier.SendModePolicy
import app.dak.telephony.internal.PendingIntentFlags
import app.dak.telephony.internal.SmsManagers
import app.dak.telephony.internal.TAG
import app.dak.telephony.provider.MmsColumns
import app.dak.telephony.provider.TelephonyProviderWriter
import app.dak.telephony.send.RetryPolicy
import app.dak.telephony.send.SendFailureStore
import app.dak.telephony.send.SendScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends MMS: builds the m-send-req (SMIL + parts) with :mms-pdu, files it in the provider outbox, hands the PDU
 * to `SmsManager.sendMultimediaMessage` through [MmsFileProvider], and applies the result (m-send-conf) from
 * [MmsSentReceiver]: SENT with the MMSC Message-ID, or automatic retry / FAILED with a visible reason.
 */
@Singleton
class MmsSendManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val persister: MmsPersister,
    private val writer: TelephonyProviderWriter,
    private val settings: TelephonySettings,
    private val failures: SendFailureStore,
    private val scheduler: SendScheduler,
    private val carrierConfig: CarrierConfigRepository,
    private val sent: SentDispatcher,
) {

    suspend fun send(
        addresses: List<String>,
        text: String?,
        parts: List<OutgoingMmsPart>,
        subject: String?,
        subId: Int,
        threadId: Long?,
        requestDeliveryReport: Boolean = true,
    ): SendResult {
        val thread = threadId?.takeIf { it > 0 } ?: writer.threadIdFor(addresses.toSet()).takeIf { it > 0 }
            ?: return SendResult.Failed(FailureReasons.encode(Failure.CONVERSATION_FAILED_NOT_DEFAULT))
        val config = carrierConfig.forSubscription(subId)
        val req = MmsMessageBuilder.build(
            to = addresses,
            text = text,
            attachments = parts.map { MmsMessageBuilder.Attachment(it.mimeType, it.fileName, it.bytes) },
            subject = SendModePolicy.subject(subject, config),
            dateSeconds = System.currentTimeMillis() / 1000,
            // X-Mms-Delivery-Report / X-Mms-Read-Report: the user's settings AND the carrier's report support.
            requestDeliveryReport = ReportPolicy.requestMmsDeliveryReport(requestDeliveryReport, config),
            requestReadReport = ReportPolicy.requestMmsReadReport(settings.sendMmsReadReceipts, config),
        )
        val bytes = MmsPduEncoder.encode(req)
        val limit = maxMessageSize(subId)
        if (bytes.size > limit) {
            return SendResult.Failed(FailureReasons.encode(Failure.MMS_TOO_LARGE_FOR_CARRIER, bytes.size / 1024, limit / 1024))
        }
        val id = persister.insertOutgoing(req, subId, thread, bytes.size)
            ?: return SendResult.Failed(FailureReasons.encode(Failure.SAVE_FAILED_NOT_DEFAULT))
        dispatch(id, bytes, subId, attempt = 1)
        return SendResult.Queued(listOf(MessageKey(MessageKind.MMS, id)))
    }

    /** Re-encodes a stored outgoing MMS from the provider and sends it again. */
    suspend fun resend(id: Long, attempt: Int): SendResult {
        val (req, subId) = persister.loadOutgoing(id) ?: return SendResult.Failed(FailureReasons.encode(Failure.MESSAGE_NOT_FOUND))
        failures.clear(MessageKey(MessageKind.MMS, id))
        dispatch(id, MmsPduEncoder.encode(req), subId, attempt)
        return SendResult.Queued(listOf(MessageKey(MessageKind.MMS, id)))
    }

    /** Result of `sendMultimediaMessage` (called by [MmsSentReceiver]). */
    suspend fun onSent(intent: Intent, resultCode: Int) {
        MmsFiles.resolve(context, intent.getStringExtra(MmsSentReceiver.EXTRA_FILE))?.delete()
        if (intent.getBooleanExtra(MmsSentReceiver.EXTRA_IS_NOTIFY_RESPONSE, false)) {
            if (resultCode != Activity.RESULT_OK) Log.i(TAG, "MMS client PDU not accepted: $resultCode")
            return
        }
        val id = intent.getLongExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, -1L)
        if (id < 0) return
        val attempt = intent.getIntExtra(MmsSentReceiver.EXTRA_ATTEMPT, 1)
        val key = MessageKey(MessageKind.MMS, id)
        if (resultCode == Activity.RESULT_OK) {
            val conf = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.let { MmsPduDecoder.decodeAs<SendConf>(it) }
            if (conf == null || conf.isOk) {
                persister.markSent(id, conf?.messageId, conf?.responseStatus)
                failures.clear(key)
                sent.dispatch(key)
            } else {
                handleFailure(
                    id, attempt, ResponseStatus.describe(conf.responseStatus),
                    ResponseStatus.isTransient(conf.responseStatus), conf.responseStatus,
                )
            }
        } else {
            val http = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
            handleFailure(id, attempt, MmsResultCodes.reason(resultCode, http), MmsResultCodes.isRetryable(resultCode), null)
        }
    }

    /**
     * Sends a client transaction PDU (m-notifyresp-ind, m-acknowledge-ind or m-read-rec-ind, see
     * [app.dak.mms.pdu.MmsClientTransactions]) for a message received on [subId]. Best effort: the result is only
     * logged. When the carrier sets `enabledNotifyWapMMSC` the PDU is posted to the notification's
     * [contentLocation] (as AOSP does), otherwise to the MMSC; an unsafe location is never used.
     */
    suspend fun sendClientPdu(pdu: MmsPdu, subId: Int, contentLocation: String? = null) {
        val bytes = MmsPduEncoder.encode(pdu)
        val location = contentLocation
            ?.takeIf { carrierConfig.forSubscription(subId).notifyWapMmsc && MmsSafety.isDownloadableContentLocation(it) }
        withContext(Dispatchers.IO) {
            try {
                val file = MmsFiles.newFile(context, "notifyresp")
                file.writeBytes(bytes)
                val intent = Intent(context, MmsSentReceiver::class.java)
                    .setAction(MmsSentReceiver.ACTION_MMS_SENT)
                    .setData(Uri.parse("dak-mms://notifyresp/${file.name}"))
                    .putExtra(MmsSentReceiver.EXTRA_FILE, file.absolutePath)
                    .putExtra(MmsSentReceiver.EXTRA_IS_NOTIFY_RESPONSE, true)
                val pi = PendingIntent.getBroadcast(context, file.name.hashCode(), intent, PendingIntentFlags.mutableResult)
                SmsManagers.forSubscription(context, subId)
                    .sendMultimediaMessage(context, MmsFiles.contentUri(context, file), location, null, pi)
                val status = (pdu as? NotifyRespInd)?.status?.let { " status 0x%02X".format(Locale.ROOT, it) }.orEmpty()
                Log.i(TAG, "MMS client PDU 0x%02X%s handed to the platform".format(Locale.ROOT, pdu.messageType, status))
            } catch (e: Exception) {
                Log.w(TAG, "MMS client PDU 0x%02X not sent: %s".format(Locale.ROOT, pdu.messageType, e.javaClass.simpleName))
            }
        }
    }

    private suspend fun dispatch(id: Long, pdu: ByteArray, subId: Int, attempt: Int) {
        persister.setBox(id, MmsColumns.BOX_OUTBOX)
        val started = withContext(Dispatchers.IO) {
            try {
                val file = MmsFiles.newFile(context, "send-$id")
                file.writeBytes(pdu)
                val intent = Intent(context, MmsSentReceiver::class.java)
                    .setAction(MmsSentReceiver.ACTION_MMS_SENT)
                    .setData(Uri.parse("dak-mms://sent/$id/$attempt"))
                    .putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, id)
                    .putExtra(MmsSentReceiver.EXTRA_FILE, file.absolutePath)
                    .putExtra(MmsSentReceiver.EXTRA_ATTEMPT, attempt)
                val pi = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntentFlags.mutableResult)
                SmsManagers.forSubscription(context, subId)
                    .sendMultimediaMessage(context, MmsFiles.contentUri(context, file), null, null, pi)
                true
            } catch (e: Exception) {
                Log.w(TAG, "sendMultimediaMessage rejected: ${e.javaClass.simpleName}")
                false
            }
        }
        if (!started) handleFailure(id, attempt, MmsResultCodes.reason(MmsResultCodes.UNSPECIFIED), true, null)
    }

    private suspend fun handleFailure(id: Long, attempt: Int, reason: String, retryable: Boolean, responseStatus: Int?) {
        val key = MessageKey(MessageKind.MMS, id)
        failures.set(key, reason)
        if (RetryPolicy.shouldRetry(attempt, RetryPolicy.MAX_MMS_ATTEMPTS, retryable)) {
            persister.setBox(id, MmsColumns.BOX_OUTBOX)
            scheduler.enqueue(key, attempt + 1, RetryPolicy.delayMillis(attempt), slotReserved = true)
        } else {
            persister.markFailed(id, responseStatus)
        }
    }

    /** Carrier MMS size limit for [subId] (`maxMessageSize` carrier config), 300 KB when unknown. */
    fun maxMessageSize(subId: Int): Int = carrierConfig.forSubscription(subId).maxMessageSizeBytes
}
