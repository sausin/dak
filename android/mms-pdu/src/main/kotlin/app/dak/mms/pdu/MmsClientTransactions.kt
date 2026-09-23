package app.dak.mms.pdu

/**
 * Which PDU the MMS client owes the MMSC at each step of a retrieval (OMA MMS-CTR 1.3 retrieval transactions and
 * the MMS-ENC 1.3 m-notifyresp-ind / m-acknowledge-ind definitions and version rules). The platform download API
 * (`SmsManager.downloadMultimediaMessage`) only does the HTTP GET; the answers below are sent by the app.
 *
 * - Immediate retrieval: after the message is stored, m-notifyresp-ind with X-Mms-Status = Retrieved.
 * - Deferred retrieval (auto-download off, roaming, too large, notification flood): m-notifyresp-ind with
 *   X-Mms-Status = Deferred when the fetch is postponed, then m-acknowledge-ind once the user's later fetch stored it
 *   (no second notifyresp: the notification transaction already ended with Deferred).
 * - A notification the client cannot understand (undecodable, or a major MMS version it does not implement):
 *   m-notifyresp-ind with X-Mms-Status = Unrecognised, and no download.
 * - A received message the sender asked a read report for (X-Mms-Read-Report = Yes), once it is read and only when
 *   the user opted in: m-read-rec-ind ([readReceipt], OMA MMS-CTR read-report transaction).
 *
 * All retrieval answers need the notification's X-Mms-Transaction-ID; without one there is nothing to answer (null).
 * [reportAllowed] fills X-Mms-Report-Allowed (null leaves it out, which the MMSC treats as its default, "Yes").
 */
object MmsClientTransactions {

    /** Major MMS versions this client implements (1.0 – 1.3 all share major version 1). */
    const val SUPPORTED_MAJOR_VERSION: Int = 1

    /** False for a PDU of a major version we do not implement (e.g. 2.0): answer it with [unrecognised]. */
    fun isSupportedVersion(mmsVersion: Int): Boolean = MmsVersion.major(mmsVersion) == SUPPORTED_MAJOR_VERSION

    /** m-notifyresp-ind (Deferred) for a notification whose download was postponed. */
    fun deferred(notification: NotificationInd, reportAllowed: Boolean? = null): NotifyRespInd? =
        notification.transactionId?.takeIf { it.isNotEmpty() }?.let {
            NotifyRespInd(transactionId = it, status = MmsStatus.DEFERRED, reportAllowed = reportAllowed)
        }

    /** m-notifyresp-ind (Unrecognised) for a notification we will not handle. */
    fun unrecognised(transactionId: String?, reportAllowed: Boolean? = null): NotifyRespInd? =
        transactionId?.takeIf { it.isNotEmpty() }?.let {
            NotifyRespInd(transactionId = it, status = MmsStatus.UNRECOGNISED, reportAllowed = reportAllowed)
        }

    /**
     * The answer after a retrieved message was stored: m-acknowledge-ind when the notification had been answered
     * Deferred earlier ([wasDeferred]), m-notifyresp-ind (Retrieved) otherwise.
     */
    fun afterRetrieval(transactionId: String?, wasDeferred: Boolean, reportAllowed: Boolean? = null): MmsPdu? {
        val tid = transactionId?.takeIf { it.isNotEmpty() } ?: return null
        return if (wasDeferred) {
            AcknowledgeInd(transactionId = tid, reportAllowed = reportAllowed)
        } else {
            NotifyRespInd(transactionId = tid, status = MmsStatus.RETRIEVED, reportAllowed = reportAllowed)
        }
    }

    /**
     * The answer to a WAP push that [MmsPduDecoder.decode] rejected: Unrecognised when it looks like a notification
     * (message type m-notification-ind, or a type we do not know at all) and carries a transaction id. Damaged
     * reports (delivery / read) and our own PDU types get no answer: a notifyresp for them would be meaningless.
     */
    fun forUndecodable(bytes: ByteArray, reportAllowed: Boolean? = null): NotifyRespInd? {
        val preamble = MmsPduDecoder.peekPreamble(bytes) ?: return null
        val type = preamble.messageType
        val answerable = type == null || type == MessageType.NOTIFICATION_IND || type !in KNOWN_TYPES
        return if (answerable) unrecognised(preamble.transactionId, reportAllowed) else null
    }

