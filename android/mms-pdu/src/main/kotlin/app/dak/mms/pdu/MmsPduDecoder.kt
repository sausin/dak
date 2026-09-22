package app.dak.mms.pdu

/**
 * Decodes MMS encapsulation PDUs (OMA-MMS-ENC 1.0–1.3 with WSP header encoding).
 *
 * Supported: m-notification-ind, m-retrieve-conf, m-send-conf, m-delivery-ind, m-read-orig-ind, and (for
 * round-trips and re-sends) m-send-req, m-notifyresp-ind, m-acknowledge-ind. Unknown headers are skipped using the
 * generic WSP value rules; application headers are ignored. This function never throws.
 */
object MmsPduDecoder {

    fun decode(bytes: ByteArray): PduDecodeResult {
        if (bytes.isEmpty()) return PduDecodeResult.Failure(PduError.Empty)
        return try {
            val reader = WspReader(bytes)
            val headers = readHeaders(reader)
            PduDecodeResult.Success(build(headers, reader))
        } catch (e: PduFormatException) {
            val detail = e.message ?: "invalid"
            PduDecodeResult.Failure(
                if (e.truncated) PduError.Truncated(e.offset, detail) else PduError.Malformed(e.offset, detail),
            )
        } catch (e: MissingHeaderException) {
            PduDecodeResult.Failure(PduError.MissingHeader(e.header))
        } catch (e: UnsupportedTypeException) {
            PduDecodeResult.Failure(PduError.UnsupportedMessageType(e.type))
        } catch (e: RuntimeException) {
            // Defensive: any bug in a decoder branch must surface as a typed error, not a crash in a receiver.
            PduDecodeResult.Failure(PduError.Malformed(-1, e.toString()))
        }
    }

    /** Convenience: decodes and returns the PDU only when it is of type [T]. */
    inline fun <reified T : MmsPdu> decodeAs(bytes: ByteArray): T? = decode(bytes).getOrNull() as? T

    // --- Headers --------------------------------------------------------------------------------------------

    private class MissingHeaderException(val header: String) : Exception(header)
    private class UnsupportedTypeException(val type: Int) : Exception("type $type")

    /** Decoded header values keyed by field code. Repeatable fields (To/Cc/Bcc) keep every occurrence. */
    private class Headers {
        val values: MutableMap<Int, MutableList<Any>> = HashMap()

        fun add(field: Int, value: Any) {
            values.getOrPut(field) { ArrayList(1) }.add(value)
        }

        fun first(field: Int): Any? = values[field]?.firstOrNull()
        fun octet(field: Int): Int? = first(field) as? Int
        fun text(field: Int): String? = first(field) as? String
        fun number(field: Int): Long? = first(field) as? Long
        fun time(field: Int): MmsTime? = first(field) as? MmsTime
        fun texts(field: Int): List<String> = values[field]?.filterIsInstance<String>().orEmpty()
        fun bool(field: Int): Boolean? = when (octet(field)) {
            Field.YES -> true
            Field.NO -> false
            else -> null
        }
        fun contentType(): ContentType? = first(Field.CONTENT_TYPE) as? ContentType
        fun from(): FromValue? = first(Field.FROM) as? FromValue
    }

    /** From header: [address] is null for the Insert-address-token. */
    private class FromValue(val address: String?)

    private fun readHeaders(r: WspReader): Headers {
        val headers = Headers()
        while (r.hasMore()) {
            val b = r.peek()
            when {
                b >= 0x80 -> {
                    r.readOctet()
                    val field = b and 0x7F
                    readField(field, r)?.let { headers.add(field, it) }
                    // Content-Type is always the last header; the body follows.
                    if (field == Field.CONTENT_TYPE) return headers
                }
                b in 32..126 -> {
                    // Application-header: Token-text Application-specific-value.
                    r.readTextBytes()
                    if (r.hasMore()) r.readTextBytes()
                }
                else -> throw r.malformed("unexpected octet 0x%02X in header section".format(b))
            }
        }
        return headers
    }

