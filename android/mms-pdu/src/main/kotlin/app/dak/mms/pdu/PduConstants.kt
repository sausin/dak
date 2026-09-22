package app.dak.mms.pdu

/**
 * `X-Mms-Message-Type` values (OMA-MMS-ENC §7.3.30). These octets are also what the Telephony provider stores in
 * `Telephony.Mms.MESSAGE_TYPE` (`m_type`), e.g. 128 = send-req, 130 = notification-ind, 132 = retrieve-conf.
 */
object MessageType {
    const val SEND_REQ: Int = 0x80
    const val SEND_CONF: Int = 0x81
    const val NOTIFICATION_IND: Int = 0x82
    const val NOTIFYRESP_IND: Int = 0x83
    const val RETRIEVE_CONF: Int = 0x84
    const val ACKNOWLEDGE_IND: Int = 0x85
    const val DELIVERY_IND: Int = 0x86
    const val READ_REC_IND: Int = 0x87
    const val READ_ORIG_IND: Int = 0x88
}

/**
 * `X-Mms-MMS-Version` values as `(major shl 4) or minor`. The wire octet is this value with the high bit set
 * (e.g. 1.2 is sent as 0x92). The Telephony provider stores the unflagged value in `v`.
 */
object MmsVersion {
    const val V1_0: Int = 0x10
    const val V1_1: Int = 0x11
    const val V1_2: Int = 0x12
    const val V1_3: Int = 0x13

    /** The version we send; 1.2 is what AOSP and nearly every MMSC speak. */
    const val DEFAULT: Int = V1_2

    fun major(version: Int): Int = (version shr 4) and 0x07
    fun minor(version: Int): Int = version and 0x0F
}

/** `X-Mms-Response-Status` values (m-send-conf). */
object ResponseStatus {
    const val OK: Int = 0x80
    const val ERROR_UNSPECIFIED: Int = 0x81
    const val ERROR_SERVICE_DENIED: Int = 0x82
    const val ERROR_MESSAGE_FORMAT_CORRUPT: Int = 0x83
    const val ERROR_SENDING_ADDRESS_UNRESOLVED: Int = 0x84
    const val ERROR_MESSAGE_NOT_FOUND: Int = 0x85
    const val ERROR_NETWORK_PROBLEM: Int = 0x86
    const val ERROR_CONTENT_NOT_ACCEPTED: Int = 0x87
    const val ERROR_UNSUPPORTED_MESSAGE: Int = 0x88
    const val ERROR_TRANSIENT_FAILURE: Int = 0xC0
    const val ERROR_TRANSIENT_NETWORK_PROBLEM: Int = 0xC3
    const val ERROR_PERMANENT_FAILURE: Int = 0xE0

    /** Transient errors (0xC0..0xDF, plus the legacy 1.0 "network problem") may succeed on retry. */
    fun isTransient(status: Int): Boolean = status in 0xC0..0xDF || status == ERROR_NETWORK_PROBLEM

    fun describe(status: Int): String = when (status) {
        OK -> "OK"
        ERROR_SERVICE_DENIED, 0xE1 -> "Service denied"
        ERROR_MESSAGE_FORMAT_CORRUPT, 0xE2 -> "Message format corrupt"
        ERROR_SENDING_ADDRESS_UNRESOLVED, 0xC1, 0xE3 -> "Recipient address not resolved"
        ERROR_MESSAGE_NOT_FOUND, 0xC2, 0xE4 -> "Message not found"
        ERROR_NETWORK_PROBLEM, ERROR_TRANSIENT_NETWORK_PROBLEM -> "Network problem"
        ERROR_CONTENT_NOT_ACCEPTED, 0xE5 -> "Content not accepted"
        ERROR_UNSUPPORTED_MESSAGE, 0xEA -> "Unsupported message"
        0xE6 -> "Reply charging limitations not met"
        0xE7 -> "Reply charging request not accepted"
        0xE8 -> "Reply charging forwarding denied"
        0xE9 -> "Reply charging not supported"
        0xEB -> "Address hiding not supported"
        0xEC -> "Lack of prepaid credit"
        else -> if (isTransient(status)) "Temporary MMSC failure (0x%02X)".format(status)
        else "MMSC error (0x%02X)".format(status)
    }
}