    /**
     * The m-read-rec-ind owed for a received message that has just been read, or null when none should be sent.
     * The caller has already checked that the user allows read receipts (off by default) and that the carrier
     * supports them; this decides from the message itself:
     * - the sender asked for one ([readReportRequested], X-Mms-Read-Report = Yes);
     * - there is a Message-ID to refer to (printable, at most [MmsLimits.MAX_TOKEN_CHARS]);
     * - the sender ([originator]) is a person: a phone number of at least [MIN_PERSONAL_NUMBER_DIGITS] digits or an
     *   e-mail address. Short codes and alphanumeric sender ids (businesses, bulk senders) never learn when a message
     *   was read, and neither does a hidden sender (Insert-address-token);
     * - the message is not an advertisement or auto-generated ([messageClass]).
     */
    fun readReceipt(
        messageId: String?,
        originator: String?,
        readReportRequested: Boolean,
        messageClass: String?,
        nowSeconds: Long,
        readStatus: Int = ReadStatus.READ,
    ): ReadRecInd? {
        if (!readReportRequested) return null
        val id = messageId?.trim()?.takeIf { it.isNotEmpty() && it.length <= MmsLimits.MAX_TOKEN_CHARS && it.all { c -> c.code in 0x21..0x7E } }
            ?: return null
        val to = originator?.let(MmsAddress::fromWire)?.takeIf { isPersonalAddress(it) } ?: return null
        val cls = messageClass?.trim()?.lowercase()
        if (cls == MessageClass.ADVERTISEMENT || cls == MessageClass.AUTO) return null
        return ReadRecInd(messageId = id, to = to, from = null, dateSeconds = nowSeconds, readStatus = readStatus)
    }

    /** Shortest phone number treated as a person rather than a short code. */
    const val MIN_PERSONAL_NUMBER_DIGITS: Int = 7

    /** True for a phone number of at least [MIN_PERSONAL_NUMBER_DIGITS] digits, or a plausible e-mail address. */
    fun isPersonalAddress(address: String): Boolean {
        val a = address.trim()
        if (a.isEmpty() || a.length > MmsLimits.MAX_ADDRESS_CHARS) return false
        if (a.equals(INSERT_ADDRESS_TOKEN, ignoreCase = true)) return false
        val at = a.indexOf('@')
        if (at >= 0) {
            return at > 0 && at == a.lastIndexOf('@') && a.indexOf('.', at) > at + 1 && !a.endsWith('.') &&
                a.none { it.isWhitespace() || it.isISOControl() }
        }
        val compact = a.filterNot { it == ' ' || it == '-' || it == '.' || it == '(' || it == ')' }
        return MmsAddress.isPhoneNumber(compact) && compact.count { it.isDigit() } >= MIN_PERSONAL_NUMBER_DIGITS &&
            compact.none { it == '*' || it == '#' }
    }

    /** The Telephony provider's placeholder for "our own number" / a hidden sender. */
    private const val INSERT_ADDRESS_TOKEN = "insert-address-token"

    /** The answer to a decoded notification of an unsupported major version, or null when it is supported. */
    fun forUnsupportedVersion(notification: NotificationInd, reportAllowed: Boolean? = null): NotifyRespInd? =
        if (isSupportedVersion(notification.mmsVersion)) null else unrecognised(notification.transactionId, reportAllowed)

    private val KNOWN_TYPES = setOf(
        MessageType.SEND_REQ, MessageType.SEND_CONF, MessageType.NOTIFICATION_IND, MessageType.NOTIFYRESP_IND,
        MessageType.RETRIEVE_CONF, MessageType.ACKNOWLEDGE_IND, MessageType.DELIVERY_IND, MessageType.READ_REC_IND,
        MessageType.READ_ORIG_IND,
    )
}
