package app.dak.telephony

import java.util.Locale

/**
 * Why a send or an MMS download failed, as a language-neutral code the UI turns into text in the app language.
 *
 * Reasons travel as plain strings ([SendResult.Failed.reason], [MmsDownloadState.Failed.reason], the send-failure
 * store, a scheduled send's `failureReason` column), so the contracts did not change: a reason is now
 * `dak-fail:<FAILURE>` with `|`-separated arguments (see [encode]). [FailureReasonText.resolve] shows it in the app
 * language; [english] gives the English text for logs and tests. Anything without the prefix is shown as is: reasons
 * stored by older versions (already English sentences), exception messages and carrier-supplied text.
 *
 * Adding a reason: add an entry with its English text (a `String.format` pattern when it takes arguments), a
 * `dak_telephony_fail_<name in lower case>` string with the same text in res/values/strings_failures.xml, and its
 * line in [FailureReasonText]; FailureReasonsTest checks all three agree (new strings may also live in
 * res/values/strings_l10n_core.xml).
 */
enum class Failure(val english: String) {
    // Before sending
    MESSAGE_EMPTY("Message is empty"),
    NO_RECIPIENT("No recipient"),
    NOT_DEFAULT_APP("Dak is not the default SMS app"),
    WAITING_NOT_DEFAULT_APP("Waiting: Dak is not the default SMS app"),
    SAVE_FAILED_NOT_DEFAULT("Could not save the message; is Dak the default SMS app?"),
    CONVERSATION_FAILED_NOT_DEFAULT("Could not open the conversation; is Dak the default SMS app?"),
    EMERGENCY_REFUSED("The phone refused to send the emergency text"),
    MESSAGE_NOT_FOUND("Message not found"),
    SEND_INTERRUPTED("Sending was interrupted; tap to retry"),
    MMS_TOO_LARGE_FOR_CARRIER("Too large for this carrier: %1\$d KB (limit %2\$d KB)"),

    // SMS result codes (SmsResultCodes)
    SMS_SENT("Sent"),
    SMS_FAILED("Sending failed"),
    SMS_RADIO_OFF("Mobile radio is off (airplane mode or the other SIM is busy)"),
    SMS_NOT_ENCODED("The message could not be encoded"),
    SMS_NO_SERVICE("No mobile service"),
    SMS_LIMIT_EXCEEDED("Too many messages sent; waiting before retrying"),
    SMS_FDN_BLOCKED("Blocked by Fixed Dialing Numbers"),
    SMS_SHORT_CODE_NOT_ALLOWED("Sending to this short code is not allowed"),
    SMS_RADIO_NOT_AVAILABLE("Mobile radio is not available"),
    SMS_NETWORK_REJECT("The network rejected the message"),
    SMS_BAD_FORMAT("The message could not be sent in this format"),
    SMS_INVALID_SMSC("The SIM's message centre number is invalid"),
    SMS_NOT_ALLOWED("Sending is not allowed right now"),
    SMS_CANCELLED("Sending was cancelled"),
    SMS_NOT_SUPPORTED("Sending is not supported on this SIM"),
    SMS_TEMPORARY_ERROR("Temporary phone error"),
    SMS_NETWORK_ERROR("Network error"),
    SMS_UNKNOWN_CODE("Sending failed (code %1\$d)"),
    SMS_NOT_DELIVERED("Not delivered"),
    SMS_PARTIAL(
        "Only %1\$d of %2\$d parts were sent. Retrying sends the whole message again, " +
            "so the recipient may see some of it twice",
    ),

    // MMS result codes (MmsResultCodes); an HTTP status is appended by [FailureReasonText] / [english]
    MMS_OK("OK"),
    MMS_FAILED("MMS failed"),
    MMS_INVALID_APN("MMS settings (APN) are missing or invalid for this SIM"),
    MMS_CANNOT_CONNECT("Could not connect to the carrier's MMS server"),
    MMS_HTTP_FAILURE("The carrier's MMS server returned an error"),
    MMS_IO_ERROR("Network error while transferring the MMS"),
    MMS_RETRY_LATER("The carrier asked to retry later"),
    MMS_NOT_CONFIGURED("MMS is not configured for this SIM"),
    MMS_NO_DATA("No mobile data connection for MMS"),
    MMS_SIM_UNAVAILABLE("The SIM for this message is not available"),
    MMS_SIM_INACTIVE("The SIM for this message is inactive"),
    MMS_DATA_OFF("Mobile data is turned off"),
    MMS_DISABLED_BY_CARRIER("MMS is disabled by the carrier"),
    MMS_UNKNOWN_CODE("MMS failed (code %1\$d)"),
    MMS_WITH_HTTP_STATUS("%1\$s (HTTP %2\$d)"),

    // MMS download
    MMS_TAP_TO_DOWNLOAD("Tap to download"),
    MMS_TAP_TO_DOWNLOAD_LARGE("Very large message: tap to download"),
    MMS_TAP_TO_DOWNLOAD_ROAMING("Roaming: tap to download"),
    MMS_TAP_TO_DOWNLOAD_MANY("Many messages at once: tap to download"),
    MMS_NOT_DOWNLOADED("Not downloaded yet"),
    MMS_EXPIRED("Expired on the carrier's server"),
    MMS_INVALID_LINK("Invalid download link"),
    MMS_TIMED_OUT("Timed out waiting for the carrier"),
    MMS_SAVE_FAILED("Could not save the message"),
    MMS_TOO_LARGE("The message is too large"),
    MMS_EMPTY_RESPONSE("The carrier returned an empty message"),
    // The decoder's (English) detail goes to the log only; reasons stored with it by older versions still decode.
    MMS_UNREADABLE("Unreadable MMS"),
    MMS_UNEXPECTED_RESPONSE("Unexpected response from the carrier"),
    MMS_CARRIER_COULD_NOT_DELIVER("The carrier could not deliver this message"),