/** `X-Mms-Retrieve-Status` values (m-retrieve-conf). */
object RetrieveStatus {
    const val OK: Int = 0x80
    const val ERROR_TRANSIENT_FAILURE: Int = 0xC0
    const val ERROR_TRANSIENT_MESSAGE_NOT_FOUND: Int = 0xC1
    const val ERROR_TRANSIENT_NETWORK_PROBLEM: Int = 0xC2
    const val ERROR_PERMANENT_FAILURE: Int = 0xE0
    const val ERROR_PERMANENT_SERVICE_DENIED: Int = 0xE1
    const val ERROR_PERMANENT_MESSAGE_NOT_FOUND: Int = 0xE2
    const val ERROR_PERMANENT_CONTENT_UNSUPPORTED: Int = 0xE3

    fun isTransient(status: Int): Boolean = status in 0xC0..0xDF
}

/** `X-Mms-Status` values (m-notifyresp-ind, m-delivery-ind). */
object MmsStatus {
    const val EXPIRED: Int = 0x80
    const val RETRIEVED: Int = 0x81
    const val REJECTED: Int = 0x82
    const val DEFERRED: Int = 0x83
    const val UNRECOGNISED: Int = 0x84
    const val INDETERMINATE: Int = 0x85
    const val FORWARDED: Int = 0x86
    const val UNREACHABLE: Int = 0x87
}

/** `X-Mms-Read-Status` values (m-read-orig-ind). */
object ReadStatus {
    const val READ: Int = 0x80
    const val DELETED_WITHOUT_BEING_READ: Int = 0x81
}

/** `X-Mms-Priority` values. */
object Priority {
    const val LOW: Int = 0x80
    const val NORMAL: Int = 0x81
    const val HIGH: Int = 0x82
}

/** `X-Mms-Message-Class` well-known classes; any other class is carried as token text. */
object MessageClass {
    const val PERSONAL: String = "personal"
    const val ADVERTISEMENT: String = "advertisement"
    const val INFORMATIONAL: String = "informational"
    const val AUTO: String = "auto"

    internal fun fromOctet(octet: Int): String? = when (octet) {
        0x80 -> PERSONAL
        0x81 -> ADVERTISEMENT
        0x82 -> INFORMATIONAL
        0x83 -> AUTO
        else -> null
    }

    internal fun toOctet(name: String): Int? = when (name.lowercase()) {
        PERSONAL -> 0x80
        ADVERTISEMENT -> 0x81
        INFORMATIONAL -> 0x82
        AUTO -> 0x83
        else -> null
    }
}

/** Header field codes (OMA-MMS-ENC Table 12), without the high bit. */
internal object Field {
    const val BCC = 0x01
    const val CC = 0x02
    const val CONTENT_LOCATION = 0x03
    const val CONTENT_TYPE = 0x04
    const val DATE = 0x05
    const val DELIVERY_REPORT = 0x06
    const val DELIVERY_TIME = 0x07
    const val EXPIRY = 0x08
    const val FROM = 0x09
    const val MESSAGE_CLASS = 0x0A
    const val MESSAGE_ID = 0x0B
    const val MESSAGE_TYPE = 0x0C
    const val MMS_VERSION = 0x0D
    const val MESSAGE_SIZE = 0x0E
    const val PRIORITY = 0x0F
    const val READ_REPORT = 0x10
    const val REPORT_ALLOWED = 0x11
    const val RESPONSE_STATUS = 0x12
    const val RESPONSE_TEXT = 0x13
    const val SENDER_VISIBILITY = 0x14
    const val STATUS = 0x15
    const val SUBJECT = 0x16
    const val TO = 0x17
    const val TRANSACTION_ID = 0x18
    const val RETRIEVE_STATUS = 0x19
    const val RETRIEVE_TEXT = 0x1A
    const val READ_STATUS = 0x1B
    const val REPLY_CHARGING = 0x1C
    const val REPLY_CHARGING_DEADLINE = 0x1D
    const val REPLY_CHARGING_ID = 0x1E
    const val REPLY_CHARGING_SIZE = 0x1F
    const val PREVIOUSLY_SENT_BY = 0x20
    const val PREVIOUSLY_SENT_DATE = 0x21
    const val STORE = 0x22
    const val MM_STATE = 0x23
    const val MM_FLAGS = 0x24
    const val STORE_STATUS = 0x25
    const val STORE_STATUS_TEXT = 0x26
    const val STORED = 0x27
    const val ATTRIBUTES = 0x28
    const val TOTALS = 0x29
    const val MBOX_TOTALS = 0x2A
    const val QUOTAS = 0x2B
    const val MBOX_QUOTAS = 0x2C
    const val MESSAGE_COUNT = 0x2D
    const val CONTENT = 0x2E
    const val START = 0x2F
    const val ADDITIONAL_HEADERS = 0x30
    const val DISTRIBUTION_INDICATOR = 0x31
    const val ELEMENT_DESCRIPTOR = 0x32
    const val LIMIT = 0x33
    const val RECOMMENDED_RETRIEVAL_MODE = 0x34
    const val RECOMMENDED_RETRIEVAL_MODE_TEXT = 0x35
    const val STATUS_TEXT = 0x36
    const val APPLIC_ID = 0x37
    const val REPLY_APPLIC_ID = 0x38
    const val AUX_APPLIC_INFO = 0x39
    const val CONTENT_CLASS = 0x3A
    const val DRM_CONTENT = 0x3B
    const val ADAPTATION_ALLOWED = 0x3C
    const val REPLACE_ID = 0x3D
    const val CANCEL_ID = 0x3E
    const val CANCEL_STATUS = 0x3F

