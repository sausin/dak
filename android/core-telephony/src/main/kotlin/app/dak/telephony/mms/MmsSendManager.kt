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
import app.dak.mms.pdu.MmsPduDecoder
import app.dak.mms.pdu.MmsPduEncoder
import app.dak.mms.pdu.MmsStatus
import app.dak.mms.pdu.NotifyRespInd
import app.dak.mms.pdu.ResponseStatus
import app.dak.mms.pdu.SendConf
import app.dak.telephony.OutgoingMmsPart
import app.dak.telephony.SendResult
import app.dak.telephony.TelephonySettings
import app.dak.telephony.internal.PendingIntentFlags
import app.dak.telephony.internal.SmsManagers
import app.dak.telephony.internal.TAG
import app.dak.telephony.provider.MmsColumns
import app.dak.telephony.provider.TelephonyProviderWriter
import app.dak.telephony.send.RetryPolicy
import app.dak.telephony.send.SendFailureStore
import app.dak.telephony.send.SendScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
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
            ?: return SendResult.Failed("Could not open the conversation; is Dak the default SMS app?")
        val req = MmsMessageBuilder.build(
            to = addresses,
            text = text,
            attachments = parts.map { MmsMessageBuilder.Attachment(it.mimeType, it.fileName, it.bytes) },
            subject = subject,
            dateSeconds = System.currentTimeMillis() / 1000,
            requestDeliveryReport = requestDeliveryReport && settings.requestMmsDeliveryReports,
        )
        val bytes = MmsPduEncoder.encode(req)
        val limit = maxMessageSize(subId)
        if (bytes.size > limit) {
            return SendResult.Failed("Too large for this carrier: ${bytes.size / 1024} KB (limit ${limit / 1024} KB)")
        }
        val id = persister.insertOutgoing(req, subId, thread, bytes.size)
            ?: return SendResult.Failed("Could not save the message; is Dak the default SMS app?")
        dispatch(id, bytes, subId, attempt = 1)
        return SendResult.Queued(listOf(MessageKey(MessageKind.MMS, id)))
    }

    /** Re-encodes a stored outgoing MMS from the provider and sends it again. */
    suspend fun resend(id: Long, attempt: Int): SendResult {
        val (req, subId) = persister.loadOutgoing(id) ?: return SendResult.Failed("Message not found")
        failures.clear(MessageKey(MessageKind.MMS, id))
        dispatch(id, MmsPduEncoder.encode(req), subId, attempt)
        return SendResult.Queued(listOf(MessageKey(MessageKind.MMS, id)))
    }

    /** Result of `sendMultimediaMessage` (called by [MmsSentReceiver]). */
    suspend fun onSent(intent: Intent, resultCode: Int) {
        MmsFiles.resolve(context, intent.getStringExtra(MmsSentReceiver.EXTRA_FILE))?.delete()
        if (intent.getBooleanExtra(MmsSentReceiver.EXTRA_IS_NOTIFY_RESPONSE, false)) {
            if (resultCode != Activity.RESULT_OK) Log.i(TAG, "m-notifyresp-ind not accepted: $resultCode")
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
            } else {
                handleFailure(
                    id, attempt, ResponseStatus.describe(conf.responseStatus),
                    ResponseStatus.isTransient(conf.responseStatus), conf.responseStatus,
                )
            }
        } else {
            val http = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
            handleFailure(id, attempt, MmsResultCodes.describe(resultCode, http), MmsResultCodes.isRetryable(resultCode), null)
        }
    }

    /**
     * Sends m-notifyresp-ind (status Retrieved) for a downloaded message, when enabled in [TelephonySettings].
     * Best effort: the result is ignored.
     */
    suspend fun sendNotifyResponse(transactionId: String, subId: Int) {
        val bytes = MmsPduEncoder.encode(NotifyRespInd(transactionId = transactionId, status = MmsStatus.RETRIEVED))
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
                    .sendMultimediaMessage(context, MmsFiles.contentUri(context, file), null, null, pi)
            } catch (e: Exception) {
                Log.w(TAG, "m-notifyresp-ind not sent: ${e.javaClass.simpleName}")
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
        if (!started) handleFailure(id, attempt, MmsResultCodes.describe(MmsResultCodes.UNSPECIFIED), true, null)
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
    @Suppress("DEPRECATION")
    private fun maxMessageSize(subId: Int): Int = try {
        SmsManagers.forSubscription(context, subId).carrierConfigValues
            ?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MAX_SIZE)
            ?.takeIf { it > 0 } ?: DEFAULT_MAX_SIZE
    } catch (e: Exception) {
        DEFAULT_MAX_SIZE
    }

    private companion object {
        const val DEFAULT_MAX_SIZE = 300 * 1024
    }
}
