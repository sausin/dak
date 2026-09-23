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
import app.dak.telephony.carrier.CarrierConfigRepository
import app.dak.telephony.cost.EmergencyNumberCheck
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
import app.dak.telephony.role.SmsRoleMonitor
import app.dak.telephony.sms.SmsResultCodes
import app.dak.telephony.sms.SmsStatusProcessor
import app.dak.telephony.sms.SmsStatusReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [MessageSender] implementation.
 *
 * SMS: each recipient gets its own provider row, written to the outbox first; the text is split with
 * `divideMessage` and sent with per-part sent (and, if requested, delivery) PendingIntents on the chosen
 * subscription's SmsManager. Sends are spread by [SendRateLimiter]; failures are retried with backoff by
 * [SmsStatusProcessor] + [SendScheduler]. MMS is delegated to [MmsSendManager]. Addresses are normalised to E.164
 * with the sending SIM's home country when the setting is on.
 *
 * While Dak is not the default SMS app ([SmsRoleMonitor]) nothing is attempted and nothing is dropped: due sends are
 * held ([HeldSendStore]) and go out when the role comes back ([resumeHeld]). Texts to emergency numbers are never
 * held: they are handed to the platform at once, with or without a provider row.
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
    private val role: SmsRoleMonitor,
    private val held: HeldSendStore,
    private val carrierConfig: CarrierConfigRepository,
    private val emergencyCheck: EmergencyNumberCheck,
) : MessageSender {

    private val resumeLock = Mutex()

    override suspend fun sendSms(sms: OutgoingSms): SendResult {
        if (sms.body.isEmpty()) return SendResult.Failed("Message is empty")
        val recipients = sms.addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (recipients.isEmpty()) return SendResult.Failed("No recipient")
        val subId = resolveSubId(sms.subId)
        val deliveryReport = sms.requestDeliveryReport && settings.requestSmsDeliveryReports
        if (!role.isDefaultNow()) return holdNewSms(recipients, sms, subId, deliveryReport)
        val threadId = if (recipients.size == 1) sms.threadId else null
        val keys = ArrayList<MessageKey>(recipients.size)
        var emergencySent = false
        for (raw in recipients) {
            val address = outgoingAddress(raw, subId)
            val emergency = isEmergency(raw, subId)
            val key = writer.insertOutgoing(address, sms.body, subId, threadId, deliveryReport)
            if (key == null) {
                // No provider row (role revoked mid-send, provider error): an emergency text still goes out.
                if (emergency) emergencySent = dispatchUnpersisted(address, sms.body, subId) || emergencySent
                continue
            }
            keys += key
            // A text to an emergency number never waits for a rate-limit slot (or behind a bulk send).
            val now = System.currentTimeMillis()
            val delay = if (emergency) limiter.reserveEmergency(now) else limiter.reserve(now)
            if (delay == 0L) {
                dispatchSms(key.providerId, address, sms.body, subId, deliveryReport, attempt = 1)
            } else {
                writer.markSmsStatus(key, OutgoingStatus.QUEUED)
                scheduler.enqueue(key, attempt = 1, delayMillis = delay, slotReserved = true)
            }
        }
        return when {
            keys.isNotEmpty() -> SendResult.Queued(keys)
            emergencySent -> SendResult.Queued(emptyList())
            else -> SendResult.Failed("Could not save the message; is Dak the default SMS app?")
        }
    }

    /**
     * Not the default SMS app: emergency recipients are sent to straight away (the platform files the text itself
     * for a non-default app); everyone else is held until the role is back. Reported as queued: the message is not
     * lost, it waits (callers such as scheduled sends must not mark it failed and drop it).
     */
    private suspend fun holdNewSms(recipients: List<String>, sms: OutgoingSms, subId: Int, deliveryReport: Boolean): SendResult {
        val (emergency, others) = recipients.partition { isEmergency(it, subId) }
        var emergencyRefused = false
        for (raw in emergency) {
            if (!dispatchUnpersisted(outgoingAddress(raw, subId), sms.body, subId)) emergencyRefused = true
        }
        if (others.isNotEmpty()) {
            val kept = held.holdNew(others, sms.body, subId, sms.threadId, deliveryReport, System.currentTimeMillis())
            if (!kept) return SendResult.Failed(NOT_DEFAULT_REASON)
            Log.i(TAG, "not the default SMS app: send held until the role is back")
        } else if (emergencyRefused) {
            return SendResult.Failed("The phone refused to send the emergency text")
        }
        return SendResult.Queued(emptyList())
    }

    override suspend fun sendMms(mms: OutgoingMms): SendResult {
        val recipients = mms.addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (recipients.isEmpty()) return SendResult.Failed("No recipient")
        if (mms.text.isNullOrEmpty() && mms.parts.isEmpty()) return SendResult.Failed("Message is empty")
        // MMS needs a provider row (the platform service reads the PDU we file); without the role there is none.
        if (!role.isDefaultNow()) return SendResult.Failed(NOT_DEFAULT_REASON)
        val subId = resolveSubId(mms.subId)
        val addresses = recipients.map { outgoingAddress(it, subId) }
        return mmsSender.send(addresses, mms.text, mms.parts, mms.subject, subId, mms.threadId, mms.requestDeliveryReport)
    }

    override suspend fun retry(key: MessageKey): SendResult {
        failures.clear(key)
        scheduler.cancel(key)
        return when (key.kind) {
            MessageKind.SMS -> {
                val row = loadSms(key.providerId) ?: return SendResult.Failed("Message not found")
                // A resend after "not delivered" (or a completed report) asks for a fresh report, so the ticks
                // restart instead of keeping the old outcome.
                if (row.status != SmsColumns.STATUS_NONE && row.status != SmsColumns.STATUS_PENDING) {
                    writer.setSmsDeliveryStatus(key.providerId, SmsColumns.STATUS_PENDING)
                }
                writer.markSmsStatus(key, OutgoingStatus.QUEUED)
                runScheduled(key, attempt = 1, slotReserved = false)
                SendResult.Queued(listOf(key))
            }
            MessageKind.MMS -> {
                if (!role.isDefaultNow()) {
                    hold(key)
                    SendResult.Queued(listOf(key))
                } else {
                    mmsSender.resend(key.providerId, attempt = 1)
                }
            }
        }
    }

    override fun failureReason(key: MessageKey): String? = failures.get(key)

    /**
     * Runs a deferred attempt (from [SendRetryWorker]). Skips messages that were deleted or already left the
     * outbox/queue; re-books a rate-limit slot unless one was reserved when scheduling. Held (not attempted) while
     * Dak is not the default SMS app, except for emergency numbers.
     */
    suspend fun runScheduled(key: MessageKey, attempt: Int, slotReserved: Boolean) {
        when (key.kind) {
            MessageKind.SMS -> {
                val row = loadSms(key.providerId)
                if (row == null) {
                    // Unreadable: deleted, or READ_SMS went with the role. Keep it for when the role is back.
                    if (!role.isDefaultNow()) hold(key)
                    return
                }
                if (row.type != SmsColumns.TYPE_QUEUED && row.type != SmsColumns.TYPE_OUTBOX && row.type != SmsColumns.TYPE_FAILED) return
                val emergency = isEmergency(row.address, resolveSubId(row.subId))
                if (!emergency && !role.isDefaultNow()) {
                    hold(key)
                    return
                }
                if (!slotReserved && !emergency) {
                    val delay = limiter.reserve(System.currentTimeMillis())
                    if (delay > 0L) {
                        writer.markSmsStatus(key, OutgoingStatus.QUEUED)
                        scheduler.enqueue(key, attempt, delay, slotReserved = true)
                        return
                    }
                }
                dispatchSms(key.providerId, row.address, row.body, resolveSubId(row.subId), row.deliveryRequested, attempt)
            }
            MessageKind.MMS -> if (role.isDefaultNow()) mmsSender.resend(key.providerId, attempt) else hold(key)
        }
    }

    /**
     * Sends everything held while Dak was not the default SMS app (called by [SmsRoleMonitor] once the role is
     * back). Each entry is removed before it is handed on, under [NonCancellable], so a resume cut short neither
     * loses nor duplicates it; a send that finds the role gone again is simply held again.
     */
    suspend fun resumeHeld() = resumeLock.withLock {
        for (key in held.rows()) {
            if (!role.isDefaultNow()) return@withLock
            withContext(NonCancellable) {
                held.removeRow(key)
                failures.clear(key)
                runScheduled(key, attempt = 1, slotReserved = false)
            }
        }
        for (entry in held.news()) {
            if (!role.isDefaultNow()) return@withLock
            withContext(NonCancellable) {
                held.removeNew(entry.id)
                val result = sendSms(OutgoingSms(entry.addresses, entry.body, entry.subId, entry.threadId, entry.requestDeliveryReport))
                if (result is SendResult.Failed) Log.w(TAG, "held send failed on resume: ${result.reason}")
            }
        }
    }

    private fun hold(key: MessageKey) {
        held.holdRow(key)
        failures.set(key, WAITING_REASON)
        Log.i(TAG, "not the default SMS app: $key held until the role is back")
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
        val separateParts = carrierConfig.forSubscription(subId).sendMultipartSmsAsSeparateMessages
        val failedCode = withContext(Dispatchers.IO) {
            try {
                val manager = SmsManagers.forSubscription(context, subId)
                val divided: ArrayList<String>? = try {
                    manager.divideMessage(body)
                } catch (e: Exception) {
                    null
                }
                val parts: ArrayList<String> = divided?.takeIf { it.isNotEmpty() } ?: arrayListOf(body)
                progress.begin(id, parts.size, System.currentTimeMillis(), attempt)
                val sent = ArrayList<PendingIntent>(parts.size)
                val delivered: ArrayList<PendingIntent>? = if (deliveryReport) ArrayList(parts.size) else null
                for (i in parts.indices) {
                    sent += statusIntent(SmsStatusReceiver.ACTION_SENT, id, i, parts.size, attempt, deliveryReport)
                    delivered?.add(statusIntent(SmsStatusReceiver.ACTION_DELIVERED, id, i, parts.size, attempt, deliveryReport))
                }
                when {
                    parts.size == 1 -> manager.sendTextMessage(address, null, parts[0], sent[0], delivered?.get(0))
                    // Carrier cannot reassemble concatenated SMS (`sendMultipartSmsAsSeparateMessages`): each part is
                    // its own SMS, still tracked per part like a multipart send.
                    separateParts -> for (i in parts.indices) manager.sendTextMessage(address, null, parts[i], sent[i], delivered?.get(i))
                    else -> manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "SmsManager rejected the send: ${e.javaClass.simpleName}")
                SmsResultCodes.GENERIC_FAILURE
            }
        }
        if (failedCode != null) statusProcessor.handleFailure(key, attempt, failedCode)
    }

    /**
     * Emergency text without a provider row (no role, or the insert failed): straight to the platform, no status
     * tracking. Returns false when the platform refused it.
     */
    private suspend fun dispatchUnpersisted(address: String, body: String, subId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val manager = SmsManagers.forSubscription(context, subId)
            val parts = runCatching { manager.divideMessage(body) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: arrayListOf(body)
            if (parts.size == 1) manager.sendTextMessage(address, null, parts[0], null, null)
            else manager.sendMultipartTextMessage(address, null, parts, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "emergency SMS rejected by the platform: ${e.javaClass.simpleName}")
            false
        }
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

    /** Emergency destination for this SIM's home country or the network it is on (see [EmergencyNumberCheck]). */
    private fun isEmergency(address: String, subId: Int): Boolean = emergencyCheck.isEmergency(address, subId)

    private fun outgoingAddress(raw: String, subId: Int): String =
        if (settings.normalizeOutgoingNumbers) normalizer.normalize(raw, subId) else raw

    private class SmsRow(val address: String, val body: String, val subId: Int, val type: Int, val status: Int) {
        val deliveryRequested: Boolean get() = status == SmsColumns.STATUS_PENDING
    }

    private suspend fun loadSms(id: Long): SmsRow? = withContext(Dispatchers.IO) {
        context.contentResolver.safeQuery(ProviderUris.sms(id))?.use { c ->
            if (!c.moveToFirst()) return@use null
            val address = c.string(SmsColumns.ADDRESS) ?: return@use null
            SmsRow(
                address = address,
                body = c.string(SmsColumns.BODY).orEmpty(),
                subId = c.int(SmsColumns.SUBSCRIPTION_ID, -1),
                type = c.int(SmsColumns.TYPE, SmsColumns.TYPE_OUTBOX),
                status = c.int(SmsColumns.STATUS, SmsColumns.STATUS_NONE),
            )
        }
    }

    private companion object {
        const val NOT_DEFAULT_REASON = "Dak is not the default SMS app"
        const val WAITING_REASON = "Waiting: Dak is not the default SMS app"
    }
}