    // MMSC X-Mms-Response-Status of a rejected send (mms/MmscStatusReasons), English as ResponseStatus.describe has it
    MMSC_SERVICE_DENIED("Service denied"),
    MMSC_FORMAT_CORRUPT("Message format corrupt"),
    MMSC_ADDRESS_UNRESOLVED("Recipient address not resolved"),
    MMSC_MESSAGE_NOT_FOUND("Message not found"),
    MMSC_NETWORK_PROBLEM("Network problem"),
    MMSC_CONTENT_NOT_ACCEPTED("Content not accepted"),
    MMSC_UNSUPPORTED_MESSAGE("Unsupported message"),
    MMSC_REPLY_CHARGING_LIMITATIONS("Reply charging limitations not met"),
    MMSC_REPLY_CHARGING_REJECTED("Reply charging request not accepted"),
    MMSC_REPLY_CHARGING_FORWARDING_DENIED("Reply charging forwarding denied"),
    MMSC_REPLY_CHARGING_NOT_SUPPORTED("Reply charging not supported"),
    MMSC_ADDRESS_HIDING_NOT_SUPPORTED("Address hiding not supported"),
    MMSC_LACK_OF_PREPAID_CREDIT("Lack of prepaid credit"),
    MMSC_TEMPORARY_FAILURE("Temporary MMSC failure (0x%1\$s)"),
    MMSC_ERROR("MMSC error (0x%1\$s)"),

    // Birthday wishes not sent (app BirthdaySendGate); the wish is closed, or a prompt is posted instead
    BIRTHDAY_ALREADY_WISHED("Already wished this year"),
    BIRTHDAY_WISHES_OFF("Birthday wishes turned off"),
    BIRTHDAY_ASKED("Asked the user first"),
    BIRTHDAY_NO_APP_LOCK("No app lock: asked the user"),
    BIRTHDAY_DAILY_LIMIT("Daily unattended limit: asked the user"),

    // Scheduled sends not sent (app ScheduledSendExecutor); older versions stored the English text of each
    SCHEDULED_PREMIUM_REFUSED("Premium-rate number not approved"),
    SCHEDULED_NO_APP_LOCK("No app lock was set up"),
    SCHEDULED_EMERGENCY_CANCELLED("Scheduled texts to emergency numbers are not allowed"),
    ;

    /**
     * Name of the string resource with this reason's text (core-telephony res/values/strings_failures.xml, or
     * strings_l10n_core.xml for the MMSC statuses).
     */
    val resourceName: String get() = "dak_telephony_fail_${name.lowercase()}"
}

/** A decoded reason: what failed, its arguments, and the MMS HTTP status when one was reported. */
data class FailureReason(val failure: Failure, val args: List<Any> = emptyList(), val httpStatus: Int? = null) {
    /** The reason in English, exactly as Dak showed it before reasons were localized. */
    fun english(): String {
        val base = formatEnglish(failure.english, args)
        return if (httpStatus != null && httpStatus > 0) formatEnglish(Failure.MMS_WITH_HTTP_STATUS.english, listOf(base, httpStatus)) else base
    }

    /** The persisted form (see [FailureReasons]). */
    fun encode(): String = FailureReasons.encode(this)
}

object FailureReasons {
    const val PREFIX: String = "dak-fail:"
    private const val HTTP = "http="

    fun encode(failure: Failure, vararg args: Any): String = encode(FailureReason(failure, args.toList()))

    fun encode(reason: FailureReason): String = buildString {
        append(PREFIX).append(reason.failure.name)
        // Arguments never contain '|' except possibly the last free-text one, which decode keeps whole.
        reason.args.forEach { append('|').append(it.toString()) }
        reason.httpStatus?.takeIf { it > 0 }?.let { append('|').append(HTTP).append(it) }
    }

    /** The reason encoded in [raw], or null when [raw] is legacy / free text (show it as is). */
    fun decode(raw: String?): FailureReason? {
        if (raw == null || !raw.startsWith(PREFIX)) return null
        val parts = raw.substring(PREFIX.length).split('|')
        val failure = Failure.entries.firstOrNull { it.name == parts[0] } ?: return null
        var rest = parts.drop(1)
        val http = rest.lastOrNull()?.takeIf { it.startsWith(HTTP) }?.removePrefix(HTTP)?.toIntOrNull()
        if (http != null) rest = rest.dropLast(1)
        val expected = argCount(failure.english)
        // A free-text last argument may itself contain '|': join the surplus back.
        if (rest.size > expected && expected > 0) rest = rest.take(expected - 1) + rest.drop(expected - 1).joinToString("|")
        // A reason that no longer takes arguments (MMS_UNREADABLE kept a decoder detail before): drop the old ones.
        if (expected == 0) rest = emptyList()
        if (rest.size != expected) return null
        return FailureReason(failure, rest.map { it.toIntOrNull() ?: it }, http)
    }

    /** English text of [raw]: the decoded reason's, or [raw] itself when it is not encoded. */
    fun english(raw: String): String = decode(raw)?.english() ?: raw

    private fun argCount(pattern: String): Int = Regex("%(\\d+)\\$").findAll(pattern).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
}

/** Locale.ROOT: English text with ASCII digits whatever the phone's default locale. */
private fun formatEnglish(pattern: String, args: List<Any>): String =
    if (args.isEmpty()) pattern else String.format(Locale.ROOT, pattern, *args.toTypedArray())
