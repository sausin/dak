package app.dak.telephony.send

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.telephony.MessageSender
import app.dak.telephony.OutgoingMms
import app.dak.telephony.OutgoingSms
import app.dak.telephony.OutgoingStatus
import app.dak.telephony.SendResult
import app.dak.telephony.SimRepository
import app.dak.telephony.TelephonySettings
import app.dak.telephony.internal.PendingIntentFlags
import app.dak.telephony.internal.SmsManagers
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.int
import app.dak.telephony.internal.isUsableSubId
import app.dak.telephony.internal.safeQuery
import app.dak.telephony.internal.string
import app.dak.telephony.mms.MmsSendManager
import app.dak.telephony.number.TelephonyNumberNormalizer
import app.dak.telephony.provider.ProviderUris
import app.dak.telephony.provider.SmsColumns
import app.dak.telephony.provider.TelephonyProviderWriter
import app.dak.telephony.sms.SmsResultCodes
import app.dak.telephony.sms.SmsStatusProcessor
import app.dak.telephony.sms.SmsStatusReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MessageSender] implementation.
 *
 * SMS: each recipient gets its own provider row, written to the outbox first; the text is split with
 * `divideMessage` and sent with per-part sent (and, if requested, delivery) PendingIntents on the chosen
 * subscription's SmsManager. Sends are spread by [SendRateLimiter]; failures are retried with backoff by
 * [SmsStatusProcessor] + [SendScheduler]. MMS is delegated to [MmsSendManager]. Addresses are normalised to E.164
 * with the sending SIM's home country when the setting is on.
 */
