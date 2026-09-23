package app.dak.mms.pdu

/**
 * One body part of a multipart MMS (a SMIL layout, a text slide, an image, ...).
 *
 * @property contentId the `Content-ID` header as sent, usually with angle brackets, e.g. `<image1>`.
 * @property contentLocation the `Content-Location` header, usually a file name such as `image1.jpg`.
 * @property contentDisposition `attachment`, `inline` or `form-data` when the part carried a Content-Disposition.
 * @property dispositionFileName the `filename` (or `name`) parameter of Content-Disposition.
 */
data class PduPart(
    val contentType: ContentType,
    val data: ByteArray,
    val contentId: String? = null,
    val contentLocation: String? = null,
    val contentDisposition: String? = null,
    val dispositionFileName: String? = null,
) {
    /** Best available file name: disposition filename, then Content-Type filename/name, then Content-Location. */
    val fileName: String?
        get() = dispositionFileName ?: contentType.fileName ?: contentType.name ?: contentLocation

    /**
     * [fileName] made safe to show or to use as a file name: no path components, control or bidi characters,
     * bounded length (see [MmsSafety.safeFileName]). Use this, never the raw values, whenever a name leaves the codec.
     */
    val safeFileName: String?
        get() = MmsSafety.safeFileName(fileName)

    /** Charset MIBenum of this part, if declared. */
    val charset: Int? get() = contentType.charset

    /**
     * Decoded text for text and SMIL parts using the declared charset (UTF-8 when absent or unknown);
     * null for binary parts.
     */
    fun text(): String? = if (contentType.isText) MmsCharset.decode(data, charset) else null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PduPart) return false
        return contentType == other.contentType &&
            data.contentEquals(other.data) &&
            contentId == other.contentId &&
            contentLocation == other.contentLocation &&
            contentDisposition == other.contentDisposition &&
            dispositionFileName == other.dispositionFileName
    }

    override fun hashCode(): Int {
        var result = contentType.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (contentId?.hashCode() ?: 0)
        result = 31 * result + (contentLocation?.hashCode() ?: 0)
        result = 31 * result + (contentDisposition?.hashCode() ?: 0)
        result = 31 * result + (dispositionFileName?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PduPart(contentType=$contentType, bytes=${data.size}, contentId=$contentId, " +
            "contentLocation=$contentLocation, contentDisposition=$contentDisposition, " +
            "dispositionFileName=$dispositionFileName)"

    companion object {
        /** A UTF-8 `text/plain` part. */
        fun text(text: String, contentId: String? = null, contentLocation: String? = null): PduPart = PduPart(
            contentType = ContentType.of(ContentType.TEXT_PLAIN, MmsCharset.UTF_8),
            data = text.toByteArray(Charsets.UTF_8),
            contentId = contentId,
            contentLocation = contentLocation,
        )
    }
}