    /** Fields whose value is a single octet (enumerations and Yes/No). */
    val OCTET_FIELDS: Set<Int> = setOf(
        MESSAGE_TYPE, MMS_VERSION, DELIVERY_REPORT, PRIORITY, READ_REPORT, REPORT_ALLOWED, RESPONSE_STATUS,
        SENDER_VISIBILITY, STATUS, RETRIEVE_STATUS, READ_STATUS, REPLY_CHARGING, STORE, MM_STATE, STORE_STATUS,
        STORED, DISTRIBUTION_INDICATOR, CONTENT_CLASS, DRM_CONTENT, ADAPTATION_ALLOWED, RECOMMENDED_RETRIEVAL_MODE,
        CANCEL_STATUS,
    )

    /** Fields whose value is an Encoded-string-value. */
    val ENCODED_STRING_FIELDS: Set<Int> = setOf(
        BCC, CC, TO, SUBJECT, RESPONSE_TEXT, RETRIEVE_TEXT, STORE_STATUS_TEXT, RECOMMENDED_RETRIEVAL_MODE_TEXT,
        STATUS_TEXT,
    )

    /** Fields whose value is a plain Text-string (or Uri-value). */
    val TEXT_FIELDS: Set<Int> = setOf(
        CONTENT_LOCATION, MESSAGE_ID, TRANSACTION_ID, REPLY_CHARGING_ID, APPLIC_ID, REPLY_APPLIC_ID,
        AUX_APPLIC_INFO, REPLACE_ID, CANCEL_ID,
    )

    /** Fields whose value is a Long-integer. */
    val LONG_FIELDS: Set<Int> = setOf(DATE, MESSAGE_SIZE, REPLY_CHARGING_SIZE)

    /** Fields whose value is an Integer-value. */
    val INTEGER_FIELDS: Set<Int> = setOf(MESSAGE_COUNT, START, LIMIT)

    /** Fields whose value is Value-length (Absolute-token Date-value | Relative-token Delta-seconds-value). */
    val TIME_FIELDS: Set<Int> = setOf(EXPIRY, DELIVERY_TIME, REPLY_CHARGING_DEADLINE)

    const val YES = 0x80
    const val NO = 0x81
    const val FROM_ADDRESS_PRESENT = 0x80
    const val FROM_INSERT_ADDRESS = 0x81
    const val TIME_ABSOLUTE = 0x80
    const val TIME_RELATIVE = 0x81
}

/** WSP header field codes used inside multipart part headers (WAP-230 Table 39), without the high bit. */
internal object PartField {
    const val CONTENT_LOCATION = 0x0E
    const val CONTENT_DISPOSITION = 0x2E
    const val CONTENT_ID = 0x40
    const val CONTENT_DISPOSITION_1_4 = 0x45
}
