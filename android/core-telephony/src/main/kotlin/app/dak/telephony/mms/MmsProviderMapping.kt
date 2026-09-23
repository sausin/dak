package app.dak.telephony.mms

import app.dak.core.model.Attachment
import app.dak.mms.pdu.ContentType
import app.dak.mms.pdu.MmsCharset
import app.dak.mms.pdu.MmsLimits
import app.dak.mms.pdu.MmsSafety
import app.dak.mms.pdu.NotificationInd
import app.dak.mms.pdu.PduPart
import app.dak.mms.pdu.Priority
import app.dak.mms.pdu.RetrieveConf
import app.dak.mms.pdu.SendReq
import app.dak.mms.pdu.MessageType
import app.dak.telephony.provider.MmsAddrColumns
import app.dak.telephony.provider.MmsColumns
import app.dak.telephony.provider.MmsPartColumns

/** A part row to insert under `content://mms/<id>/part`: [text] goes in the `text` column, [data] is streamed. */
internal class PartRow(val values: Map<String, Any>, val text: String?, val data: ByteArray?)

/** An address row for `content://mms/<id>/addr`. */
internal data class AddrRow(val address: String, val type: Int)

/** A part row as read back from the provider. */
internal data class StoredPart(
    val id: Long,
    val contentType: String,
    val text: String?,
    val name: String?,
    val fileName: String?,
    val contentLocation: String?,
)

/**
 * Pure mapping between decoded PDUs and Telephony MMS provider rows (`pdu`, `part`, `addr`). Values are plain
 * Kotlin Int/Long/String so the Android layer converts them to ContentValues mechanically, and this logic stays
 * unit-testable on the JVM.
 */
internal object MmsProviderMapping {
    private const val YES = 0x80
    private const val NO = 0x81

