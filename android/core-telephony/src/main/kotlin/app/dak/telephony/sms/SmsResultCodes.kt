package app.dak.telephony.sms

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

    fun describe(code: Int): String = when (code) {
        RESULT_OK -> "Sent"
        GENERIC_FAILURE -> "Sending failed"
        RADIO_OFF -> "Mobile radio is off (airplane mode or the other SIM is busy)"
        NULL_PDU -> "The message could not be encoded"
        NO_SERVICE -> "No mobile service"
        LIMIT_EXCEEDED -> "Too many messages sent; waiting before retrying"
        FDN_CHECK_FAILURE -> "Blocked by Fixed Dialing Numbers"
        SHORT_CODE_NOT_ALLOWED, SHORT_CODE_NEVER_ALLOWED -> "Sending to this short code is not allowed"
        RADIO_NOT_AVAILABLE -> "Mobile radio is not available"
        NETWORK_REJECT -> "The network rejected the message"
        INVALID_ARGUMENTS, INVALID_SMS_FORMAT, ENCODING_ERROR -> "The message could not be sent in this format"
        INVALID_SMSC_ADDRESS -> "The SIM's message centre number is invalid"
        OPERATION_NOT_ALLOWED -> "Sending is not allowed right now"
        CANCELLED -> "Sending was cancelled"
        REQUEST_NOT_SUPPORTED -> "Sending is not supported on this SIM"
        MODEM_ERROR, SYSTEM_ERROR, INTERNAL_ERROR, NO_MEMORY, NO_RESOURCES, INVALID_STATE -> "Temporary phone error"
        NETWORK_ERROR -> "Network error"
        else -> "Sending failed (code $code)"
    }

    /** Reason for a multipart SMS of which only [sentParts] of [partCount] parts went out. */
    fun describePartial(sentParts: Int, partCount: Int): String =
        "Only $sentParts of $partCount parts were sent. Retrying sends the whole message again, " +
            "so the recipient may see some of it twice"
}
