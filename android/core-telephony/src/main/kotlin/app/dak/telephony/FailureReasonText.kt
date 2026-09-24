package app.dak.telephony

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * Turns a stored failure reason ([FailureReasons]) into text in the app language. Reasons that are not encoded
 * (written by older versions, exception messages, carrier-supplied text) are returned unchanged.
 */
object FailureReasonText {

    /** [raw] in the language of [context] (use the activity or composition context so the app language applies). */
    fun resolve(context: Context, raw: String?): String? = resolve(context.resources, raw)

    fun resolve(resources: Resources, raw: String?): String? {
        if (raw == null) return null
        val reason = FailureReasons.decode(raw) ?: return raw
        val res = stringRes(reason.failure)
        val base = if (reason.args.isEmpty()) resources.getString(res) else resources.getString(res, *reason.args.toTypedArray())
        val http = reason.httpStatus ?: return base
        return resources.getString(stringRes(Failure.MMS_WITH_HTTP_STATUS), base, http)
    }

    @StringRes
    fun stringRes(failure: Failure): Int = when (failure) {
        Failure.MESSAGE_EMPTY -> R.string.dak_telephony_fail_message_empty
        Failure.NO_RECIPIENT -> R.string.dak_telephony_fail_no_recipient
        Failure.NOT_DEFAULT_APP -> R.string.dak_telephony_fail_not_default_app
        Failure.WAITING_NOT_DEFAULT_APP -> R.string.dak_telephony_fail_waiting_not_default_app
        Failure.SAVE_FAILED_NOT_DEFAULT -> R.string.dak_telephony_fail_save_failed_not_default
        Failure.CONVERSATION_FAILED_NOT_DEFAULT -> R.string.dak_telephony_fail_conversation_failed_not_default
        Failure.EMERGENCY_REFUSED -> R.string.dak_telephony_fail_emergency_refused
        Failure.MESSAGE_NOT_FOUND -> R.string.dak_telephony_fail_message_not_found
        Failure.SEND_INTERRUPTED -> R.string.dak_telephony_fail_send_interrupted
        Failure.MMS_TOO_LARGE_FOR_CARRIER -> R.string.dak_telephony_fail_mms_too_large_for_carrier
        Failure.SMS_SENT -> R.string.dak_telephony_fail_sms_sent
        Failure.SMS_FAILED -> R.string.dak_telephony_fail_sms_failed
        Failure.SMS_RADIO_OFF -> R.string.dak_telephony_fail_sms_radio_off
        Failure.SMS_NOT_ENCODED -> R.string.dak_telephony_fail_sms_not_encoded
        Failure.SMS_NO_SERVICE -> R.string.dak_telephony_fail_sms_no_service
        Failure.SMS_LIMIT_EXCEEDED -> R.string.dak_telephony_fail_sms_limit_exceeded
        Failure.SMS_FDN_BLOCKED -> R.string.dak_telephony_fail_sms_fdn_blocked
        Failure.SMS_SHORT_CODE_NOT_ALLOWED -> R.string.dak_telephony_fail_sms_short_code_not_allowed
        Failure.SMS_RADIO_NOT_AVAILABLE -> R.string.dak_telephony_fail_sms_radio_not_available
        Failure.SMS_NETWORK_REJECT -> R.string.dak_telephony_fail_sms_network_reject
        Failure.SMS_BAD_FORMAT -> R.string.dak_telephony_fail_sms_bad_format
        Failure.SMS_INVALID_SMSC -> R.string.dak_telephony_fail_sms_invalid_smsc
        Failure.SMS_NOT_ALLOWED -> R.string.dak_telephony_fail_sms_not_allowed
        Failure.SMS_CANCELLED -> R.string.dak_telephony_fail_sms_cancelled
        Failure.SMS_NOT_SUPPORTED -> R.string.dak_telephony_fail_sms_not_supported
        Failure.SMS_TEMPORARY_ERROR -> R.string.dak_telephony_fail_sms_temporary_error
        Failure.SMS_NETWORK_ERROR -> R.string.dak_telephony_fail_sms_network_error
        Failure.SMS_UNKNOWN_CODE -> R.string.dak_telephony_fail_sms_unknown_code
        Failure.SMS_NOT_DELIVERED -> R.string.dak_telephony_fail_sms_not_delivered
        Failure.SMS_PARTIAL -> R.string.dak_telephony_fail_sms_partial
        Failure.MMS_OK -> R.string.dak_telephony_fail_mms_ok
        Failure.MMS_FAILED -> R.string.dak_telephony_fail_mms_failed
        Failure.MMS_INVALID_APN -> R.string.dak_telephony_fail_mms_invalid_apn
        Failure.MMS_CANNOT_CONNECT -> R.string.dak_telephony_fail_mms_cannot_connect
        Failure.MMS_HTTP_FAILURE -> R.string.dak_telephony_fail_mms_http_failure
        Failure.MMS_IO_ERROR -> R.string.dak_telephony_fail_mms_io_error
        Failure.MMS_RETRY_LATER -> R.string.dak_telephony_fail_mms_retry_later
        Failure.MMS_NOT_CONFIGURED -> R.string.dak_telephony_fail_mms_not_configured
        Failure.MMS_NO_DATA -> R.string.dak_telephony_fail_mms_no_data
        Failure.MMS_SIM_UNAVAILABLE -> R.string.dak_telephony_fail_mms_sim_unavailable
        Failure.MMS_SIM_INACTIVE -> R.string.dak_telephony_fail_mms_sim_inactive
        Failure.MMS_DATA_OFF -> R.string.dak_telephony_fail_mms_data_off
        Failure.MMS_DISABLED_BY_CARRIER -> R.string.dak_telephony_fail_mms_disabled_by_carrier
        Failure.MMS_UNKNOWN_CODE -> R.string.dak_telephony_fail_mms_unknown_code
        Failure.MMS_WITH_HTTP_STATUS -> R.string.dak_telephony_fail_mms_with_http_status
        Failure.MMS_TAP_TO_DOWNLOAD -> R.string.dak_telephony_fail_mms_tap_to_download
        Failure.MMS_TAP_TO_DOWNLOAD_LARGE -> R.string.dak_telephony_fail_mms_tap_to_download_large
        Failure.MMS_TAP_TO_DOWNLOAD_ROAMING -> R.string.dak_telephony_fail_mms_tap_to_download_roaming
        Failure.MMS_TAP_TO_DOWNLOAD_MANY -> R.string.dak_telephony_fail_mms_tap_to_download_many
        Failure.MMS_NOT_DOWNLOADED -> R.string.dak_telephony_fail_mms_not_downloaded
        Failure.MMS_EXPIRED -> R.string.dak_telephony_fail_mms_expired
        Failure.MMS_INVALID_LINK -> R.string.dak_telephony_fail_mms_invalid_link
        Failure.MMS_TIMED_OUT -> R.string.dak_telephony_fail_mms_timed_out
        Failure.MMS_SAVE_FAILED -> R.string.dak_telephony_fail_mms_save_failed
        Failure.MMS_TOO_LARGE -> R.string.dak_telephony_fail_mms_too_large
        Failure.MMS_EMPTY_RESPONSE -> R.string.dak_telephony_fail_mms_empty_response
        Failure.MMS_UNREADABLE -> R.string.dak_telephony_fail_mms_unreadable
        Failure.MMS_UNEXPECTED_RESPONSE -> R.string.dak_telephony_fail_mms_unexpected_response
        Failure.MMS_CARRIER_COULD_NOT_DELIVER -> R.string.dak_telephony_fail_mms_carrier_could_not_deliver
    }
}
