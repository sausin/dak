package app.dak.telephony.carrier

/** How one composed message goes out. */
enum class SendMode {
    /** One SMS send (one provider row per recipient; the platform's concatenated SMS when long). */
    SMS,

    /** One MMS to every recipient (a group MMS when there are several). */
    MMS,

    /**
     * One MMS per recipient: the content needs MMS (media or very long text) but the carrier has group MMS off, so
     * each recipient gets their own copy in their 1:1 thread.
     */
    MMS_PER_RECIPIENT,
}

/** Why a message cannot be sent as composed. */
enum class SendBlock {
    /**
     * The message needs MMS (media, or an e-mail recipient without a carrier e-mail gateway) but the carrier has MMS
     * switched off.
     */
    MMS_DISABLED,

    /** More recipients than the carrier's `recipientLimit` for one MMS. */
    TOO_MANY_RECIPIENTS,

    /** MMS text over the carrier's `maxMessageTextSize`. */
    TEXT_TOO_LONG,
}

/**
 * [mode] to use, or [block] when the message cannot go out as composed (then [mode] is only a hint for the chip).
 * [emailGateway] is set when a text to one e-mail address goes as an SMS `"<address> <text>"` to the carrier's
 * e-mail gateway number (`emailGatewayNumber`) instead of as MMS.
 */
data class SendPlan(val mode: SendMode, val block: SendBlock? = null, val emailGateway: String? = null) {
    val isMms: Boolean get() = mode != SendMode.SMS
}

/**
 * Chooses SMS or MMS from the carrier's rules ([CarrierMessagingConfig]) instead of fixed numbers:
 * - media always needs MMS;
 * - a long text becomes MMS above the carrier's segment (`smsToMmsTextThreshold`) or length
 *   (`smsToMmsTextLengthThreshold`) threshold; carriers without a rule get [DEFAULT_SEGMENT_THRESHOLD] segments;
 * - several recipients share one group MMS only when the carrier has group MMS on. Otherwise plain texts go as SMS
 *   (one row per recipient, like a broadcast) and media goes as one MMS per recipient;
 * - an e-mail recipient needs MMS (the MMSC routes it), except that a plain text to a single e-mail address goes by
 *   SMS through the carrier's e-mail gateway number when it has one (as AOSP does);
 * - with MMS switched off by the carrier, text always goes as SMS and media (or e-mail without a gateway) cannot be
 *   sent.
 */
object SendModePolicy {
    /** Segments above which a text goes as MMS when the carrier has no threshold (Dak's historical default). */
    const val DEFAULT_SEGMENT_THRESHOLD: Int = 10

    fun plan(
        recipientCount: Int,
        segments: Int,
        textLength: Int,
        textBytes: Int,
        hasAttachments: Boolean,
        config: CarrierMessagingConfig,
        emailRecipients: Int = 0,
    ): SendPlan {
        val longText = isLongText(segments, textLength, config)
        val email = emailRecipients > 0
        val gateway = config.emailGatewayNumber?.takeIf { email && recipientCount == 1 && !hasAttachments && !longText }
        if (gateway != null) return SendPlan(SendMode.SMS, emailGateway = gateway)
        if (!config.mmsEnabled) {
            return if (hasAttachments || email) SendPlan(SendMode.MMS, SendBlock.MMS_DISABLED) else SendPlan(SendMode.SMS)
        }
        val group = recipientCount > 1
        val needsMms = hasAttachments || longText || email
        val mode = when {
            group && !config.groupMmsEnabled -> if (needsMms) SendMode.MMS_PER_RECIPIENT else SendMode.SMS
            group || needsMms -> SendMode.MMS
            else -> SendMode.SMS
        }
        val block = when {
            mode == SendMode.SMS -> null
            mode == SendMode.MMS && config.recipientLimit != null && recipientCount > config.recipientLimit -> SendBlock.TOO_MANY_RECIPIENTS
            config.maxTextBytes != null && textBytes > config.maxTextBytes -> SendBlock.TEXT_TOO_LONG
            else -> null
        }
        return SendPlan(mode, block)
    }

    private fun isLongText(segments: Int, textLength: Int, config: CarrierMessagingConfig): Boolean {
        val bySegments = segments > (config.smsToMmsSegmentThreshold ?: DEFAULT_SEGMENT_THRESHOLD)
        val byLength = config.smsToMmsLengthThreshold?.let { textLength > it } ?: false
        return bySegments || byLength
    }

    /** True for an e-mail recipient (the only addresses with `@`; phone numbers and sender ids never have one). */
    fun isEmailAddress(address: String): Boolean {
        val a = address.trim()
        val at = a.indexOf('@')
        return at > 0 && at < a.length - 1
    }

    /** SMS body for [text] to [email] through the carrier's e-mail gateway: `"<address> <text>"` (AOSP format). */
    fun emailGatewayBody(email: String, text: String): String = email.trim() + " " + text

    /** Subject cut to the carrier's `maxSubjectLength` (whole code points), or null when blank. */
    fun subject(subject: String?, config: CarrierMessagingConfig): String? {
        val s = subject?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.codePointCount(0, s.length) <= config.maxSubjectLength) return s
        return s.substring(0, s.offsetByCodePoints(0, config.maxSubjectLength))
    }
}
