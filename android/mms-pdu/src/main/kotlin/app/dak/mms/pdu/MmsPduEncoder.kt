package app.dak.mms.pdu

/**
 * Encodes MMS PDUs. The PDUs a client sends are m-send-req, m-notifyresp-ind and m-acknowledge-ind; the other
 * types are encodable too so tests and tools can build realistic fixtures.
 *
 * Header order follows the spec: X-Mms-Message-Type, X-Mms-Transaction-ID, X-Mms-MMS-Version first and
 * Content-Type last (followed by the multipart body).
 */
object MmsPduEncoder {

    fun encode(pdu: MmsPdu): ByteArray {
        val w = WspWriter()
        when (pdu) {
            is SendReq -> sendReq(w, pdu)
            is NotifyRespInd -> {
                preamble(w, pdu.messageType, pdu.transactionId, pdu.mmsVersion)
                octetField(w, Field.STATUS, pdu.status)
                boolField(w, Field.REPORT_ALLOWED, pdu.reportAllowed)
            }
            is AcknowledgeInd -> {
                preamble(w, pdu.messageType, pdu.transactionId, pdu.mmsVersion)
                boolField(w, Field.REPORT_ALLOWED, pdu.reportAllowed)
            }
            is NotificationInd -> notificationInd(w, pdu)
            is RetrieveConf -> retrieveConf(w, pdu)
            is SendConf -> {
                preamble(w, pdu.messageType, pdu.transactionId, pdu.mmsVersion)
                octetField(w, Field.RESPONSE_STATUS, pdu.responseStatus)
                pdu.responseText?.let { encodedField(w, Field.RESPONSE_TEXT, it) }
                pdu.messageId?.let { textField(w, Field.MESSAGE_ID, it) }
            }
            is DeliveryInd -> {
                preamble(w, pdu.messageType, null, pdu.mmsVersion)
                textField(w, Field.MESSAGE_ID, pdu.messageId)
                pdu.to.forEach { encodedField(w, Field.TO, MmsAddress.toWire(it)) }
                pdu.dateSeconds?.let { longField(w, Field.DATE, it) }
                octetField(w, Field.STATUS, pdu.status)
            }
            is ReadOrigInd -> {
                preamble(w, pdu.messageType, null, pdu.mmsVersion)
                pdu.messageId?.let { textField(w, Field.MESSAGE_ID, it) }
                pdu.to.forEach { encodedField(w, Field.TO, MmsAddress.toWire(it)) }
                fromField(w, pdu.from)
                pdu.dateSeconds?.let { longField(w, Field.DATE, it) }
                pdu.readStatus?.let { octetField(w, Field.READ_STATUS, it) }
            }
        }
        return w.toByteArray()
    }

    private fun sendReq(w: WspWriter, pdu: SendReq) {
        preamble(w, pdu.messageType, pdu.transactionId, pdu.mmsVersion)
        pdu.dateSeconds?.let { longField(w, Field.DATE, it) }
        fromField(w, pdu.from)
        pdu.to.forEach { encodedField(w, Field.TO, MmsAddress.toWire(it)) }
        pdu.cc.forEach { encodedField(w, Field.CC, MmsAddress.toWire(it)) }
        pdu.bcc.forEach { encodedField(w, Field.BCC, MmsAddress.toWire(it)) }
        pdu.subject?.let { encodedField(w, Field.SUBJECT, it) }
        pdu.messageClass?.let { classField(w, it) }
        pdu.expiry?.let { timeField(w, Field.EXPIRY, it) }
        pdu.priority?.let { octetField(w, Field.PRIORITY, it) }
        boolField(w, Field.DELIVERY_REPORT, pdu.deliveryReport)
        boolField(w, Field.READ_REPORT, pdu.readReport)
        body(w, pdu.contentType, pdu.parts)
    }

    private fun notificationInd(w: WspWriter, pdu: NotificationInd) {
        preamble(w, pdu.messageType, pdu.transactionId, pdu.mmsVersion)
        pdu.from?.let { fromField(w, it) }
        pdu.subject?.let { encodedField(w, Field.SUBJECT, it) }
        pdu.messageClass?.let { classField(w, it) }
        w.shortInteger(Field.MESSAGE_SIZE)
        w.longInteger(pdu.messageSize)
        pdu.expiry?.let { timeField(w, Field.EXPIRY, it) }
        pdu.priority?.let { octetField(w, Field.PRIORITY, it) }
        boolField(w, Field.DELIVERY_REPORT, pdu.deliveryReport)
        textField(w, Field.CONTENT_LOCATION, pdu.contentLocation)
    }

