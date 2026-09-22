package app.dak.telephony.provider

import app.dak.core.model.MessageBox

/*
 * Telephony provider column names and values, spelled out as literals. They are identical to the
 * `android.provider.Telephony.*` constants (which exist on every supported API level) but keeping them here lets
 * the pure mapping code stay JVM-testable, and makes every raw column we touch greppable in one place.
 */

/** `Telephony.Sms` / `Telephony.TextBasedSmsColumns`. */
internal object SmsColumns {
    const val ID = "_id"
    const val THREAD_ID = "thread_id"
    const val ADDRESS = "address"
    const val BODY = "body"
    const val DATE = "date"
    const val DATE_SENT = "date_sent"
    const val TYPE = "type"
    const val READ = "read"
    const val SEEN = "seen"
    const val STATUS = "status"
    const val SUBSCRIPTION_ID = "sub_id"
    const val PROTOCOL = "protocol"
    const val SERVICE_CENTER = "service_center"
    const val REPLY_PATH_PRESENT = "reply_path_present"
    const val SUBJECT = "subject"
    const val ERROR_CODE = "error_code"

    /** Columns some OEM providers use instead of [SUBSCRIPTION_ID] (subscription ids). */
    val ALT_SUBSCRIPTION_COLUMNS = listOf("sim_id", "subscription_id")

    /** Columns some OEM providers use for the SIM slot instead of a subscription id. */
    val SLOT_COLUMNS = listOf("sim_slot", "slot", "phone_id")

    const val STATUS_NONE = -1
    const val STATUS_COMPLETE = 0
    const val STATUS_PENDING = 32
    const val STATUS_FAILED = 64

    const val TYPE_INBOX = 1
    const val TYPE_SENT = 2
    const val TYPE_DRAFT = 3
    const val TYPE_OUTBOX = 4
    const val TYPE_FAILED = 5
    const val TYPE_QUEUED = 6
}

/** `Telephony.Mms` / `Telephony.BaseMmsColumns`. MMS dates are in seconds. */
internal object MmsColumns {
    const val ID = "_id"
    const val THREAD_ID = "thread_id"
    const val DATE = "date"
    const val DATE_SENT = "date_sent"
    const val MESSAGE_BOX = "msg_box"
    const val READ = "read"
    const val SEEN = "seen"
    const val MESSAGE_TYPE = "m_type"
    const val SUBJECT = "sub"
    const val SUBJECT_CHARSET = "sub_cs"
    const val CONTENT_TYPE = "ct_t"
    const val CONTENT_LOCATION = "ct_l"
    const val TRANSACTION_ID = "tr_id"
    const val MESSAGE_ID = "m_id"
    const val EXPIRY = "exp"
    const val MESSAGE_SIZE = "m_size"
    const val MESSAGE_CLASS = "m_cls"
    const val MMS_VERSION = "v"
    const val PRIORITY = "pri"
    const val DELIVERY_REPORT = "d_rpt"
    const val READ_REPORT = "rr"
    const val RESPONSE_STATUS = "resp_st"
    const val RETRIEVE_STATUS = "retr_st"
    const val STATUS = "st"
    const val READ_STATUS = "read_status"
    const val TEXT_ONLY = "text_only"
    const val SUBSCRIPTION_ID = "sub_id"

    const val BOX_INBOX = 1
    const val BOX_SENT = 2
    const val BOX_DRAFTS = 3
    const val BOX_OUTBOX = 4
    const val BOX_FAILED = 5

    /** m_type values we surface as messages: send-req, notification-ind, retrieve-conf. */
    const val MESSAGE_TYPE_FILTER = "m_type IN (128,130,132)"
}

/** `Telephony.Mms.Part`. */
internal object MmsPartColumns {
    const val ID = "_id"
    const val MSG_ID = "mid"
    const val SEQ = "seq"
    const val CONTENT_TYPE = "ct"
    const val NAME = "name"
    const val CHARSET = "chset"
    const val FILENAME = "fn"
    const val CONTENT_DISPOSITION = "cd"
    const val CONTENT_ID = "cid"
    const val CONTENT_LOCATION = "cl"
    const val TEXT = "text"
}

/** `Telephony.Mms.Addr`. Types are the PDU header codes (From 0x89, To 0x97, Cc 0x82, Bcc 0x81). */
internal object MmsAddrColumns {
    const val ADDRESS = "address"
    const val TYPE = "type"
    const val CHARSET = "charset"
    const val MSG_ID = "msg_id"

    const val TYPE_FROM = 137
    const val TYPE_TO = 151
    const val TYPE_CC = 130
    const val TYPE_BCC = 129

    /** What the platform stores as the From address of our own outgoing messages. */
    const val INSERT_ADDRESS_TOKEN = "insert-address-token"
}

/** Maps between [MessageBox] and the SMS `type` / MMS `msg_box` columns. */
internal object BoxMapping {
    fun smsTypeToBox(type: Int): MessageBox = MessageBox.fromProviderType(type)

    fun boxToSmsType(box: MessageBox): Int = box.providerType

    fun mmsBoxToBox(msgBox: Int): MessageBox = when (msgBox) {
        MmsColumns.BOX_INBOX -> MessageBox.INBOX
        MmsColumns.BOX_SENT -> MessageBox.SENT
        MmsColumns.BOX_DRAFTS -> MessageBox.DRAFT
        MmsColumns.BOX_OUTBOX -> MessageBox.OUTBOX
        MmsColumns.BOX_FAILED -> MessageBox.FAILED
        else -> MessageBox.INBOX
    }

    fun boxToMmsBox(box: MessageBox): Int = when (box) {
        MessageBox.INBOX -> MmsColumns.BOX_INBOX
        MessageBox.SENT -> MmsColumns.BOX_SENT
        MessageBox.DRAFT -> MmsColumns.BOX_DRAFTS
        MessageBox.OUTBOX, MessageBox.QUEUED -> MmsColumns.BOX_OUTBOX
        MessageBox.FAILED -> MmsColumns.BOX_FAILED
    }
}