    private fun readField(field: Int, r: WspReader): Any? = when (field) {
        in Field.ENCODED_STRING_FIELDS -> r.readEncodedString()
        in Field.TEXT_FIELDS -> readTextField(r)
        in Field.OCTET_FIELDS -> readOctetField(r)
        in Field.LONG_FIELDS, in Field.INTEGER_FIELDS -> r.readIntegerValue()
        in Field.TIME_FIELDS -> readTime(r)
        Field.CONTENT_TYPE -> ContentTypeCodec.read(r)
        Field.FROM -> readFrom(r)
        Field.MESSAGE_CLASS -> readMessageClass(r)
        else -> {
            r.skipValue()
            null
        }
    }

    /** Text-string; tolerates the 1.3 `Value-length Status-count Text` form some servers use. */
    private fun readTextField(r: WspReader): String {
        val b = r.peek()
        if (b in 1..31) {
            val body = r.slice(r.readValueLength())
            if (body.hasMore() && body.nextIsIntegerValue()) body.readIntegerValue()
            return if (body.hasMore()) body.readTextString() else ""
        }
        return r.readTextString()
    }

    /** Single-octet enumerations. A non-octet value is skipped rather than failing the whole PDU. */
    private fun readOctetField(r: WspReader): Int? {
        if (r.peek() >= 0x80) return r.readOctet()
        r.skipValue()
        return null
    }

    private fun readTime(r: WspReader): MmsTime {
        val body = r.slice(r.readValueLength())
        return when (val token = body.readOctet()) {
            Field.TIME_ABSOLUTE -> MmsTime.Absolute(body.readIntegerValue())
            Field.TIME_RELATIVE -> MmsTime.Relative(body.readIntegerValue())
            else -> throw body.malformed("bad time token 0x%02X".format(token))
        }
    }

    private fun readFrom(r: WspReader): FromValue {
        if (r.peek() > 31) return FromValue(MmsAddress.fromWire(r.readEncodedString()))
        val body = r.slice(r.readValueLength())
        return when (val token = body.readOctet()) {
            Field.FROM_ADDRESS_PRESENT -> FromValue(
                if (body.hasMore()) MmsAddress.fromWire(body.readEncodedString()) else null,
            )
            Field.FROM_INSERT_ADDRESS -> FromValue(null)
            else -> throw body.malformed("bad From token 0x%02X".format(token))
        }
    }

    private fun readMessageClass(r: WspReader): String {
        val b = r.peek()
        if (b >= 0x80) {
            r.readOctet()
            return MessageClass.fromOctet(b) ?: "class-0x%02x".format(b)
        }
        return r.readTextString()
    }

    // --- Typed PDUs -----------------------------------------------------------------------------------------

