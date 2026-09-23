package app.dak.telephony.mms

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

    /** Human-readable reason shown on the "tap to retry" bubble. [httpStatus] is appended when known. */
    fun describe(code: Int, httpStatus: Int? = null): String {
        val base = when (code) {
            RESULT_OK -> "OK"
            UNSPECIFIED -> "MMS failed"
            INVALID_APN -> "MMS settings (APN) are missing or invalid for this SIM"
            UNABLE_CONNECT_MMS -> "Could not connect to the carrier's MMS server"
            HTTP_FAILURE -> "The carrier's MMS server returned an error"
            IO_ERROR -> "Network error while transferring the MMS"
            RETRY -> "The carrier asked to retry later"
            CONFIGURATION_ERROR -> "MMS is not configured for this SIM"
            NO_DATA_NETWORK -> "No mobile data connection for MMS"
            INVALID_SUBSCRIPTION_ID -> "The SIM for this message is not available"
            INACTIVE_SUBSCRIPTION -> "The SIM for this message is inactive"
            DATA_DISABLED -> "Mobile data is turned off"
            MMS_DISABLED_BY_CARRIER -> "MMS is disabled by the carrier"
            else -> "MMS failed (code $code)"
        }
        return if (httpStatus != null && httpStatus > 0) "$base (HTTP $httpStatus)" else base
    }
}
