package app.dak.telephony.provider

import app.dak.core.model.DeliveryStatus
import app.dak.core.model.MessageBox
import app.dak.core.model.isOutgoing
import app.dak.mms.pdu.MmsStatus
import app.dak.telephony.sms.DeliveryOutcome
import app.dak.telephony.sms.DeliveryStatus as ReportStatus

/**
 * Pure mapping from the provider's delivery columns to [DeliveryStatus] (JVM-tested).
 *
 * - SMS `status` (`Telephony.Sms.STATUS_*`): [SmsColumns.STATUS_NONE] -> NONE, [SmsColumns.STATUS_COMPLETE] ->
 *   DELIVERED, [SmsColumns.STATUS_PENDING] -> PENDING, [SmsColumns.STATUS_FAILED] -> FAILED. Other apps (and older
 *   AOSP Messaging) store the raw TP-Status instead, so whole ranges are read the same way (0x00-0x1F completed,
 *   0x20-0x3F still trying, 0x40+ failed), and values above 0xFF are 3GPP2 (CDMA) report statuses (`status << 16`).
 * - MMS `st` of an outgoing message (`X-Mms-Status` written by m-delivery-ind): Retrieved / Forwarded -> DELIVERED,
 *   Expired / Rejected / Unrecognised / Unreachable -> FAILED, Deferred / Indeterminate -> PENDING; unset -> PENDING
 *   when a report was requested (`d_rpt`), else NONE.
 *
 * Incoming (and draft) messages are always NONE.
 */
internal object DeliveryStatusMapping {

    fun sms(box: MessageBox, status: Int): DeliveryStatus {
        if (!box.isOutgoing) return DeliveryStatus.NONE
        return when {
            status < 0 -> DeliveryStatus.NONE
            status > 0xFF -> fromOutcome(ReportStatus.outcome(status, ReportStatus.FORMAT_3GPP2))
            status < SmsColumns.STATUS_PENDING -> DeliveryStatus.DELIVERED
            status < SmsColumns.STATUS_FAILED -> DeliveryStatus.PENDING
            else -> DeliveryStatus.FAILED
        }
    }

    /**
     * When an outgoing SMS was delivered: its `date_sent`, which the delivery-report handler sets to the report's
     * arrival time (outgoing rows otherwise keep 0 there). Null when unknown or not delivered.
     */
    fun smsDeliveredAt(status: DeliveryStatus, dateSentMillis: Long): Long? =
        dateSentMillis.takeIf { status == DeliveryStatus.DELIVERED && it > 0 }

    fun mms(box: MessageBox, st: Int, deliveryReportRequested: Boolean): DeliveryStatus {
        if (!box.isOutgoing) return DeliveryStatus.NONE
        return when (st) {
            MmsStatus.RETRIEVED, MmsStatus.FORWARDED -> DeliveryStatus.DELIVERED
            MmsStatus.EXPIRED, MmsStatus.REJECTED, MmsStatus.UNRECOGNISED, MmsStatus.UNREACHABLE -> DeliveryStatus.FAILED
            MmsStatus.DEFERRED, MmsStatus.INDETERMINATE -> DeliveryStatus.PENDING
            else -> if (deliveryReportRequested) DeliveryStatus.PENDING else DeliveryStatus.NONE
        }
    }

    /**
     * The `st` to store for a group MMS from its per-recipient m-delivery-ind statuses ([reports]: recipient ->
     * `X-Mms-Status`) and its recipient count: Retrieved only once every recipient reported delivered, the first
     * failure as soon as one recipient failed, else Deferred (still pending). A single recipient's status is kept
     * as reported.
     */
    fun aggregateMmsSt(recipientCount: Int, reports: Map<String, Int>): Int {
        if (recipientCount <= 1 && reports.size <= 1) return reports.values.firstOrNull() ?: MmsStatus.DEFERRED
        val perRecipient = reports.values.map { mms(MessageBox.SENT, it, deliveryReportRequested = true) }
        val missing = (recipientCount - reports.size).coerceAtLeast(0)
        val all = perRecipient + List(missing) { DeliveryStatus.PENDING }
        return when (DeliveryStatus.aggregate(all)) {
            DeliveryStatus.DELIVERED -> MmsStatus.RETRIEVED
            DeliveryStatus.FAILED -> reports.values.first { mms(MessageBox.SENT, it, true) == DeliveryStatus.FAILED }
            else -> MmsStatus.DEFERRED
        }
    }

    private fun fromOutcome(outcome: DeliveryOutcome): DeliveryStatus = when (outcome) {
        DeliveryOutcome.DELIVERED -> DeliveryStatus.DELIVERED
        DeliveryOutcome.PENDING -> DeliveryStatus.PENDING
        DeliveryOutcome.FAILED -> DeliveryStatus.FAILED
    }
}
