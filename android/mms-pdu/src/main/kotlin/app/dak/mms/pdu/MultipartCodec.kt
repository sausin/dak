package app.dak.mms.pdu

/** WSP multipart body (WAP-230 §8.5): uintvar count, then per part HeadersLen, DataLen, ContentType+Headers, Data. */
internal object MultipartCodec {
    private const val MAX_NESTING = 3

    /** Reads a multipart body; nested multiparts are flattened and all parts count towards [MmsLimits.MAX_PARTS]. */
    fun read(r: WspReader): List<PduPart> {
        val parts = ArrayList<PduPart>()
        read(r, 0, parts)
        return parts
    }

    private fun read(r: WspReader, depth: Int, parts: MutableList<PduPart>) {
        val count = r.readUintvarInt()
        // Each entry needs at least 3 octets (two uintvars and a content type): reject absurd counts up front.
        if (count > r.remaining / 3 + 1) throw r.malformed("multipart claims $count parts in ${r.remaining} octets")
        if (parts.size + count > MmsLimits.MAX_PARTS) throw r.malformed("more than ${MmsLimits.MAX_PARTS} parts")
        repeat(count) {
            val headersLength = r.readUintvarInt()
            val dataLength = r.readUintvarInt()
            val headers = r.slice(headersLength)
            val contentType = ContentTypeCodec.read(headers)
            val part = readPartHeaders(headers, contentType, r.readBytes(dataLength))
            if (part.contentType.isMultipart && depth < MAX_NESTING) {
                // Nested multipart (e.g. multipart/alternative inside related): flatten its children when valid.
                val nested = ArrayList<PduPart>()
                val valid = try {
                    read(WspReader(part.data), depth + 1, nested)
                    true
                } catch (e: PduFormatException) {
                    false
                }
                if (valid) {
                    if (parts.size + nested.size > MmsLimits.MAX_PARTS) throw r.malformed("more than ${MmsLimits.MAX_PARTS} parts")
                    parts.addAll(nested)
                } else {
                    parts.add(part)
                }
            } else {
                parts.add(part)
            }
        }
    }

    private fun readPartHeaders(r: WspReader, contentType: ContentType, data: ByteArray): PduPart {
        var contentId: String? = null
        var contentLocation: String? = null
        var disposition: String? = null
        val dispositionParams = ParamsBuilder()
        loop@ while (r.hasMore()) {
            val b = r.peek()
            when {
                b >= 0x80 -> {
                    r.readOctet()
                    when (b and 0x7F) {
                        PartField.CONTENT_LOCATION -> contentLocation = r.readTextValue()
                        PartField.CONTENT_ID -> contentId = r.readTextValue()
                        PartField.CONTENT_DISPOSITION, PartField.CONTENT_DISPOSITION_1_4 ->
                            disposition = ContentTypeCodec.readDisposition(r, dispositionParams)
                        else -> r.skipValue()
                    }
                }
                b in 32..126 -> {
                    // Application-header: Token-text Application-specific-value (Text-string).
                    val name = r.readTextString().trim().lowercase()
                    val value = if (r.hasMore()) r.readTextValue() else ""
                    when (name) {
                        "content-id" -> contentId = value
                        "content-location" -> contentLocation = value
                        "content-disposition" -> disposition = value.substringBefore(';').trim().lowercase()
                    }
                }
                // Shift sequences / stray octets: we cannot resynchronise, so keep what we have.
                else -> break@loop
            }
        }
        return PduPart(
            contentType = contentType,
            data = data,
            contentId = contentId?.takeIf { it.isNotEmpty() },
            contentLocation = contentLocation?.takeIf { it.isNotEmpty() },
            contentDisposition = disposition,
            dispositionFileName = dispositionParams.fileName ?: dispositionParams.name,
        )
    }

    fun write(w: WspWriter, parts: List<PduPart>) {
        w.uintvar(parts.size.toLong())
        for (part in parts) {
            val headers = WspWriter()
            ContentTypeCodec.write(headers, part.contentType)
            part.contentLocation?.let {
                headers.shortInteger(PartField.CONTENT_LOCATION)
                headers.textString(it)
            }
            part.contentId?.let {
                headers.shortInteger(PartField.CONTENT_ID)
                headers.quotedString(it)
            }
            val disposition = part.contentDisposition
            if (disposition != null) {
                val body = WspWriter()
                when (disposition.lowercase()) {
                    "form-data" -> body.octet(0x80)
                    "attachment" -> body.octet(0x81)
                    "inline" -> body.octet(0x82)
                    else -> body.textString(disposition)
                }
                part.dispositionFileName?.let {
                    body.shortInteger(Param.FILENAME_1_1)
                    body.textString(it)
                }
                headers.shortInteger(PartField.CONTENT_DISPOSITION)
                headers.lengthPrefixed(body)
            }
            w.uintvar(headers.size.toLong())
            w.uintvar(part.data.size.toLong())
            w.write(headers)
            w.bytes(part.data)
        }
    }
}
