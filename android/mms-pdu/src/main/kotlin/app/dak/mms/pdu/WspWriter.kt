package app.dak.mms.pdu

import java.io.ByteArrayOutputStream

/** WSP primitive encoders, the mirror of [WspReader]. Inputs are sanitised (NULs dropped) rather than rejected. */
internal class WspWriter {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun octet(value: Int) {
        out.write(value and 0xFF)
    }

    fun bytes(bytes: ByteArray) {
        out.write(bytes, 0, bytes.size)
    }

    fun write(other: WspWriter) {
        bytes(other.toByteArray())
    }

    /** Short-integer for 0..127. */
    fun shortInteger(value: Int) {
        require(value in 0..127) { "short-integer out of range: $value" }
        octet(value or 0x80)
    }

    /** Long-integer: minimal big-endian octets preceded by their count. */
    fun longInteger(value: Long) {
        require(value >= 0) { "negative long-integer: $value" }
        var n = 1
        while (n < 8 && (value ushr (8 * n)) != 0L) n++
        octet(n)
        for (i in n - 1 downTo 0) octet(((value ushr (8 * i)) and 0xFF).toInt())
    }

    /** Integer-value: short form when it fits, long form otherwise. */
    fun integerValue(value: Long) {
        if (value in 0..127) shortInteger(value.toInt()) else longInteger(value)
    }

    fun uintvar(value: Long) {
        require(value in 0..0xFFFFFFFFL) { "uintvar out of range: $value" }
        var groups = 1
        while (groups < 5 && (value ushr (7 * groups)) != 0L) groups++
        for (i in groups - 1 downTo 0) {
            val seven = ((value ushr (7 * i)) and 0x7F).toInt()
            octet(if (i > 0) seven or 0x80 else seven)
        }
    }

    fun valueLength(length: Int) {
        if (length <= 30) {
            octet(length)
        } else {
            octet(31)
            uintvar(length.toLong())
        }
    }

    /** Writes `Value-length` + [body]. */
    fun lengthPrefixed(body: WspWriter) {
        valueLength(body.size)
        write(body)
    }

    /** Text-string, quoting when the first octet has the high bit set. */
    fun textString(text: String) {
        val b = text.sanitized().toByteArray(Charsets.UTF_8)
        if (b.isNotEmpty() && (b[0].toInt() and 0x80) != 0) octet(0x7F)
        bytes(b)
        octet(0)
    }

    /** Quoted-string: `"` + text + End-of-string. */
    fun quotedString(text: String) {
        octet('"'.code)
        bytes(text.sanitized().removePrefix("\"").toByteArray(Charsets.UTF_8))
        octet(0)
    }

    /**
     * Encoded-string-value: plain Text-string for printable ASCII, otherwise Value-length + UTF-8 charset +
     * Text-string (so non-Latin subjects and names survive).
     */
    fun encodedString(text: String) {
        val clean = text.sanitized()
        if (clean.isNotEmpty() && clean.all { it.code in 32..126 }) {
            textString(clean)
        } else {
            val body = WspWriter()
            body.integerValue(MmsCharset.UTF_8.toLong())
            body.textString(clean)
            lengthPrefixed(body)
        }
    }

    private fun String.sanitized(): String = if (indexOf('\u0000') >= 0) replace("\u0000", "") else this
}
