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
 *
 * All answers need the notification's X-Mms-Transaction-ID; without one there is nothing to answer (null).
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
    fun forUndecodable(bytes: ByteArray): NotifyRespInd? {
        val preamble = MmsPduDecoder.peekPreamble(bytes) ?: return null
        val type = preamble.messageType
        val answerable = type == null || type == MessageType.NOTIFICATION_IND || type !in KNOWN_TYPES
        return if (answerable) unrecognised(preamble.transactionId) else null
    }

    /** The answer to a decoded notification of an unsupported major version, or null when it is supported. */
    fun forUnsupportedVersion(notification: NotificationInd): NotifyRespInd? =
        if (isSupportedVersion(notification.mmsVersion)) null else unrecognised(notification.transactionId)

    private val KNOWN_TYPES = setOf(
        MessageType.SEND_REQ, MessageType.SEND_CONF, MessageType.NOTIFICATION_IND, MessageType.NOTIFYRESP_IND,
        MessageType.RETRIEVE_CONF, MessageType.ACKNOWLEDGE_IND, MessageType.DELIVERY_IND, MessageType.READ_REC_IND,
        MessageType.READ_ORIG_IND,
    )
}
