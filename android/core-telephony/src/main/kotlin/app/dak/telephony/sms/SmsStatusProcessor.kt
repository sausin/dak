package app.dak.telephony.sms

import android.app.Activity
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.telephony.Failure
import app.dak.telephony.FailureReasons
import app.dak.telephony.OutgoingStatus
import app.dak.telephony.SentDispatcher
import app.dak.telephony.internal.TAG
import app.dak.telephony.provider.SmsColumns
import app.dak.telephony.provider.TelephonyProviderWriter
import app.dak.telephony.send.RetryPolicy
import app.dak.telephony.send.SendFailureStore
import app.dak.telephony.send.SendProgressStore
import app.dak.telephony.send.SendScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies SMS sent / delivered results to the provider once every part of an attempt has reported: OUTBOX -> SENT
 * (all parts sent), QUEUED + automatic retry (no part sent, retryable radio/network failure), or FAILED (permanent
 * failure, retries used up, or only some parts sent: see [markPartlySent]); delivery reports set `status`
 * COMPLETE / PENDING / FAILED.
 */
@Singleton
class SmsStatusProcessor @Inject constructor(
    private val writer: TelephonyProviderWriter,
    private val progress: SendProgressStore,
    private val failures: SendFailureStore,
    private val scheduler: SendScheduler,
    private val sent: SentDispatcher,
) {
    suspend fun onSent(intent: Intent, resultCode: Int) {
        val id = intent.getLongExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, -1L)
        if (id < 0) return
        val part = intent.getIntExtra(SmsStatusReceiver.EXTRA_PART, 0)
        val count = intent.getIntExtra(SmsStatusReceiver.EXTRA_PART_COUNT, 1)
        val attempt = intent.getIntExtra(SmsStatusReceiver.EXTRA_ATTEMPT, 1)
        val deliveryRequested = intent.getBooleanExtra(SmsStatusReceiver.EXTRA_DELIVERY_REQUESTED, false)
        val key = MessageKey(MessageKind.SMS, id)

        val ok = resultCode == Activity.RESULT_OK
        if (!ok) {
            val errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, 0)
            Log.w(TAG, "SMS part ${part + 1}/$count failed: result=$resultCode errorCode=$errorCode attempt=$attempt")
        }
        // Decide only once the whole attempt has reported: acting on the first failed part would resend parts that
        // are still on their way (or already delivered).
        val settled = progress.recordSendResult(id, part, count, attempt, ok, resultCode) ?: return
        when (settled.outcome) {
            SendAttemptOutcome.SENT -> {
                writer.markSmsStatus(key, OutgoingStatus.SENT)
                failures.clear(key)
                if (!deliveryRequested) progress.clear(id)
                sent.dispatch(key)
            }
            SendAttemptOutcome.ALL_FAILED -> handleFailure(key, attempt, settled.failureCode)
            SendAttemptOutcome.PARTIAL -> markPartlySent(key, settled)
            SendAttemptOutcome.IN_FLIGHT -> Unit
        }
    }

    /**
     * Some parts went out, some did not. SMS has no way to send just the missing parts (a resend is a new
     * concatenated message with a new reference, so the recipient's phone cannot merge it with the parts it already
     * has), and an automatic whole-message resend would show the recipient those parts twice. So: FAILED with an
     * accurate reason and no automatic retry; the user's "retry" resends the whole message knowingly.
     * The progress record is kept so late delivery reports cannot flip the message to delivered.
     */
    private suspend fun markPartlySent(key: MessageKey, settled: PartProgress) {
        failures.set(key, SmsResultCodes.partial(settled.sentParts.size, settled.partCount).encode())
        writer.markSmsFailed(key.providerId, settled.failureCode)
        scheduler.cancel(key)
    }

    /**
     * Records a failed attempt and either schedules the next one (QUEUED) or gives up (FAILED, with the reason
     * kept for the "tap to retry" bubble). Also used by the sender when the platform call itself throws.
     */
    suspend fun handleFailure(key: MessageKey, attempt: Int, resultCode: Int) {
        failures.set(key, SmsResultCodes.failureOf(resultCode).encode())
        if (RetryPolicy.shouldRetry(attempt, RetryPolicy.MAX_SMS_ATTEMPTS, SmsResultCodes.isRetryable(resultCode))) {
            writer.markSmsStatus(key, OutgoingStatus.QUEUED)
            scheduler.enqueue(key, attempt + 1, RetryPolicy.delayMillis(attempt), slotReserved = false)
        } else {
            writer.markSmsFailed(key.providerId, resultCode)
            progress.clear(key.providerId)
        }
    }

    suspend fun onDelivered(intent: Intent) {
        val id = intent.getLongExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, -1L)
        if (id < 0) return
        val part = intent.getIntExtra(SmsStatusReceiver.EXTRA_PART, 0)
        val count = intent.getIntExtra(SmsStatusReceiver.EXTRA_PART_COUNT, 1)
        val pdu = intent.getByteArrayExtra(EXTRA_PDU) ?: return
        val format = intent.getStringExtra(EXTRA_FORMAT)
        val report: SmsMessage = try {
            SmsMessage.createFromPdu(pdu, format)
        } catch (e: Exception) {
            null
        } ?: return
        val key = MessageKey(MessageKind.SMS, id)
        when (DeliveryStatus.outcome(report.status, format)) {
            DeliveryOutcome.DELIVERED -> if (progress.recordDelivered(id, part, count)) {
                writer.markSmsStatus(key, OutgoingStatus.DELIVERED)
                progress.clear(id)
            }
            DeliveryOutcome.PENDING -> writer.setSmsDeliveryStatus(id, SmsColumns.STATUS_PENDING)
            DeliveryOutcome.FAILED -> {
                writer.setSmsDeliveryStatus(id, SmsColumns.STATUS_FAILED)
                failures.set(key, FailureReasons.encode(Failure.SMS_NOT_DELIVERED))
                progress.clear(id)
            }
        }
    }

    private companion object {
        /** Extras the platform fills into sent / delivery PendingIntents. */
        const val EXTRA_ERROR_CODE = "errorCode"
        const val EXTRA_PDU = "pdu"
        const val EXTRA_FORMAT = "format"
    }
}