    private fun build(h: Headers, body: WspReader): MmsPdu {
        val type = h.octet(Field.MESSAGE_TYPE) ?: throw UnsupportedTypeException(-1)
        val version = h.octet(Field.MMS_VERSION)?.let { it and 0x7F } ?: MmsVersion.V1_0
        val transactionId = h.text(Field.TRANSACTION_ID)
        return when (type) {
            MessageType.NOTIFICATION_IND -> NotificationInd(
                contentLocation = h.text(Field.CONTENT_LOCATION) ?: throw MissingHeaderException("X-Mms-Content-Location"),
                transactionId = transactionId,
                from = h.from()?.address,
                subject = h.text(Field.SUBJECT),
                messageClass = h.text(Field.MESSAGE_CLASS),
                messageSize = h.number(Field.MESSAGE_SIZE) ?: 0L,
                expiry = h.time(Field.EXPIRY),
                priority = h.octet(Field.PRIORITY),
                deliveryReport = h.bool(Field.DELIVERY_REPORT),
                mmsVersion = version,
            )
            MessageType.RETRIEVE_CONF -> {
                val contentType = h.contentType() ?: ContentType.of(ContentType.MULTIPART_MIXED)
                RetrieveConf(
                    contentType = contentType,
                    parts = readBody(contentType, body),
                    transactionId = transactionId,
                    messageId = h.text(Field.MESSAGE_ID),
                    dateSeconds = h.number(Field.DATE),
                    from = h.from()?.address,
                    to = h.texts(Field.TO).map(MmsAddress::fromWire),
                    cc = h.texts(Field.CC).map(MmsAddress::fromWire),
                    subject = h.text(Field.SUBJECT),
                    messageClass = h.text(Field.MESSAGE_CLASS),
                    priority = h.octet(Field.PRIORITY),
                    deliveryReport = h.bool(Field.DELIVERY_REPORT),
                    readReport = h.bool(Field.READ_REPORT),
                    retrieveStatus = h.octet(Field.RETRIEVE_STATUS),
                    retrieveText = h.text(Field.RETRIEVE_TEXT),
                    mmsVersion = version,
                )
            }
            MessageType.SEND_REQ -> {
                val contentType = h.contentType() ?: throw MissingHeaderException("Content-Type")
                SendReq(
                    transactionId = transactionId ?: throw MissingHeaderException("X-Mms-Transaction-ID"),
                    to = h.texts(Field.TO).map(MmsAddress::fromWire),
                    contentType = contentType,
                    parts = readBody(contentType, body),
                    cc = h.texts(Field.CC).map(MmsAddress::fromWire),
                    bcc = h.texts(Field.BCC).map(MmsAddress::fromWire),
                    from = h.from()?.address,
                    subject = h.text(Field.SUBJECT),
                    dateSeconds = h.number(Field.DATE),
                    messageClass = h.text(Field.MESSAGE_CLASS),
                    expiry = h.time(Field.EXPIRY),
                    priority = h.octet(Field.PRIORITY),
                    deliveryReport = h.bool(Field.DELIVERY_REPORT),
                    readReport = h.bool(Field.READ_REPORT),
                    mmsVersion = version,
                )
            }
            MessageType.SEND_CONF -> SendConf(
                responseStatus = h.octet(Field.RESPONSE_STATUS) ?: throw MissingHeaderException("X-Mms-Response-Status"),
                transactionId = transactionId,
                messageId = h.text(Field.MESSAGE_ID),
                responseText = h.text(Field.RESPONSE_TEXT),
                mmsVersion = version,
            )
            MessageType.NOTIFYRESP_IND -> NotifyRespInd(
                transactionId = transactionId ?: throw MissingHeaderException("X-Mms-Transaction-ID"),
                status = h.octet(Field.STATUS) ?: throw MissingHeaderException("X-Mms-Status"),
                reportAllowed = h.bool(Field.REPORT_ALLOWED),
                mmsVersion = version,
            )
            MessageType.ACKNOWLEDGE_IND -> AcknowledgeInd(
                transactionId = transactionId ?: throw MissingHeaderException("X-Mms-Transaction-ID"),
                reportAllowed = h.bool(Field.REPORT_ALLOWED),
                mmsVersion = version,
            )
            MessageType.DELIVERY_IND -> DeliveryInd(
                messageId = h.text(Field.MESSAGE_ID) ?: throw MissingHeaderException("Message-ID"),
                status = h.octet(Field.STATUS) ?: throw MissingHeaderException("X-Mms-Status"),
                to = h.texts(Field.TO).map(MmsAddress::fromWire),
                dateSeconds = h.number(Field.DATE),
                mmsVersion = version,
            )
            MessageType.READ_ORIG_IND -> ReadOrigInd(
                messageId = h.text(Field.MESSAGE_ID),
                from = h.from()?.address,
                to = h.texts(Field.TO).map(MmsAddress::fromWire),
                dateSeconds = h.number(Field.DATE),
                readStatus = h.octet(Field.READ_STATUS),
                mmsVersion = version,
            )
            else -> throw UnsupportedTypeException(type)
        }
    }

    /** Multipart bodies are split into parts; a single-part body becomes one part of the declared type. */
    private fun readBody(contentType: ContentType, body: WspReader): List<PduPart> {
        if (!body.hasMore()) return emptyList()
        return if (contentType.isMultipart) {
            MultipartCodec.read(body)
        } else {
            listOf(PduPart(contentType = contentType, data = body.readRemaining()))
        }
    }
}
