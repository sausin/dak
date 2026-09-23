package app.dak.backup.xml

import app.dak.backup.format.ArchiveLimitException
import app.dak.backup.format.ArchiveLimits
import app.dak.backup.format.MessageRecord
import app.dak.backup.importers.ImportedAttachment
import app.dak.backup.importers.ImportedMessage
import app.dak.backup.importers.Importer
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.core.model.NO_SUB_ID
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/** From-address type in SyncTech's `<addr type="...">` (source: SMS Backup & Restore's own documentation). */
private const val ADDR_TYPE_FROM = 137
private const val ADDR_TYPE_TO = 151
private const val ADDR_TYPE_CC = 130

private fun attrOrNull(v: String?): String? = if (v.isNullOrEmpty() || v == "null") null else v

/**
 * Exports [MessageRecord]s to the SMS Backup & Restore (SyncTech) XML format, streaming one
 * `<sms>`/`<mms>` element at a time so an arbitrarily large export never needs to buffer.
 */
object SmsBackupRestoreXmlExporter {
    private val readableDate = ThreadLocal.withInitial { SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US) }

    /**
     * Writes `messages` as a `<smses count="...">...</smses>` document. [attachmentBytes] resolves
     * an attachment's sha256 reference to its bytes for base64 inlining; returning null omits the
     * part's `data` attribute (the attachment is skipped, not the whole message).
     */
    fun write(
        output: OutputStream,
        messages: Sequence<MessageRecord>,
        count: Int,
        attachmentBytes: (sha256: String) -> ByteArray? = { null },
    ) {
        val writer = XmlWriter(output)
        writer.writeDeclaration()
        writer.startElement("smses", mapOf("count" to count.toString()))
        for (m in messages) {
            when (m.kind) {
                MessageKind.SMS -> writeSms(writer, m)
                MessageKind.MMS -> writeMms(writer, m, attachmentBytes)
            }
        }
        writer.endElement("smses")
        writer.flush()
    }

    private fun writeSms(writer: XmlWriter, m: MessageRecord) {
        writer.selfClosingElement(
            "sms",
            linkedMapOf(
                "protocol" to "0",
                "address" to m.address,
                "date" to m.dateMillis.toString(),
                "type" to m.box.providerType.toString(),
                "subject" to "null",
                "body" to m.body,
                "toa" to "null",
                "sc_toa" to "null",
                "service_center" to "null",
                "read" to if (m.read) "1" else "0",
                "status" to "-1",
                "locked" to "0",
                "date_sent" to m.dateMillis.toString(),
                "sub_id" to m.subId.toString(),
                "readable_date" to readableDate.get().format(Date(m.dateMillis)),
                "contact_name" to "(Unknown)",
            ),
        )
    }

    private fun writeMms(writer: XmlWriter, m: MessageRecord, attachmentBytes: (String) -> ByteArray?) {
        writer.startElement(
            "mms",
            linkedMapOf(
                "date" to m.dateMillis.toString(),
                "msg_box" to m.box.providerType.toString(),
                "read" to if (m.read) "1" else "0",
                "sub_id" to m.subId.toString(),
                "ct_t" to "application/vnd.wap.multipart.related",
                "readable_date" to readableDate.get().format(Date(m.dateMillis)),
                "contact_name" to "(Unknown)",
            ),
        )
        writer.startElement("parts")
        writer.selfClosingElement(
            "part",
            linkedMapOf("seq" to "0", "ct" to "text/plain", "name" to "null", "cid" to "<text>", "cl" to "text", "text" to m.body),
        )
        m.attachments.forEachIndexed { i, a ->
            val bytes = attachmentBytes(a.sha256)
            val attrs = linkedMapOf(
                "seq" to (i + 1).toString(),
                "ct" to a.mimeType,
                "name" to (a.name ?: a.sha256),
                "cl" to (a.name ?: a.sha256),
            )
            if (bytes != null) attrs["data"] = Base64.getEncoder().encodeToString(bytes)
            writer.selfClosingElement("part", attrs)
        }
        writer.endElement("parts")
        writer.startElement("addrs")
        if (m.box == MessageBox.INBOX) {
            writer.selfClosingElement("addr", mapOf("address" to m.address, "type" to ADDR_TYPE_FROM.toString(), "charset" to "106"))
        } else {
            for (addr in m.address.split(' ').filter { it.isNotBlank() }) {
                writer.selfClosingElement("addr", mapOf("address" to addr, "type" to ADDR_TYPE_TO.toString(), "charset" to "106"))
            }
        }
        writer.endElement("addrs")
        writer.endElement("mms")
    }
}

/**
 * Imports the SMS Backup & Restore (SyncTech) XML format. Streams `<sms>`/`<mms>` elements as they
 * close, so a multi-gigabyte export can be imported without buffering the whole document.
 */
class SmsBackupRestoreXmlImporter : Importer {
    override val id: String = "sms-backup-restore-xml"

    override fun sniff(headerBytes: ByteArray, fileName: String?): Boolean {
        val head = String(headerBytes, Charsets.UTF_8).take(4096)
        if (head.contains("<smses")) return true
        return fileName?.endsWith(".xml", ignoreCase = true) == true && (head.contains("<?xml") || head.contains("<sms"))
    }

