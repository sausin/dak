package app.dak.telephony.mms

import app.dak.telephony.Failure
import app.dak.telephony.FailureReason

/**
 * Result codes delivered to `sendMultimediaMessage` / `downloadMultimediaMessage` PendingIntents
 * (`SmsManager.MMS_ERROR_*`; `Activity.RESULT_OK` = -1 is success). Literals keep this mapping JVM-testable and
 * avoid referencing constants newer than minSdk.
 */
internal object MmsResultCodes {
    const val RESULT_OK = -1
    const val UNSPECIFIED = 1
    const val INVALID_APN = 2
    const val UNABLE_CONNECT_MMS = 3
    const val HTTP_FAILURE = 4
    const val IO_ERROR = 5
    const val RETRY = 6
    const val CONFIGURATION_ERROR = 7
    const val NO_DATA_NETWORK = 8
    const val INVALID_SUBSCRIPTION_ID = 9
    const val INACTIVE_SUBSCRIPTION = 10
    const val DATA_DISABLED = 11
    const val MMS_DISABLED_BY_CARRIER = 12

    /** Failures that may succeed later without user action (network, MMSC or radio hiccups). */
    fun isRetryable(code: Int): Boolean = when (code) {
        UNSPECIFIED, UNABLE_CONNECT_MMS, HTTP_FAILURE, IO_ERROR, RETRY, NO_DATA_NETWORK -> true
        else -> false
    }

    /** What [code] means (plus the [httpStatus] when known), for the "tap to retry" bubble. */
    fun failureOf(code: Int, httpStatus: Int? = null): FailureReason {
        val failure = when (code) {
            RESULT_OK -> Failure.MMS_OK
            UNSPECIFIED -> Failure.MMS_FAILED
            INVALID_APN -> Failure.MMS_INVALID_APN
            UNABLE_CONNECT_MMS -> Failure.MMS_CANNOT_CONNECT
            HTTP_FAILURE -> Failure.MMS_HTTP_FAILURE
            IO_ERROR -> Failure.MMS_IO_ERROR
            RETRY -> Failure.MMS_RETRY_LATER
            CONFIGURATION_ERROR -> Failure.MMS_NOT_CONFIGURED
            NO_DATA_NETWORK -> Failure.MMS_NO_DATA
            INVALID_SUBSCRIPTION_ID -> Failure.MMS_SIM_UNAVAILABLE
            INACTIVE_SUBSCRIPTION -> Failure.MMS_SIM_INACTIVE
            DATA_DISABLED -> Failure.MMS_DATA_OFF
            MMS_DISABLED_BY_CARRIER -> Failure.MMS_DISABLED_BY_CARRIER
            else -> Failure.MMS_UNKNOWN_CODE
        }
        val args = if (failure == Failure.MMS_UNKNOWN_CODE) listOf<Any>(code) else emptyList()
        return FailureReason(failure, args, httpStatus?.takeIf { it > 0 })
    }

    /** English text of [failureOf] (logs, tests). [httpStatus] is appended when known. */
    fun describe(code: Int, httpStatus: Int? = null): String = failureOf(code, httpStatus).english()

    /** The stored form of [failureOf], resolved to the app language by [app.dak.telephony.FailureReasonText]. */
    fun reason(code: Int, httpStatus: Int? = null): String = failureOf(code, httpStatus).encode()
}
