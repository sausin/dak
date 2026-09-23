package app.dak.mms.pdu

/**
 * A point in time as MMS encodes expiry / delivery-time headers: either absolute (seconds since the epoch) or
 * relative to when the PDU was received.
 */
sealed interface MmsTime {
    /** Resolves to seconds since the epoch, given the receive time. */
    fun toEpochSeconds(nowEpochSeconds: Long): Long

    data class Absolute(val epochSeconds: Long) : MmsTime {
        override fun toEpochSeconds(nowEpochSeconds: Long): Long = epochSeconds
    }

    data class Relative(val seconds: Long) : MmsTime {
        override fun toEpochSeconds(nowEpochSeconds: Long): Long = nowEpochSeconds + seconds
    }
}

/**
 * A decoded or to-be-encoded MMS PDU. Addresses are exposed without the `/TYPE=...` suffix (see [MmsAddress]);
 * the encoder adds `/TYPE=PLMN` to phone numbers.
 */
sealed interface MmsPdu {
    /** `X-Mms-Message-Type` octet, see [MessageType]. */
    val messageType: Int

    /** `X-Mms-MMS-Version`, see [MmsVersion]. */
    val mmsVersion: Int
}

/** m-notification-ind (WAP push from the MMSC announcing a message to fetch). */
data class NotificationInd(
    val contentLocation: String,
    val transactionId: String? = null,
    val from: String? = null,
    val subject: String? = null,
    val messageClass: String? = MessageClass.PERSONAL,
    /** Declared size in octets; 0 when absent. */
    val messageSize: Long = 0,
    val expiry: MmsTime? = null,
    val priority: Int? = null,
    val deliveryReport: Boolean? = null,
    override val mmsVersion: Int = MmsVersion.DEFAULT,
) : MmsPdu {
    override val messageType: Int get() = MessageType.NOTIFICATION_IND
}

/** m-retrieve-conf (the downloaded message). */
data class RetrieveConf(
    val contentType: ContentType,
    val parts: List<PduPart>,
    val transactionId: String? = null,
    val messageId: String? = null,
    /** Seconds since the epoch, when the MMSC stamped it. */
    val dateSeconds: Long? = null,
    val from: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String? = null,
    val messageClass: String? = null,
    val priority: Int? = null,
    val deliveryReport: Boolean? = null,
    val readReport: Boolean? = null,
    val retrieveStatus: Int? = null,
    val retrieveText: String? = null,
    override val mmsVersion: Int = MmsVersion.DEFAULT,
) : MmsPdu {
    override val messageType: Int get() = MessageType.RETRIEVE_CONF

    /** True unless the MMSC reported a retrieve error in `X-Mms-Retrieve-Status`. */
    val isRetrieveOk: Boolean get() = retrieveStatus == null || retrieveStatus == RetrieveStatus.OK
}

/** m-send-req (a message we send). */
data class SendReq(
    val transactionId: String,
    val to: List<String>,
    val contentType: ContentType,
    val parts: List<PduPart>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    /** Null encodes the Insert-address-token, letting the MMSC fill in our number (the normal case). */
    val from: String? = null,
    val subject: String? = null,
    val dateSeconds: Long? = null,
    val messageClass: String? = MessageClass.PERSONAL,
    val expiry: MmsTime? = null,
    val priority: Int? = null,
    val deliveryReport: Boolean? = null,
    val readReport: Boolean? = null,
    override val mmsVersion: Int = MmsVersion.DEFAULT,
) : MmsPdu {
    override val messageType: Int get() = MessageType.SEND_REQ
}

/** m-send-conf (the MMSC's answer to m-send-req, delivered as the send result data). */
data class SendConf(
    val responseStatus: Int,
    val transactionId: String? = null,
    val messageId: String? = null,
    val responseText: String? = null,
    override val mmsVersion: Int = MmsVersion.DEFAULT,
) : MmsPdu {
    override val messageType: Int get() = MessageType.SEND_CONF

    val isOk: Boolean get() = responseStatus == ResponseStatus.OK
}

/** m-notifyresp-ind (our response to a notification after immediate retrieval or deferral). */
data class NotifyRespInd(
    val transactionId: String,
    val status: Int = MmsStatus.RETRIEVED,
    val reportAllowed: Boolean? = null,
    override val mmsVersion: Int = MmsVersion.DEFAULT,
) : MmsPdu {
    override val messageType: Int get() = MessageType.NOTIFYRESP_IND
}

/** m-acknowledge-ind (acknowledges a deferred retrieval). */
data class AcknowledgeInd(
    val transactionId: String,
    val reportAllowed: Boolean? = null,
    override val mmsVersion: Int = MmsVersion.DEFAULT,
) : MmsPdu {
    override val messageType: Int get() = MessageType.ACKNOWLEDGE_IND
}

/** m-delivery-ind (delivery report for a message we sent). */
data class DeliveryInd(
    val messageId: String,
    val status: Int,
    val to: List<String> = emptyList(),
    val dateSeconds: Long? = null,
    override val mmsVersion: Int = MmsVersion.DEFAULT,
) : MmsPdu {
    override val messageType: Int get() = MessageType.DELIVERY_IND
}

/** m-read-orig-ind (read report for a message we sent). Decoded tolerantly: every field may be missing. */
data class ReadOrigInd(
    val messageId: String? = null,
    val from: String? = null,
    val to: List<String> = emptyList(),
    val dateSeconds: Long? = null,
    val readStatus: Int? = null,
    override val mmsVersion: Int = MmsVersion.DEFAULT,
) : MmsPdu {
    override val messageType: Int get() = MessageType.READ_ORIG_IND
}

/**
 * m-read-rec-ind (our read report for a received message whose sender asked for one with X-Mms-Read-Report = Yes;
 * OMA MMS-ENC 1.3 §6.7.2). The MMSC turns it into an m-read-orig-ind for the original sender. [to] is the original
 * sender; [from] null encodes the Insert-address-token (the MMSC fills in our number). There is no transaction id:
 * the PDU is one-way.
 */
data class ReadRecInd(
    val messageId: String,
    val to: String,
    val from: String? = null,
    val dateSeconds: Long? = null,
    val readStatus: Int = ReadStatus.READ,
    override val mmsVersion: Int = MmsVersion.DEFAULT,
) : MmsPdu {
    override val messageType: Int get() = MessageType.READ_REC_IND
}
