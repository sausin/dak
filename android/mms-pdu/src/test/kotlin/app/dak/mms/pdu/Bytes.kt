package app.dak.mms.pdu

import java.io.ByteArrayOutputStream

/**
 * Tiny DSL for hand-building PDU fixtures: ints are octets, strings are raw UTF-8 bytes (add "\u0000" yourself),
 * byte arrays are appended verbatim.
 */
internal fun bytes(vararg items: Any): ByteArray {
    val out = ByteArrayOutputStream()
    for (item in items) {
        when (item) {
            is Int -> out.write(item and 0xFF)
            is String -> out.write(item.toByteArray(Charsets.UTF_8))
            is ByteArray -> out.write(item)
            else -> error("unsupported fixture item $item")
        }
    }
    return out.toByteArray()
}

/** A NUL-terminated text string. */
internal fun text(s: String): ByteArray = bytes(s, 0)

/** Short value-length (must be <= 30) followed by [body]. */
internal fun lp(body: ByteArray): ByteArray {
    require(body.size <= 30) { "use lpLong for ${body.size} octets" }
    return bytes(body.size, body)
}

/** Uintvar encoding, built independently of [WspWriter] so fixtures do not trust the code under test. */
internal fun uintvar(value: Int): ByteArray {
    val groups = ArrayList<Int>()
    var v = value
    do {
        groups.add(v and 0x7F)
        v = v ushr 7
    } while (v != 0)
    groups.reverse()
    return ByteArray(groups.size) { i -> (if (i < groups.size - 1) groups[i] or 0x80 else groups[i]).toByte() }
}

/** One WSP multipart entry: HeadersLen, DataLen, (content type + headers), data. */
internal fun entry(contentTypeAndHeaders: ByteArray, data: ByteArray): ByteArray =
    bytes(uintvar(contentTypeAndHeaders.size), uintvar(data.size), contentTypeAndHeaders, data)

internal fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
