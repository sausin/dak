package app.dak.telephony.mms

import app.dak.mms.pdu.ResponseStatus
import app.dak.telephony.Failure
import app.dak.telephony.FailureReason
import java.util.Locale

/**
 * The MMSC's `X-Mms-Response-Status` of a rejected send as a failure code ([Failure.MMSC_SERVICE_DENIED] …), so it
 * is shown in the app language. English ([FailureReason.english]) reads exactly as [ResponseStatus.describe].
 */
object MmscStatusReasons {

    fun failureOf(status: Int): FailureReason = when (status) {
        ResponseStatus.ERROR_SERVICE_DENIED, 0xE1 -> FailureReason(Failure.MMSC_SERVICE_DENIED)
        ResponseStatus.ERROR_MESSAGE_FORMAT_CORRUPT, 0xE2 -> FailureReason(Failure.MMSC_FORMAT_CORRUPT)
        ResponseStatus.ERROR_SENDING_ADDRESS_UNRESOLVED, 0xC1, 0xE3 -> FailureReason(Failure.MMSC_ADDRESS_UNRESOLVED)
        ResponseStatus.ERROR_MESSAGE_NOT_FOUND, 0xC2, 0xE4 -> FailureReason(Failure.MMSC_MESSAGE_NOT_FOUND)
        ResponseStatus.ERROR_NETWORK_PROBLEM, ResponseStatus.ERROR_TRANSIENT_NETWORK_PROBLEM -> FailureReason(Failure.MMSC_NETWORK_PROBLEM)
        ResponseStatus.ERROR_CONTENT_NOT_ACCEPTED, 0xE5 -> FailureReason(Failure.MMSC_CONTENT_NOT_ACCEPTED)
        ResponseStatus.ERROR_UNSUPPORTED_MESSAGE, 0xEA -> FailureReason(Failure.MMSC_UNSUPPORTED_MESSAGE)
        0xE6 -> FailureReason(Failure.MMSC_REPLY_CHARGING_LIMITATIONS)
        0xE7 -> FailureReason(Failure.MMSC_REPLY_CHARGING_REJECTED)
        0xE8 -> FailureReason(Failure.MMSC_REPLY_CHARGING_FORWARDING_DENIED)
        0xE9 -> FailureReason(Failure.MMSC_REPLY_CHARGING_NOT_SUPPORTED)
        0xEB -> FailureReason(Failure.MMSC_ADDRESS_HIDING_NOT_SUPPORTED)
        0xEC -> FailureReason(Failure.MMSC_LACK_OF_PREPAID_CREDIT)
        else -> FailureReason(if (ResponseStatus.isTransient(status)) Failure.MMSC_TEMPORARY_FAILURE else Failure.MMSC_ERROR, listOf(hex(status)))
    }

    /**
     * The stored reason for a send the MMSC rejected with [status]. A status without its own text falls back to
     * the carrier's `X-Mms-Response-Text` ([carrierText]) when it sent one, then to the generic "MMSC error (0x…)".
     */
    fun reason(status: Int, carrierText: String?): String {
        val failure = failureOf(status)
        val generic = failure.failure == Failure.MMSC_ERROR || failure.failure == Failure.MMSC_TEMPORARY_FAILURE
        val text = carrierText?.trim()?.takeIf { it.isNotEmpty() }
        return if (generic && text != null) text else failure.encode()
    }

    /** Two upper-case hex digits, ASCII whatever the locale (a protocol value, not a number to read). */
    private fun hex(status: Int): String = "%02X".format(Locale.ROOT, status and 0xFF)
}