    private fun retrieveConf(w: WspWriter, pdu: RetrieveConf) {
        preamble(w, pdu.messageType, pdu.transactionId, pdu.mmsVersion)
        pdu.messageId?.let { textField(w, Field.MESSAGE_ID, it) }
        pdu.dateSeconds?.let { longField(w, Field.DATE, it) }
        pdu.from?.let { fromField(w, it) }
        pdu.to.forEach { encodedField(w, Field.TO, MmsAddress.toWire(it)) }
        pdu.cc.forEach { encodedField(w, Field.CC, MmsAddress.toWire(it)) }
        pdu.subject?.let { encodedField(w, Field.SUBJECT, it) }
        pdu.messageClass?.let { classField(w, it) }
        pdu.priority?.let { octetField(w, Field.PRIORITY, it) }
        boolField(w, Field.DELIVERY_REPORT, pdu.deliveryReport)
        boolField(w, Field.READ_REPORT, pdu.readReport)
        pdu.retrieveStatus?.let { octetField(w, Field.RETRIEVE_STATUS, it) }
        pdu.retrieveText?.let { encodedField(w, Field.RETRIEVE_TEXT, it) }
        body(w, pdu.contentType, pdu.parts)
    }

    // --- Field writers --------------------------------------------------------------------------------------

    private fun preamble(w: WspWriter, type: Int, transactionId: String?, version: Int) {
        octetField(w, Field.MESSAGE_TYPE, type)
        transactionId?.let { textField(w, Field.TRANSACTION_ID, it) }
        octetField(w, Field.MMS_VERSION, (version and 0x7F) or 0x80)
    }

    private fun octetField(w: WspWriter, field: Int, octet: Int) {
        w.shortInteger(field)
        w.octet(octet)
    }

    private fun boolField(w: WspWriter, field: Int, value: Boolean?) {
        if (value != null) octetField(w, field, if (value) Field.YES else Field.NO)
    }

    private fun textField(w: WspWriter, field: Int, value: String) {
        w.shortInteger(field)
        w.textString(value)
    }

    private fun encodedField(w: WspWriter, field: Int, value: String) {
        w.shortInteger(field)
        w.encodedString(value)
    }

    private fun longField(w: WspWriter, field: Int, value: Long) {
        w.shortInteger(field)
        w.longInteger(value)
    }

    /** From: Value-length (Address-present-token Encoded-string | Insert-address-token). */
    private fun fromField(w: WspWriter, address: String?) {
        val body = WspWriter()
        if (address == null) {
            body.octet(Field.FROM_INSERT_ADDRESS)
        } else {
            body.octet(Field.FROM_ADDRESS_PRESENT)
            body.encodedString(MmsAddress.toWire(address))
        }
        w.shortInteger(Field.FROM)
        w.lengthPrefixed(body)
    }

    private fun classField(w: WspWriter, messageClass: String) {
        w.shortInteger(Field.MESSAGE_CLASS)
        val octet = MessageClass.toOctet(messageClass)
        if (octet != null) w.octet(octet) else w.textString(messageClass)
    }

    private fun timeField(w: WspWriter, field: Int, time: MmsTime) {
        val body = WspWriter()
        when (time) {
            is MmsTime.Absolute -> {
                body.octet(Field.TIME_ABSOLUTE)
                body.longInteger(time.epochSeconds)
            }
            is MmsTime.Relative -> {
                body.octet(Field.TIME_RELATIVE)
                body.integerValue(time.seconds)
            }
        }
        w.shortInteger(field)
        w.lengthPrefixed(body)
    }

    private fun body(w: WspWriter, contentType: ContentType, parts: List<PduPart>) {
        w.shortInteger(Field.CONTENT_TYPE)
        ContentTypeCodec.write(w, contentType)
        if (contentType.isMultipart) {
            MultipartCodec.write(w, parts)
        } else {
            parts.firstOrNull()?.let { w.bytes(it.data) }
        }
    }
}
