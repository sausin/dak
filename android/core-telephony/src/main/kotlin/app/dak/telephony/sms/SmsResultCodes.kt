package app.dak.telephony.sms

import app.dak.telephony.Failure
import app.dak.telephony.FailureReason

/**
 * Result codes delivered to SMS sent PendingIntents (`SmsManager.RESULT_*`; `Activity.RESULT_OK` = -1 is
 * success). Literals keep this JVM-testable and independent of the constants' API levels.
 */
internal object SmsResultCodes {
    const val RESULT_OK = -1
    const val GENERIC_FAILURE = 1
    const val RADIO_OFF = 2
    const val NULL_PDU = 3
    const val NO_SERVICE = 4
    const val LIMIT_EXCEEDED = 5
    const val FDN_CHECK_FAILURE = 6
    const val SHORT_CODE_NOT_ALLOWED = 7
    const val SHORT_CODE_NEVER_ALLOWED = 8
    const val RADIO_NOT_AVAILABLE = 9
    const val NETWORK_REJECT = 10
    const val INVALID_ARGUMENTS = 11
    const val INVALID_STATE = 12
    const val NO_MEMORY = 13
    const val INVALID_SMS_FORMAT = 14
    const val SYSTEM_ERROR = 15
    const val MODEM_ERROR = 16
    const val NETWORK_ERROR = 17
    const val ENCODING_ERROR = 18
    const val INVALID_SMSC_ADDRESS = 19
    const val OPERATION_NOT_ALLOWED = 20
    const val INTERNAL_ERROR = 21
    const val NO_RESOURCES = 22
    const val CANCELLED = 23
    const val REQUEST_NOT_SUPPORTED = 24

    /**
     * Failures worth retrying automatically: no service / radio off (including DSDS single-radio conflicts while
     * the other SIM is in a call), transient modem and network errors, and the platform rate limit.
     */
    fun isRetryable(code: Int): Boolean = when (code) {
        GENERIC_FAILURE, RADIO_OFF, NO_SERVICE, LIMIT_EXCEEDED, RADIO_NOT_AVAILABLE, NETWORK_REJECT,
        INVALID_STATE, NO_MEMORY, SYSTEM_ERROR, MODEM_ERROR, NETWORK_ERROR, INTERNAL_ERROR, NO_RESOURCES,
        -> true
        else -> false
    }

    /** What [code] means, for the UI (see [app.dak.telephony.FailureReasonText]). */
    fun failureOf(code: Int): FailureReason = when (code) {
        RESULT_OK -> FailureReason(Failure.SMS_SENT)
        GENERIC_FAILURE -> FailureReason(Failure.SMS_FAILED)
        RADIO_OFF -> FailureReason(Failure.SMS_RADIO_OFF)
        NULL_PDU -> FailureReason(Failure.SMS_NOT_ENCODED)
        NO_SERVICE -> FailureReason(Failure.SMS_NO_SERVICE)
        LIMIT_EXCEEDED -> FailureReason(Failure.SMS_LIMIT_EXCEEDED)
        FDN_CHECK_FAILURE -> FailureReason(Failure.SMS_FDN_BLOCKED)
        SHORT_CODE_NOT_ALLOWED, SHORT_CODE_NEVER_ALLOWED -> FailureReason(Failure.SMS_SHORT_CODE_NOT_ALLOWED)
        RADIO_NOT_AVAILABLE -> FailureReason(Failure.SMS_RADIO_NOT_AVAILABLE)
        NETWORK_REJECT -> FailureReason(Failure.SMS_NETWORK_REJECT)
        INVALID_ARGUMENTS, INVALID_SMS_FORMAT, ENCODING_ERROR -> FailureReason(Failure.SMS_BAD_FORMAT)
        INVALID_SMSC_ADDRESS -> FailureReason(Failure.SMS_INVALID_SMSC)
        OPERATION_NOT_ALLOWED -> FailureReason(Failure.SMS_NOT_ALLOWED)
        CANCELLED -> FailureReason(Failure.SMS_CANCELLED)
        REQUEST_NOT_SUPPORTED -> FailureReason(Failure.SMS_NOT_SUPPORTED)
        MODEM_ERROR, SYSTEM_ERROR, INTERNAL_ERROR, NO_MEMORY, NO_RESOURCES, INVALID_STATE -> FailureReason(Failure.SMS_TEMPORARY_ERROR)
        NETWORK_ERROR -> FailureReason(Failure.SMS_NETWORK_ERROR)
        else -> FailureReason(Failure.SMS_UNKNOWN_CODE, listOf(code))
    }

    /** English text for [code] (logs, tests). The stored/displayed form is [failureOf]`.encode()`. */
    fun describe(code: Int): String = failureOf(code).english()

    /** Reason for a multipart SMS of which only [sentParts] of [partCount] parts went out. */
    fun partial(sentParts: Int, partCount: Int): FailureReason = FailureReason(Failure.SMS_PARTIAL, listOf(sentParts, partCount))

    /** English text of [partial]. */
    fun describePartial(sentParts: Int, partCount: Int): String = partial(sentParts, partCount).english()
}
