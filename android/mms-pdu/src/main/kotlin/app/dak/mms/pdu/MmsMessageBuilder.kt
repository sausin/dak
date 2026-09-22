package app.dak.mms.pdu

import kotlin.random.Random

/**
 * Builds a ready-to-encode m-send-req from text and attachments: a SMIL root part (`<smil>`), the attachments,
 * then a UTF-8 text part, all under `application/vnd.wap.multipart.related; start=<smil>; type=application/smil`.
 */
object MmsMessageBuilder {

    /** One outgoing attachment. [fileName] becomes its Content-Location (sanitised and de-duplicated). */
    class Attachment(val mimeType: String, val fileName: String, val data: ByteArray)

    const val SMIL_CONTENT_ID: String = "<smil>"
    const val SMIL_LOCATION: String = "smil.xml"
    const val TEXT_LOCATION: String = "text_0.txt"

    fun build(
        to: List<String>,
        text: String?,
        attachments: List<Attachment>,
        subject: String? = null,
        transactionId: String = newTransactionId(),
        dateSeconds: Long? = null,
        requestDeliveryReport: Boolean = false,
        requestReadReport: Boolean = false,
    ): SendReq {
        val usedNames = hashSetOf(SMIL_LOCATION, TEXT_LOCATION)
        val mediaParts = attachments.mapIndexed { index, a ->
            val location = uniqueName(sanitizeFileName(a.fileName, a.mimeType, index), usedNames)
            PduPart(
                contentType = ContentType(
                    mimeType = a.mimeType.trim().lowercase(),
                    name = location,
                ),
                data = a.data,
                contentId = "<$location>",
                contentLocation = location,
            )
        }
        val textPart = text?.takeIf { it.isNotEmpty() }?.let {
            PduPart(
                contentType = ContentType(ContentType.TEXT_PLAIN, charset = MmsCharset.UTF_8, name = TEXT_LOCATION),
                data = it.toByteArray(Charsets.UTF_8),
                contentId = "<text_0>",
                contentLocation = TEXT_LOCATION,
            )
        }
        val content = mediaParts + listOfNotNull(textPart)
        val smil = Smil.build(
            content.map { part -> Smil.Item(part.contentLocation.orEmpty(), Smil.Kind.forMimeType(part.contentType.mimeType)) },
        )
        val smilPart = PduPart(
            contentType = ContentType(ContentType.SMIL, charset = MmsCharset.UTF_8, name = SMIL_LOCATION),
            data = smil.toByteArray(Charsets.UTF_8),
            contentId = SMIL_CONTENT_ID,
            contentLocation = SMIL_LOCATION,
        )
        return SendReq(
            transactionId = transactionId,
            to = to,
            contentType = ContentType.multipartRelated(start = SMIL_CONTENT_ID, type = ContentType.SMIL),
            parts = listOf(smilPart) + content,
            subject = subject?.takeIf { it.isNotBlank() },
            dateSeconds = dateSeconds,
            deliveryReport = requestDeliveryReport,
            readReport = requestReadReport,
        )
    }

    /** A transaction id unique enough for an MMSC (`T` + time + random, printable ASCII). */
    fun newTransactionId(random: Random = Random.Default, nowMillis: Long = System.currentTimeMillis()): String =
        "T" + nowMillis.toString(16) + random.nextLong().toULong().toString(16)

    /** Keeps `[A-Za-z0-9._-]`, replaces anything else with `_`, and guarantees a non-empty name with an extension. */
    internal fun sanitizeFileName(fileName: String, mimeType: String, index: Int): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('.')
            .take(64)
        if (base.isNotEmpty() && base.contains('.')) return base
        val ext = extensionFor(mimeType)
        val stem = base.ifEmpty { "part_$index" }
        return if (ext != null) "$stem.$ext" else stem
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name.lowercase())) return name
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "${stem}_$n" else "${stem}_$n.$ext"
            if (used.add(candidate.lowercase())) return candidate
            n++
        }
    }

    private fun extensionFor(mimeType: String): String? = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "audio/amr" -> "amr"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/aac" -> "m4a"
        "text/x-vcard", "text/vcard" -> "vcf"
        "text/plain" -> "txt"
        else -> null
    }
}
