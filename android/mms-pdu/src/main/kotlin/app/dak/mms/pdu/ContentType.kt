package app.dak.mms.pdu

/**
 * A WSP `Content-Type` value: a media type plus the parameters MMS actually uses.
 *
 * @property mimeType lower-case media type, e.g. `application/vnd.wap.multipart.related` or `image/jpeg`.
 * @property charset IANA MIBenum (see [MmsCharset]) from the `charset` parameter.
 * @property type the `type` parameter of multipart/related (media type of the root part), e.g. `application/smil`.
 * @property start the `start` parameter of multipart/related (Content-ID of the root part), e.g. `<smil>`.
 * @property otherParameters any other parameters, keyed by lower-case name.
 */
data class ContentType(
    val mimeType: String,
    val charset: Int? = null,
    val type: String? = null,
    val start: String? = null,
    val startInfo: String? = null,
    val name: String? = null,
    val fileName: String? = null,
    val otherParameters: Map<String, String> = emptyMap(),
) {
    /** True for WSP and MIME multipart types (the body is a WSP multipart). */
    val isMultipart: Boolean
        get() = mimeType.startsWith("multipart/") || mimeType.startsWith("application/vnd.wap.multipart.")

    /** True when the part body is text we can decode with [charset] (text slides and SMIL). */
    val isText: Boolean
        get() = mimeType.startsWith("text/") || mimeType == SMIL

    /** Same type without any parameters. */
    fun withoutParameters(): ContentType = ContentType(mimeType)

    companion object {
        const val MULTIPART_RELATED: String = "application/vnd.wap.multipart.related"
        const val MULTIPART_MIXED: String = "application/vnd.wap.multipart.mixed"
        const val MMS_MESSAGE: String = "application/vnd.wap.mms-message"
        const val SMIL: String = "application/smil"
        const val TEXT_PLAIN: String = "text/plain"

        /** Builds a content type, lower-casing [mimeType]. */
        fun of(mimeType: String, charset: Int? = null): ContentType =
            ContentType(mimeType.trim().lowercase(), charset = charset)

        /** `application/vnd.wap.multipart.related; start=<start>; type=<type>` as used by m-send-req. */
        fun multipartRelated(start: String, type: String = SMIL): ContentType =
            ContentType(MULTIPART_RELATED, start = start, type = type)
    }
}
