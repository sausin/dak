package app.dak.mms.pdu

import java.nio.charset.Charset

/** WSP well-known content types (WAP-230 Appendix A, Table 40), indexed by assigned number. */
internal object WellKnownMedia {
    private val TABLE: Array<String> = arrayOf(
        "*/*", // 0x00
        "text/*",
        "text/html",
        "text/plain",
        "text/x-hdml",
        "text/x-ttml",
        "text/x-vcalendar",
        "text/x-vcard",
        "text/vnd.wap.wml",
        "text/vnd.wap.wmlscript",
        "text/vnd.wap.wta-event", // 0x0A
        "multipart/*",
        "multipart/mixed",
        "multipart/form-data",
        "multipart/byterantes",
        "multipart/alternative",
        "application/*", // 0x10
        "application/java-vm",
        "application/x-www-form-urlencoded",
        "application/x-hdmlc",
        "application/vnd.wap.wmlc",
        "application/vnd.wap.wmlscriptc",
        "application/vnd.wap.wta-eventc",
        "application/vnd.wap.uaprof",
        "application/vnd.wap.wtls-ca-certificate",
        "application/vnd.wap.wtls-user-certificate",
        "application/x-x509-ca-cert", // 0x1A
        "application/x-x509-user-cert",
        "image/*",
        "image/gif",
        "image/jpeg",
        "image/tiff",
        "image/png", // 0x20
        "image/vnd.wap.wbmp",
        "application/vnd.wap.multipart.*",
        "application/vnd.wap.multipart.mixed",
        "application/vnd.wap.multipart.form-data",
        "application/vnd.wap.multipart.byteranges",
        "application/vnd.wap.multipart.alternative",
        "application/xml",
        "text/xml",
        "application/vnd.wap.wbxml",
        "application/x-x968-cross-cert", // 0x2A
        "application/x-x968-ca-cert",
        "application/x-x968-user-cert",
        "text/vnd.wap.si",
        "application/vnd.wap.sic",
        "text/vnd.wap.sl",
        "application/vnd.wap.slc", // 0x30
        "text/vnd.wap.co",
        "application/vnd.wap.coc",
        "application/vnd.wap.multipart.related",
        "application/vnd.wap.sia",
        "text/vnd.wap.connectivity-xml",
        "application/vnd.wap.connectivity-wbxml",
        "application/pkcs7-mime",
        "application/vnd.wap.hashed-certificate",
        "application/vnd.wap.signed-certificate",
        "application/vnd.wap.cert-response", // 0x3A
        "application/xhtml+xml",
        "application/wml+xml",
        "text/css",
        "application/vnd.wap.mms-message",
        "application/vnd.wap.rollover-certificate",
        "application/vnd.wap.locc+wbxml", // 0x40
        "application/vnd.wap.loc+xml",
        "application/vnd.syncml.dm+wbxml",
        "application/vnd.syncml.dm+xml",
        "application/vnd.syncml.notification",
        "application/vnd.wap.xhtml+xml",
        "application/vnd.wv.csp.cir",
        "application/vnd.oma.dd+xml",
        "application/vnd.oma.drm.message",
        "application/vnd.oma.drm.content",
        "application/vnd.oma.drm.rights+xml", // 0x4A
        "application/vnd.oma.drm.rights+wbxml",
    )

    private val BY_NAME: Map<String, Int> = TABLE.withIndex().associate { (i, name) -> name to i }

    const val MULTIPART_MIXED = 0x23
    const val MULTIPART_RELATED = 0x33

    /** Name for an assigned number, or null when the number is not in our table. */
    fun name(code: Long): String? = if (code >= 0 && code < TABLE.size) TABLE[code.toInt()] else null

    /** Assigned number for a (lower-case) MIME type, or null when it must be sent as text. */
    fun code(mimeType: String): Int? = BY_NAME[mimeType.lowercase()]
}

/**
 * IANA MIBenum charset numbers as used by WSP/MMS (`Well-known-charset`), mapped to JVM charsets.
 * Unknown or unsupported charsets decode as UTF-8 (a superset of the US-ASCII default), never throwing.
 */
object MmsCharset {
    /** "Any-charset" (`*`); treated as UTF-8. */
    const val ANY: Int = 0
    const val US_ASCII: Int = 3
    const val ISO_8859_1: Int = 4
    const val SHIFT_JIS: Int = 17
    const val EUC_KR: Int = 38
    const val UTF_8: Int = 106
    const val GBK: Int = 113
    const val GB18030: Int = 114
    const val UCS_2: Int = 1000
    const val UTF_16BE: Int = 1013
    const val UTF_16LE: Int = 1014
    const val UTF_16: Int = 1015
    const val GB2312: Int = 2025
    const val BIG5: Int = 2026

    private val NAMES: Map<Int, String> = mapOf(
        US_ASCII to "US-ASCII",
        ISO_8859_1 to "ISO-8859-1",
        5 to "ISO-8859-2",
        6 to "ISO-8859-3",
        7 to "ISO-8859-4",
        8 to "ISO-8859-5",
        9 to "ISO-8859-6",
        10 to "ISO-8859-7",
        11 to "ISO-8859-8",
        12 to "ISO-8859-9",
        SHIFT_JIS to "Shift_JIS",
        18 to "EUC-JP",
        EUC_KR to "EUC-KR",
        39 to "ISO-2022-JP",
        UTF_8 to "UTF-8",
        GBK to "GBK",
        GB18030 to "GB18030",
        UCS_2 to "UTF-16BE",
        UTF_16BE to "UTF-16BE",
        UTF_16LE to "UTF-16LE",
        UTF_16 to "UTF-16",
        GB2312 to "GB2312",
        BIG5 to "Big5",
        2252 to "windows-1252",
    )

    private val BY_NAME: Map<String, Int> = buildMap {
        NAMES.forEach { (mib, name) -> putIfAbsent(name.lowercase(), mib) }
        put("utf-16be", UTF_16BE)
        put("utf8", UTF_8)
        put("ascii", US_ASCII)
        put("us-ascii", US_ASCII)
        put("latin1", ISO_8859_1)
        put("iso-10646-ucs-2", UCS_2)
        put("ucs-2", UCS_2)
        put("*", ANY)
    }

    /** JVM charset for a MIBenum; UTF-8 when unknown or unavailable on this runtime. */
    fun toCharset(mib: Int?): Charset {
        val name = mib?.let { NAMES[it] } ?: return Charsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    /** MIBenum for a charset name such as "utf-8", or null when unknown. */
    fun fromName(name: String): Int? = BY_NAME[name.trim().lowercase()]

    /** True for the UTF-16 family, whose text legitimately contains 0x00 octets. */
    fun isUtf16(mib: Int?): Boolean = mib == UCS_2 || mib == UTF_16 || mib == UTF_16BE || mib == UTF_16LE

    /** Decodes [bytes] leniently (malformed sequences become U+FFFD). */
    fun decode(bytes: ByteArray, mib: Int?): String = String(bytes, toCharset(mib))
}
