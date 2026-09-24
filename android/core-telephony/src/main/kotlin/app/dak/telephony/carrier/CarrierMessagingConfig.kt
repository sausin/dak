package app.dak.telephony.carrier

/**
 * The carrier's MMS / SMS rules for one subscription, read from `CarrierConfigManager.getConfigForSubId` (the
 * `KEY_MMS_*` keys, whose string values are the same as the older `SmsManager.MMS_CONFIG_*` names used below).
 * Every field has a safe default that matches AOSP's `carrier_config` defaults, so a device or SIM without the key
 * behaves like stock Android.
 *
 * Pure Kotlin: [fromLookup] takes a key lookup so it is JVM-testable; [CarrierConfigRepository] feeds it a
 * `PersistableBundle`.
 */
data class CarrierMessagingConfig(
    /** `enabledMMS`: the carrier supports MMS at all. */
    val mmsEnabled: Boolean = true,
    /** `enableGroupMms`: several recipients may share one MMS (a group thread). When false, send one message each. */
    val groupMmsEnabled: Boolean = true,
    /** `maxMessageSize` in bytes (whole m-send-req). */
    val maxMessageSizeBytes: Int = DEFAULT_MAX_MESSAGE_SIZE,
    /** `recipientLimit`: most recipients one MMS may carry; null = no limit. */
    val recipientLimit: Int? = null,
    /** `smsToMmsTextThreshold`: SMS segments above which a text goes as MMS; null = carrier has no rule. */
    val smsToMmsSegmentThreshold: Int? = null,
    /** `smsToMmsTextLengthThreshold`: characters above which a text goes as MMS; null = no rule. */
    val smsToMmsLengthThreshold: Int? = null,
    /** `maxSubjectLength`: characters of an MMS subject. */
    val maxSubjectLength: Int = DEFAULT_MAX_SUBJECT_LENGTH,
    /** `maxMessageTextSize`: bytes of MMS text; null = no limit. */
    val maxTextBytes: Int? = null,
    /** `maxImageWidth` / `maxImageHeight` in pixels. */
    val maxImageWidth: Int = DEFAULT_MAX_IMAGE_WIDTH,
    val maxImageHeight: Int = DEFAULT_MAX_IMAGE_HEIGHT,
    /**
     * `enabledNotifyWapMMSC`: send m-notifyresp-ind / m-acknowledge-ind to the notification's Content-Location URL
     * instead of the MMSC URL. (It does not decide whether they are sent: they always are.)
     */
    val notifyWapMmsc: Boolean = false,
    /** `sendMultipartSmsAsSeparateMessages`: the carrier cannot reassemble concatenated SMS. */
    val sendMultipartSmsAsSeparateMessages: Boolean = false,
    /** `enableSMSDeliveryReports`: SMS delivery reports work on this carrier (AOSP default on). */
    val smsDeliveryReportsEnabled: Boolean = true,
    /** `enableMMSDeliveryReports`: the MMSC honours X-Mms-Delivery-Report (AOSP default off). */
    val mmsDeliveryReportsEnabled: Boolean = false,
    /** `enableMMSReadReports`: the MMSC relays read reports, both ways (AOSP default off). */
    val mmsReadReportsEnabled: Boolean = false,
    /**
     * `emailGatewayNumber`: an SMS short number that forwards `"<e-mail address> <text>"` to that address; null when
     * the carrier has none (then e-mail recipients need MMS).
     */
    val emailGatewayNumber: String? = null,
) {
    companion object {
        const val DEFAULT_MAX_MESSAGE_SIZE: Int = 300 * 1024
        const val DEFAULT_MAX_SUBJECT_LENGTH: Int = 40
        const val DEFAULT_MAX_IMAGE_WIDTH: Int = 640
        const val DEFAULT_MAX_IMAGE_HEIGHT: Int = 480

        /** Below this a carrier's size limit is treated as a misconfiguration (AOSP never ships less). */
        const val MIN_SANE_MESSAGE_SIZE: Int = 30 * 1024

        /** Used when nothing can be read (no SIM, no permission, a vendor exception). */
        val DEFAULTS = CarrierMessagingConfig()

        // Key names (identical strings in CarrierConfigManager.KEY_MMS_* and SmsManager.MMS_CONFIG_*).
        const val KEY_MMS_ENABLED = "enabledMMS"
        const val KEY_GROUP_MMS_ENABLED = "enableGroupMms"
        const val KEY_MAX_MESSAGE_SIZE = "maxMessageSize"
        const val KEY_RECIPIENT_LIMIT = "recipientLimit"
        const val KEY_SMS_TO_MMS_TEXT_THRESHOLD = "smsToMmsTextThreshold"
        const val KEY_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD = "smsToMmsTextLengthThreshold"
        const val KEY_MAX_SUBJECT_LENGTH = "maxSubjectLength"
        const val KEY_MAX_MESSAGE_TEXT_SIZE = "maxMessageTextSize"
        const val KEY_MAX_IMAGE_WIDTH = "maxImageWidth"
        const val KEY_MAX_IMAGE_HEIGHT = "maxImageHeight"
        const val KEY_NOTIFY_WAP_MMSC_ENABLED = "enabledNotifyWapMMSC"
        const val KEY_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES = "sendMultipartSmsAsSeparateMessages"
        const val KEY_SMS_DELIVERY_REPORT_ENABLED = "enableSMSDeliveryReports"
        const val KEY_MMS_DELIVERY_REPORT_ENABLED = "enableMMSDeliveryReports"
        const val KEY_MMS_READ_REPORT_ENABLED = "enableMMSReadReports"
        const val KEY_EMAIL_GATEWAY_NUMBER = "emailGatewayNumber"

        /** Longest e-mail gateway number accepted (they are short codes; anything longer is a misconfiguration). */
        const val MAX_GATEWAY_NUMBER_CHARS: Int = 20

        /**
         * Builds a config from a key lookup (`bundle.get(key)`). Missing, mistyped and nonsensical values fall back
         * to the defaults; "no limit" values (≤ 0, AOSP uses -1) become null.
         */
        fun fromLookup(lookup: (String) -> Any?): CarrierMessagingConfig {
            fun raw(key: String): Any? = runCatching { lookup(key) }.getOrNull()
            fun bool(key: String, default: Boolean): Boolean = raw(key) as? Boolean ?: default
            fun int(key: String): Int? = (raw(key) as? Number)?.toInt()
            fun limit(key: String): Int? = int(key)?.takeIf { it > 0 }
            val d = DEFAULTS
            return CarrierMessagingConfig(
                mmsEnabled = bool(KEY_MMS_ENABLED, d.mmsEnabled),
                groupMmsEnabled = bool(KEY_GROUP_MMS_ENABLED, d.groupMmsEnabled),
                maxMessageSizeBytes = int(KEY_MAX_MESSAGE_SIZE)?.takeIf { it >= MIN_SANE_MESSAGE_SIZE } ?: d.maxMessageSizeBytes,
                recipientLimit = limit(KEY_RECIPIENT_LIMIT),
                smsToMmsSegmentThreshold = limit(KEY_SMS_TO_MMS_TEXT_THRESHOLD),
                smsToMmsLengthThreshold = limit(KEY_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD),
                maxSubjectLength = limit(KEY_MAX_SUBJECT_LENGTH) ?: d.maxSubjectLength,
                maxTextBytes = limit(KEY_MAX_MESSAGE_TEXT_SIZE),
                maxImageWidth = limit(KEY_MAX_IMAGE_WIDTH) ?: d.maxImageWidth,
                maxImageHeight = limit(KEY_MAX_IMAGE_HEIGHT) ?: d.maxImageHeight,
                notifyWapMmsc = bool(KEY_NOTIFY_WAP_MMSC_ENABLED, d.notifyWapMmsc),
                sendMultipartSmsAsSeparateMessages = bool(KEY_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES, d.sendMultipartSmsAsSeparateMessages),
                smsDeliveryReportsEnabled = bool(KEY_SMS_DELIVERY_REPORT_ENABLED, d.smsDeliveryReportsEnabled),
                mmsDeliveryReportsEnabled = bool(KEY_MMS_DELIVERY_REPORT_ENABLED, d.mmsDeliveryReportsEnabled),
                mmsReadReportsEnabled = bool(KEY_MMS_READ_REPORT_ENABLED, d.mmsReadReportsEnabled),
                emailGatewayNumber = gatewayNumber(raw(KEY_EMAIL_GATEWAY_NUMBER) as? String),
            )
        }

        /** A dialable gateway number (`+`, digits), or null for blank / malformed values. */
        internal fun gatewayNumber(raw: String?): String? {
            val s = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_GATEWAY_NUMBER_CHARS } ?: return null
            val body = s.removePrefix("+")
            return s.takeIf { body.isNotEmpty() && body.all { it in '0'..'9' } }
        }
    }
}