@Singleton
class TelephonyMessageSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writer: TelephonyProviderWriter,
    private val normalizer: TelephonyNumberNormalizer,
    private val sims: SimRepository,
    private val settings: TelephonySettings,
    private val limiter: SendRateLimiter,
    private val progress: SendProgressStore,
    private val failures: SendFailureStore,
    private val scheduler: SendScheduler,
    private val statusProcessor: SmsStatusProcessor,
    private val mmsSender: MmsSendManager,
) : MessageSender {

    override suspend fun sendSms(sms: OutgoingSms): SendResult {
        if (sms.body.isEmpty()) return SendResult.Failed("Message is empty")
        val recipients = sms.addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (recipients.isEmpty()) return SendResult.Failed("No recipient")
        val subId = resolveSubId(sms.subId)
        val deliveryReport = sms.requestDeliveryReport && settings.requestSmsDeliveryReports
        val threadId = if (recipients.size == 1) sms.threadId else null
        val keys = ArrayList<MessageKey>(recipients.size)
        for (raw in recipients) {
            val address = outgoingAddress(raw, subId)
            val key = writer.insertOutgoing(address, sms.body, subId, threadId, deliveryReport) ?: continue
            keys += key
            val delay = limiter.reserve(System.currentTimeMillis())
            if (delay == 0L) {
                dispatchSms(key.providerId, address, sms.body, subId, deliveryReport, attempt = 1)
            } else {
                writer.markSmsStatus(key, OutgoingStatus.QUEUED)
                scheduler.enqueue(key, attempt = 1, delayMillis = delay, slotReserved = true)
            }
        }
        return if (keys.isEmpty()) {
            SendResult.Failed("Could not save the message; is Dak the default SMS app?")
        } else {
            SendResult.Queued(keys)
        }
    }

    override suspend fun sendMms(mms: OutgoingMms): SendResult {
        val recipients = mms.addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (recipients.isEmpty()) return SendResult.Failed("No recipient")
        if (mms.text.isNullOrEmpty() && mms.parts.isEmpty()) return SendResult.Failed("Message is empty")
        val subId = resolveSubId(mms.subId)
        val addresses = recipients.map { outgoingAddress(it, subId) }
        return mmsSender.send(addresses, mms.text, mms.parts, mms.subject, subId, mms.threadId, mms.requestDeliveryReport)
    }

    override suspend fun retry(key: MessageKey): SendResult {
        failures.clear(key)
        scheduler.cancel(key)
        return when (key.kind) {
            MessageKind.SMS -> {
                loadSms(key.providerId) ?: return SendResult.Failed("Message not found")
                writer.markSmsStatus(key, OutgoingStatus.QUEUED)
                runScheduled(key, attempt = 1, slotReserved = false)
                SendResult.Queued(listOf(key))
            }
            MessageKind.MMS -> mmsSender.resend(key.providerId, attempt = 1)
        }
    }

    override fun failureReason(key: MessageKey): String? = failures.get(key)

    /**
     * Runs a deferred attempt (from [SendRetryWorker]). Skips messages that were deleted or already left the
     * outbox/queue; re-books a rate-limit slot unless one was reserved when scheduling.
     */
    suspend fun runScheduled(key: MessageKey, attempt: Int, slotReserved: Boolean) {
        when (key.kind) {
            MessageKind.SMS -> {
                val row = loadSms(key.providerId) ?: return
                if (row.type != SmsColumns.TYPE_QUEUED && row.type != SmsColumns.TYPE_OUTBOX && row.type != SmsColumns.TYPE_FAILED) return
                if (!slotReserved) {
                    val delay = limiter.reserve(System.currentTimeMillis())
                    if (delay > 0L) {
                        writer.markSmsStatus(key, OutgoingStatus.QUEUED)
                        scheduler.enqueue(key, attempt, delay, slotReserved = true)
                        return
                    }
                }
                dispatchSms(key.providerId, row.address, row.body, resolveSubId(row.subId), row.deliveryRequested, attempt)
            }
            MessageKind.MMS -> mmsSender.resend(key.providerId, attempt)
        }
    }

    // --- SMS dispatch ---------------------------------------------------------------------------------------

    private suspend fun dispatchSms(
        id: Long,
        address: String,
        body: String,
        subId: Int,
        deliveryReport: Boolean,
        attempt: Int,
    ) {
        val key = MessageKey(MessageKind.SMS, id)
        writer.markSmsStatus(key, OutgoingStatus.SENDING)
        val failedCode = withContext(Dispatchers.IO) {
            try {
                val manager = SmsManagers.forSubscription(context, subId)
                val divided: ArrayList<String>? = try {
                    manager.divideMessage(body)
                } catch (e: Exception) {
                    null
                }
                val parts: ArrayList<String> = divided?.takeIf { it.isNotEmpty() } ?: arrayListOf(body)
                progress.begin(id, parts.size, System.currentTimeMillis())
                val sent = ArrayList<PendingIntent>(parts.size)
                val delivered: ArrayList<PendingIntent>? = if (deliveryReport) ArrayList(parts.size) else null
                for (i in parts.indices) {
                    sent += statusIntent(SmsStatusReceiver.ACTION_SENT, id, i, parts.size, attempt, deliveryReport)
                    delivered?.add(statusIntent(SmsStatusReceiver.ACTION_DELIVERED, id, i, parts.size, attempt, deliveryReport))
                }
                if (parts.size == 1) {
                    manager.sendTextMessage(address, null, parts[0], sent[0], delivered?.get(0))
                } else {
                    manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "SmsManager rejected the send: ${e.javaClass.simpleName}")
                SmsResultCodes.GENERIC_FAILURE
            }
        }
        if (failedCode != null) statusProcessor.handleFailure(key, attempt, failedCode)
    }

    private fun statusIntent(action: String, id: Long, part: Int, count: Int, attempt: Int, deliveryRequested: Boolean): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            // Unique data per message/part/attempt so PendingIntents never collapse into one another.
            .setData(Uri.parse("dak-sms://status/$id/$part/$attempt"))
            .putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, id)
            .putExtra(SmsStatusReceiver.EXTRA_PART, part)
            .putExtra(SmsStatusReceiver.EXTRA_PART_COUNT, count)
            .putExtra(SmsStatusReceiver.EXTRA_ATTEMPT, attempt)
            .putExtra(SmsStatusReceiver.EXTRA_DELIVERY_REQUESTED, deliveryRequested)
        val requestCode = (id * 31 + part).toInt()
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntentFlags.mutableResult)
    }

    // --- Helpers --------------------------------------------------------------------------------------------

    private fun resolveSubId(requested: Int): Int = if (isUsableSubId(requested)) requested else sims.defaultSmsSubId()

    private fun outgoingAddress(raw: String, subId: Int): String =
        if (settings.normalizeOutgoingNumbers) normalizer.normalize(raw, subId) else raw

    private class SmsRow(val address: String, val body: String, val subId: Int, val type: Int, val deliveryRequested: Boolean)

    private suspend fun loadSms(id: Long): SmsRow? = withContext(Dispatchers.IO) {
        context.contentResolver.safeQuery(ProviderUris.sms(id))?.use { c ->
            if (!c.moveToFirst()) return@use null
            val address = c.string(SmsColumns.ADDRESS) ?: return@use null
            SmsRow(
                address = address,
                body = c.string(SmsColumns.BODY).orEmpty(),
                subId = c.int(SmsColumns.SUBSCRIPTION_ID, -1),
                type = c.int(SmsColumns.TYPE, SmsColumns.TYPE_OUTBOX),
                deliveryRequested = c.int(SmsColumns.STATUS, SmsColumns.STATUS_NONE) == SmsColumns.STATUS_PENDING,
            )
        }
    }
}
