package app.dak.telephony.sms

import app.dak.telephony.provider.SmsColumns

/** Normalised delivery-report outcome. */
internal enum class DeliveryOutcome { DELIVERED, PENDING, FAILED }

/**
 * Maps the status of a delivery report (`SmsMessage.getStatus()` on the report PDU) to an outcome.
 *
 * - 3GPP (GSM/UMTS/LTE, TP-Status): 0x00–0x1F completed, 0x20–0x3F still trying, 0x40+ failed.
 * - 3GPP2 (CDMA): the platform reports `status << 16` with the error class in bits 24–25 and the message status
 *   code in bits 16–21; class 0 with status "delivered" (2) is success, class 0 otherwise or class 2
 *   (temporary) is pending, class 3 (permanent) is failure.
 */
internal object DeliveryStatus {
    const val FORMAT_3GPP2 = "3gpp2"

    fun outcome(status: Int, format: String?): DeliveryOutcome {
        if (format == FORMAT_3GPP2) {
            val errorClass = (status ushr 24) and 0x03
            val statusCode = (status ushr 16) and 0x3F
            return when (errorClass) {
                0 -> if (statusCode == 2) DeliveryOutcome.DELIVERED else DeliveryOutcome.PENDING
                2 -> DeliveryOutcome.PENDING
                else -> DeliveryOutcome.FAILED
            }
        }
        return when {
            status < 0 -> DeliveryOutcome.FAILED
            status < 0x20 -> DeliveryOutcome.DELIVERED
            status < 0x40 -> DeliveryOutcome.PENDING
            else -> DeliveryOutcome.FAILED
        }
    }

    /** Value for the provider's `status` column. */
    fun providerStatus(outcome: DeliveryOutcome): Int = when (outcome) {
        DeliveryOutcome.DELIVERED -> SmsColumns.STATUS_COMPLETE
        DeliveryOutcome.PENDING -> SmsColumns.STATUS_PENDING
        DeliveryOutcome.FAILED -> SmsColumns.STATUS_FAILED
    }
}