    /** Row for a freshly received m-notification-ind (msg_box inbox, m_type 130). */
    fun notificationRow(n: NotificationInd, subId: Int, threadId: Long, nowMillis: Long): Map<String, Any> {
        val nowSeconds = nowMillis / 1000
        return buildMap<String, Any> {
            put(MmsColumns.MESSAGE_TYPE, MessageType.NOTIFICATION_IND)
            put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_INBOX)
            put(MmsColumns.THREAD_ID, threadId)
            put(MmsColumns.DATE, nowSeconds)
            put(MmsColumns.READ, 0)
            put(MmsColumns.SEEN, 0)
            put(MmsColumns.CONTENT_LOCATION, n.contentLocation)
            n.transactionId?.let { put(MmsColumns.TRANSACTION_ID, it) }
            n.expiry?.let { put(MmsColumns.EXPIRY, it.toEpochSeconds(nowSeconds)) }
            put(MmsColumns.MESSAGE_SIZE, n.messageSize)
            n.messageClass?.let { put(MmsColumns.MESSAGE_CLASS, it) }
            n.priority?.let { put(MmsColumns.PRIORITY, it) }
            n.deliveryReport?.let { put(MmsColumns.DELIVERY_REPORT, if (it) YES else NO) }
            put(MmsColumns.MMS_VERSION, n.mmsVersion)
            putSubject(n.subject)
            putSubId(subId)
        }
    }

    /**
     * Row for a downloaded m-retrieve-conf. `date` is when we received it (like SMS), `date_sent` the MMSC's
     * Date header.
     */
    fun retrievedRow(r: RetrieveConf, subId: Int, threadId: Long, nowMillis: Long): Map<String, Any> = buildMap<String, Any> {
        put(MmsColumns.MESSAGE_TYPE, MessageType.RETRIEVE_CONF)
        put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_INBOX)
        put(MmsColumns.THREAD_ID, threadId)
        put(MmsColumns.DATE, nowMillis / 1000)
        put(MmsColumns.DATE_SENT, r.dateSeconds ?: 0L)
        put(MmsColumns.READ, 0)
        put(MmsColumns.SEEN, 0)
        put(MmsColumns.CONTENT_TYPE, r.contentType.mimeType)
        r.messageId?.let { put(MmsColumns.MESSAGE_ID, it) }
        r.transactionId?.let { put(MmsColumns.TRANSACTION_ID, it) }
        r.messageClass?.let { put(MmsColumns.MESSAGE_CLASS, it) }
        r.priority?.let { put(MmsColumns.PRIORITY, it) }
        r.deliveryReport?.let { put(MmsColumns.DELIVERY_REPORT, if (it) YES else NO) }
        r.readReport?.let { put(MmsColumns.READ_REPORT, if (it) YES else NO) }
        r.retrieveStatus?.let { put(MmsColumns.RETRIEVE_STATUS, it) }
        put(MmsColumns.MMS_VERSION, r.mmsVersion)
        put(MmsColumns.MESSAGE_SIZE, r.parts.sumOf { it.data.size.toLong() })
        put(MmsColumns.TEXT_ONLY, if (r.parts.all { isInlineText(it.contentType.mimeType) }) 1 else 0)
        putSubject(r.subject)
        putSubId(subId)
    }

    /** Row for an outgoing m-send-req, filed in the outbox until the send result arrives. */
    fun outgoingRow(req: SendReq, subId: Int, threadId: Long, nowMillis: Long, encodedSize: Int): Map<String, Any> = buildMap<String, Any> {
        put(MmsColumns.MESSAGE_TYPE, MessageType.SEND_REQ)
        put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_OUTBOX)
        put(MmsColumns.THREAD_ID, threadId)
        put(MmsColumns.DATE, nowMillis / 1000)
        put(MmsColumns.READ, 1)
        put(MmsColumns.SEEN, 1)
        put(MmsColumns.CONTENT_TYPE, req.contentType.mimeType)
        put(MmsColumns.TRANSACTION_ID, req.transactionId)
        req.messageClass?.let { put(MmsColumns.MESSAGE_CLASS, it) }
        put(MmsColumns.PRIORITY, req.priority ?: Priority.NORMAL)
        put(MmsColumns.DELIVERY_REPORT, if (req.deliveryReport == true) YES else NO)
        put(MmsColumns.READ_REPORT, if (req.readReport == true) YES else NO)
        put(MmsColumns.MMS_VERSION, req.mmsVersion)
        put(MmsColumns.MESSAGE_SIZE, encodedSize)
        put(MmsColumns.TEXT_ONLY, if (req.parts.all { isInlineText(it.contentType.mimeType) }) 1 else 0)
        putSubject(req.subject)
        putSubId(subId)
    }

    /**
     * Part rows in order; text/plain and SMIL are stored inline (decoded), everything else as data. Inline text is
     * bounded per part ([MmsLimits.MAX_INLINE_TEXT_CHARS]) and per message ([MmsLimits.MAX_MESSAGE_TEXT_CHARS]): the
     * `text` column crosses Binder on insert and a CursorWindow on every read, so a hostile multi-megabyte text part
     * would otherwise fail the insert (message lost and re-fetched) or make every later read of the thread throw.
     */
    fun partRows(parts: List<PduPart>): List<PartRow> {
        var textBudget = MmsLimits.MAX_MESSAGE_TEXT_CHARS
        return parts.mapIndexed { index, part ->
            val row = partRow(index, part, textBudget)
            textBudget -= row.text?.length ?: 0
            row
        }
    }

    private fun partRow(index: Int, part: PduPart, textBudget: Int): PartRow {
        val mime = part.contentType.mimeType
        val inline = isInlineText(mime)
        val values = buildMap<String, Any> {
            put(MmsPartColumns.SEQ, index)
            put(MmsPartColumns.CONTENT_TYPE, mime)
            // Inline text is stored as a decoded String, so its charset is UTF-8 from here on.
            val charset = if (inline) MmsCharset.UTF_8 else part.charset
            charset?.let { put(MmsPartColumns.CHARSET, it) }
            // name / fn are what other SMS apps use as the file name when saving a part: never store a sender-chosen
            // path ("../../x", "/data/…", NUL, bidi overrides). cid / cl stay raw so SMIL references still resolve.
            MmsSafety.safeFileName(part.contentType.name ?: part.fileName)?.let { put(MmsPartColumns.NAME, it) }
            part.safeFileName?.let { put(MmsPartColumns.FILENAME, it) }
            part.contentDisposition?.let { put(MmsPartColumns.CONTENT_DISPOSITION, it) }
            part.contentId?.let { put(MmsPartColumns.CONTENT_ID, it) }
            part.contentLocation?.let { put(MmsPartColumns.CONTENT_LOCATION, it) }
        }
        return if (inline) {
            val limit = minOf(MmsLimits.MAX_INLINE_TEXT_CHARS, textBudget.coerceAtLeast(0))
            PartRow(values, text = part.text(limit).orEmpty(), data = null)
        } else {
            PartRow(values, text = null, data = part.data)
        }
    }

    /** From / To / Cc rows of a received message. */
    fun retrievedAddresses(r: RetrieveConf): List<AddrRow> = buildList<AddrRow> {
        add(AddrRow(r.from ?: MmsAddrColumns.INSERT_ADDRESS_TOKEN, MmsAddrColumns.TYPE_FROM))
        r.to.forEach { add(AddrRow(it, MmsAddrColumns.TYPE_TO)) }
        r.cc.forEach { add(AddrRow(it, MmsAddrColumns.TYPE_CC)) }
    }

    /** From row of a notification (the only address it carries). */
    fun notificationAddresses(n: NotificationInd): List<AddrRow> =
        listOf(AddrRow(n.from ?: MmsAddrColumns.INSERT_ADDRESS_TOKEN, MmsAddrColumns.TYPE_FROM))

    /** Rows for an outgoing message: our own From token, then To / Cc / Bcc. */
    fun outgoingAddresses(req: SendReq): List<AddrRow> = buildList<AddrRow> {
        add(AddrRow(MmsAddrColumns.INSERT_ADDRESS_TOKEN, MmsAddrColumns.TYPE_FROM))
        req.to.forEach { add(AddrRow(it, MmsAddrColumns.TYPE_TO)) }
        req.cc.forEach { add(AddrRow(it, MmsAddrColumns.TYPE_CC)) }
        req.bcc.forEach { add(AddrRow(it, MmsAddrColumns.TYPE_BCC)) }
    }

    /**
     * Recipients that identify the provider thread of a received MMS: the sender plus every other To/Cc address
     * that is not ours. A single To/Cc recipient is by definition us (a 1:1 message), even when our own number is
     * unknown, so the thread is just the sender.
     */
    fun threadRecipients(from: String?, to: List<String>, cc: List<String>, isOwnNumber: (String) -> Boolean): Set<String> {
        val recipients = (to + cc).filter { it.isNotBlank() && it != MmsAddrColumns.INSERT_ADDRESS_TOKEN }
        val result = LinkedHashSet<String>()
        from?.takeIf { it.isNotBlank() && it != MmsAddrColumns.INSERT_ADDRESS_TOKEN }?.let { result.add(it) }
        if (recipients.size > 1) {
            recipients.filterNot(isOwnNumber).forEach { candidate ->
                if (result.none { it.equals(candidate, ignoreCase = true) }) result.add(candidate)
            }
        }
        return result
    }

    /** Body text (text/plain parts joined by newlines) and attachments (everything but text and SMIL). */
    fun bodyAndAttachments(parts: List<StoredPart>): Pair<String, List<Attachment>> {
        val text = parts.filter { it.contentType.equals(ContentType.TEXT_PLAIN, ignoreCase = true) }
            .mapNotNull { it.text }
            .joinToString("\n")
        val attachments = parts
            .filterNot { isInlineText(it.contentType.lowercase()) }
            .map { p ->
                Attachment(
                    mimeType = p.contentType.lowercase(),
                    uri = partUri(p.id),
                    name = MmsSafety.safeFileName(p.contentLocation ?: p.name ?: p.fileName),
                )
            }
        return text to attachments
    }

    /** `content://mms/part/<id>`: the URI apps (and our UI) use to open a stored part. */
    fun partUri(partId: Long): String = "content://mms/part/$partId"

    private fun isInlineText(mime: String): Boolean =
        mime == ContentType.TEXT_PLAIN || mime == ContentType.SMIL

    private fun MutableMap<String, Any>.putSubject(subject: String?) {
        if (!subject.isNullOrEmpty()) {
            put(MmsColumns.SUBJECT, subject)
            put(MmsColumns.SUBJECT_CHARSET, MmsCharset.UTF_8)
        }
    }

    private fun MutableMap<String, Any>.putSubId(subId: Int) {
        if (subId >= 0) put(MmsColumns.SUBSCRIPTION_ID, subId)
    }
}