    /** [attachmentSink] receives each MMS binary part's base64-decoded bytes as they are read. */
    fun importWithAttachments(input: InputStream, attachmentSink: (bytes: ByteArray) -> Unit): Sequence<ImportedMessage> = sequence {
        val tokenizer = XmlTokenizer(input)
        while (true) {
            val token = tokenizer.next()
            when (token) {
                is XmlToken.EndDocument -> return@sequence
                is XmlToken.StartElement -> when (token.name) {
                    "sms" -> yield(parseSms(token.attributes))
                    "mms" -> {
                        val children = if (token.selfClosing) {
                            MmsChildren(null, emptyList(), "", emptyList())
                        } else {
                            readMmsChildren(tokenizer, attachmentSink)
                        }
                        yield(parseMms(token.attributes, children))
                    }
                }
                else -> Unit
            }
        }
    }

    override fun import(input: InputStream): Sequence<ImportedMessage> = importWithAttachments(input) {}

    private fun parseSms(attrs: Map<String, String>): ImportedMessage {
        val box = MessageBox.fromProviderType(attrs["type"]?.toIntOrNull() ?: 1)
        return ImportedMessage(
            kind = MessageKind.SMS,
            address = attrs["address"].orEmpty(),
            body = attrs["body"].orEmpty(),
            dateMillis = attrs["date"]?.toLongOrNull() ?: 0L,
            dateSentMillis = attrs["date_sent"]?.toLongOrNull(),
            box = box,
            subId = attrs["sub_id"]?.toIntOrNull() ?: NO_SUB_ID,
            read = attrs["read"] != "0",
            locked = attrs["locked"] == "1",
            contactName = attrOrNull(attrs["contact_name"])?.takeUnless { it == "(Unknown)" },
        )
    }

    private data class MmsChildren(
        val fromAddress: String?,
        val toAddresses: List<String>,
        val body: String,
        val attachments: List<ImportedAttachment>,
    )

    /** Reads `<parts>`/`<addrs>` children until the matching `</mms>`. */
    private fun readMmsChildren(tokenizer: XmlTokenizer, attachmentSink: (ByteArray) -> Unit): MmsChildren {
        var fromAddress: String? = null
        val toAddresses = mutableListOf<String>()
        val body = StringBuilder()
        val attachments = mutableListOf<ImportedAttachment>()
        var partCount = 0

        while (true) {
            when (val token = tokenizer.next()) {
                is XmlToken.EndDocument -> return MmsChildren(fromAddress, toAddresses, body.toString(), attachments)
                is XmlToken.EndElement -> if (token.name == "mms") {
                    return MmsChildren(fromAddress, toAddresses, body.toString(), attachments)
                }
                is XmlToken.StartElement -> when (token.name) {
                    "addr" -> {
                        val type = token.attributes["type"]?.toIntOrNull()
                        val address = token.attributes["address"].orEmpty()
                        if (type == ADDR_TYPE_FROM) fromAddress = address
                        else if ((type == ADDR_TYPE_TO || type == ADDR_TYPE_CC) && toAddresses.size < ArchiveLimits.MAX_MMS_ADDRESSES) {
                            toAddresses += address
                        }
                    }
                    "part" -> {
                        partCount++
                        if (partCount > ArchiveLimits.MAX_MMS_PARTS) throw ArchiveLimitException("MMS with more than ${ArchiveLimits.MAX_MMS_PARTS} parts")
                        val ct = token.attributes["ct"].orEmpty()
                        val text = attrOrNull(token.attributes["text"])
                        val data = attrOrNull(token.attributes["data"])
                        when {
                            ct == "text/plain" && text != null -> {
                                if (body.isNotEmpty()) body.append('\n')
                                body.append(text)
                            }
                            data != null -> {
                                // Lenient (MIME) decoding: line breaks are tolerated; garbage drops the part, not the import.
                                val decoded = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull() ?: continue
                                attachmentSink(decoded)
                                attachments += ImportedAttachment(
                                    mimeType = ct.ifEmpty { "application/octet-stream" },
                                    name = attrOrNull(token.attributes["name"]) ?: attrOrNull(token.attributes["cl"]),
                                    bytes = { decoded },
                                )
                            }
                            ct.isNotEmpty() && ct != "application/smil" -> {
                                attachments += ImportedAttachment(
                                    mimeType = ct,
                                    name = attrOrNull(token.attributes["name"]) ?: attrOrNull(token.attributes["cl"]),
                                    text = text,
                                )
                            }
                        }
                    }
                    // "parts", "addrs": containers, nothing to capture on entry.
                }
                is XmlToken.Text -> Unit
            }
        }
    }

    private fun parseMms(attrs: Map<String, String>, children: MmsChildren): ImportedMessage {
        val box = MessageBox.fromProviderType(attrs["msg_box"]?.toIntOrNull() ?: 1)
        return ImportedMessage(
            kind = MessageKind.MMS,
            address = children.fromAddress ?: children.toAddresses.joinToString(" "),
            body = children.body,
            dateMillis = attrs["date"]?.toLongOrNull() ?: 0L,
            box = box,
            subId = attrs["sub_id"]?.toIntOrNull() ?: NO_SUB_ID,
            read = attrs["read"] != "0",
            contactName = attrOrNull(attrs["contact_name"])?.takeUnless { it == "(Unknown)" },
            attachments = children.attachments,
        )
    }
}
