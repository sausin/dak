package app.dak.mms.pdu

/** Outcome of [MmsPduDecoder.decode]. Decoding never throws; malformed input yields [Failure]. */
sealed interface PduDecodeResult {
    data class Success(val pdu: MmsPdu) : PduDecodeResult

    data class Failure(val error: PduError) : PduDecodeResult

    /** The decoded PDU, or null on failure. */
    fun getOrNull(): MmsPdu? = (this as? Success)?.pdu
}

/** Why a PDU could not be decoded. */
sealed interface PduError {
    val message: String

    /** The input was empty. */
    data object Empty : PduError {
        override val message: String get() = "Empty PDU"
    }

    /** A length or terminator ran past the end of the input at [offset]. */
    data class Truncated(val offset: Int, val detail: String) : PduError {
        override val message: String get() = "Truncated PDU at offset $offset: $detail"
    }

    /** An encoding rule was violated at [offset]. */
    data class Malformed(val offset: Int, val detail: String) : PduError {
        override val message: String get() = "Malformed PDU at offset $offset: $detail"
    }

    /** A well-formed PDU of a type we do not model (e.g. m-mbox-*), or no X-Mms-Message-Type at all (-1). */
    data class UnsupportedMessageType(val messageType: Int) : PduError {
        override val message: String get() = "Unsupported message type 0x%02X".format(messageType)
    }

    /** A header the PDU type requires was missing. */
    data class MissingHeader(val header: String) : PduError {
        override val message: String get() = "Missing required header $header"
    }
}
