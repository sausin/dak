package app.dak.mms.pdu

/** WSP well-known parameter tokens (WAP-230 Table 38), without the high bit. */
internal object Param {
    const val Q = 0x00
    const val CHARSET = 0x01
    const val LEVEL = 0x02
    const val TYPE_INT = 0x03
    const val NAME_1_1 = 0x05
    const val FILENAME_1_1 = 0x06
    const val DIFFERENCES = 0x07
    const val PADDING = 0x08
    const val TYPE = 0x09
    const val START_1_2 = 0x0A
    const val START_INFO_1_2 = 0x0B
    const val SECURE = 0x10
    const val NAME = 0x17
    const val FILENAME = 0x18
    const val START = 0x19
    const val START_INFO = 0x1A
}

/** Accumulates parameters while decoding a Content-Type or Content-Disposition value. */
internal class ParamsBuilder {
    var charset: Int? = null
    var type: String? = null
    var start: String? = null
    var startInfo: String? = null
    var name: String? = null
    var fileName: String? = null
    val other: MutableMap<String, String> = linkedMapOf()

    fun build(mimeType: String): ContentType = ContentType(
        mimeType = mimeType,
        charset = charset,
        type = type,
        start = start,
        startInfo = startInfo,
        name = name,
        fileName = fileName,
        otherParameters = other.toMap(),
    )

    /** Applies an untyped (textual) parameter, mapping the names we model onto typed fields. */
    fun putUntyped(rawName: String, value: String) {
        when (val key = rawName.trim().lowercase()) {
            "charset" -> charset = MmsCharset.fromName(value) ?: value.toIntOrNull()
            "type" -> type = value.lowercase()
            "start" -> start = value
            "start-info" -> startInfo = value
            "name" -> name = value
            "filename" -> fileName = value
            else -> other[key] = value
        }
    }
}

internal object ContentTypeCodec {

    // --- Decoding -------------------------------------------------------------------------------------------

    /** Content-type-value: Constrained-media | Content-general-form. */
    fun read(r: WspReader): ContentType {
        val b = r.peek()
        if (b >= 0x80) {
            r.readOctet()
            return ContentType(mediaName((b and 0x7F).toLong()))
        }
        if (b >= 32) return ContentType(r.readTextString().trim().lowercase())
        val body = r.slice(r.readValueLength())
        val mime = readMedia(body)
        val params = ParamsBuilder()
        readParameters(body, params)
        return params.build(mime)
    }

    /** Media-type inside the general form: Well-known-media (Integer-value) | Extension-media (Text-string). */
    private fun readMedia(r: WspReader): String {
        if (!r.hasMore()) return "application/octet-stream"
        return if (r.nextIsIntegerValue()) mediaName(r.readIntegerValue()) else r.readTextString().trim().lowercase()
    }

    private fun mediaName(code: Long): String = WellKnownMedia.name(code) ?: "application/octet-stream"

    /** Reads parameters until [r] is exhausted. Unknown typed parameters are skipped. */
    fun readParameters(r: WspReader, into: ParamsBuilder) {
        while (r.hasMore()) {
            if (r.nextIsIntegerValue()) {
                readTypedParameter(r, r.readIntegerValue().toInt(), into)
            } else {
                val name = r.readTextString()
                val value = if (!r.hasMore()) "" else if (r.nextIsIntegerValue()) {
                    r.readIntegerValue().toString()
                } else {
                    r.readTextValue()
                }
                into.putUntyped(name, value)
            }
        }
    }

    private fun readTypedParameter(r: WspReader, token: Int, into: ParamsBuilder) {
        when (token) {
            Param.Q -> r.readUintvar()
            Param.CHARSET -> into.charset = readCharset(r)
            Param.LEVEL -> if (r.peek() >= 0x80) r.readOctet() else r.readTextString()
            Param.TYPE_INT -> into.type = mediaName(r.readIntegerValue())
            Param.TYPE -> into.type = if (r.peek() >= 0x80) {
                mediaName((r.readOctet() and 0x7F).toLong())
            } else {
                r.readTextString().trim().lowercase()
            }
            Param.NAME_1_1, Param.NAME -> into.name = r.readTextValue()
            Param.FILENAME_1_1, Param.FILENAME -> into.fileName = r.readTextValue()
            Param.START_1_2, Param.START -> into.start = r.readTextValue()
            Param.START_INFO_1_2, Param.START_INFO -> into.startInfo = r.readTextValue()
            Param.SECURE -> if (r.peek() == 0) r.readOctet() else r.skipValue()
            else -> r.skipValue()
        }
    }

    /** Well-known-charset: Any-charset (128) | Integer-value; some encoders send the name as text instead. */
    private fun readCharset(r: WspReader): Int? {
        val b = r.peek()
        if (b in 32..127) return MmsCharset.fromName(r.readTextString())
        if (b == 0) {
            r.readOctet()
            return null
        }
        return r.readIntegerValue().toInt()
    }

    /**
     * Content-Disposition value: Value-length Disposition *(Parameter). Returns the disposition name and fills
     * [into] (filename / name). Also accepts a bare octet or token without a length, which some encoders send.
     */
    fun readDisposition(r: WspReader, into: ParamsBuilder): String? {
        val b = r.peek()
        if (b >= 0x80) return dispositionName(r.readOctet())
        if (b >= 32) return r.readTextString().lowercase()
        val body = r.slice(r.readValueLength())
        if (!body.hasMore()) return null
        val disposition = if (body.peek() >= 0x80) dispositionName(body.readOctet()) else body.readTextString().lowercase()
        readParameters(body, into)
        return disposition
    }

    private fun dispositionName(octet: Int): String? = when (octet) {
        0x80 -> "form-data"
        0x81 -> "attachment"
        0x82 -> "inline"
        else -> null
    }

    // --- Encoding -------------------------------------------------------------------------------------------

    fun write(w: WspWriter, type: ContentType) {
        val mime = type.mimeType.lowercase()
        val code = WellKnownMedia.code(mime)
        val params = WspWriter()
        type.charset?.let {
            params.shortInteger(Param.CHARSET)
            params.integerValue(it.toLong())
        }
        type.start?.let {
            params.shortInteger(Param.START_1_2)
            params.textString(it)
        }
        type.type?.let {
            params.shortInteger(Param.TYPE)
            val typeCode = WellKnownMedia.code(it)
            if (typeCode != null) params.shortInteger(typeCode) else params.textString(it)
        }
        type.startInfo?.let {
            params.shortInteger(Param.START_INFO_1_2)
            params.textString(it)
        }
        type.name?.let {
            params.shortInteger(Param.NAME_1_1)
            params.textString(it)
        }
        type.fileName?.let {
            params.shortInteger(Param.FILENAME_1_1)
            params.textString(it)
        }
        for ((name, value) in type.otherParameters) {
            params.textString(name)
            params.quotedString(value)
        }

        if (params.size == 0) {
            if (code != null) w.shortInteger(code) else w.textString(mime)
            return
        }
        val body = WspWriter()
        if (code != null) body.shortInteger(code) else body.textString(mime)
        body.write(params)
        w.lengthPrefixed(body)
    }
}
