package app.dak.telephony.sms

/** What to do with one assembled incoming SMS. */
internal enum class IncomingHandling {
    /** TP-PID "Short Message Type 0" (0x40): acknowledge, never show or store. */
    DROP_TYPE_ZERO,

    /** Message-waiting indication marked "discard" (voicemail lamp control, no user content). */
    DROP_MWI,

    /** Message class 0 ("flash"): show at once, store only if the user asks. */
    FLASH,

    /** TP-PID "Replace Short Message Type 1–7" (0x41–0x47): overwrite the earlier one from the same sender. */
    REPLACE,

    /** Everything else: a normal inbox row. */
    STORE,
}

/**
 * 3GPP TS 23.040 / 23.038 rules for incoming SMS that the platform leaves to the default SMS app. Pure, for tests.
 *
 * - **Type 0** (TP-PID 0x40, TS 23.040 §9.2.3.9): the ME acknowledges it and discards it. AOSP's
 *   `GsmInboundSmsHandler` already drops these before SMS_DELIVER; the check here is a defensive second line for OEM
 *   builds that do not.
 * - **Replace Short Message Type n** (TP-PID 0x41–0x47): replaces an earlier message with the same TP-PID from the
 *   same originating address (TS 23.040 §9.2.3.9). If there is none, it is stored as a new message.
 * - **Class 0** (TP-DCS message class 0, TS 23.038 §4): displayed immediately and not necessarily stored. Dak follows
 *   the stock Android behaviour (AOSP Messaging's ClassZeroActivity): show it, and write it to the inbox only when
 *   the user taps Save. Class 0 wins over the replace types (it is never stored unasked).
 *
 * TP-PID has no meaning for 3GPP2 (CDMA) messages, so PID rules apply to the `3gpp` format only.
 */
internal object IncomingSmsPolicy {
    const val PID_TYPE_ZERO: Int = 0x40
    private val REPLACE_PIDS = 0x41..0x47
    private const val FORMAT_3GPP2 = "3gpp2"

    fun isReplacePid(pid: Int): Boolean = pid in REPLACE_PIDS

    /**
     * @param format the SMS_DELIVER `format` extra (`3gpp` / `3gpp2`, null on old platforms = 3gpp)
     * @param pid TP-PID of the first part
     * @param classZero TP-DCS message class of the first part is class 0
     * @param allMwiDontStore every part is a message-waiting indication marked "discard"
     */
    fun handling(format: String?, pid: Int, classZero: Boolean, allMwiDontStore: Boolean): IncomingHandling {
        val gsm = format != FORMAT_3GPP2
        return when {
            gsm && pid == PID_TYPE_ZERO -> IncomingHandling.DROP_TYPE_ZERO
            allMwiDontStore -> IncomingHandling.DROP_MWI
            classZero -> IncomingHandling.FLASH
            gsm && isReplacePid(pid) -> IncomingHandling.REPLACE
            else -> IncomingHandling.STORE
        }
    }
}
