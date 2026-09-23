package app.dak.mms.pdu

/** Internal decode failure; converted to [PduError] at the public boundary. */
internal class PduFormatException(
    val offset: Int,
    detail: String,
    val truncated: Boolean = false,
) : Exception(detail)

/**
 * Bounds-checked cursor over `data[start, end)` with WSP (WAP-230 §8.4) primitive decoders.
 * Every read validates the remaining length first, so no read can escape the window or allocate more than it.
 */
internal class WspReader(
    private val data: ByteArray,
    start: Int = 0,
    private val end: Int = data.size,
) {
    var position: Int = start
        private set

    val remaining: Int get() = end - position

    fun hasMore(): Boolean = position < end

    fun peek(): Int {
        require(1)
        return data[position].toInt() and 0xFF
    }

    fun readOctet(): Int {
        require(1)
        return data[position++].toInt() and 0xFF
    }

    fun readBytes(count: Int): ByteArray {
        require(count)
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun readRemaining(): ByteArray = readBytes(remaining)

    fun skip(count: Int) {
        require(count)
        position += count
    }

    /** A reader over the next [length] octets; this reader advances past them. */
    fun slice(length: Int): WspReader {
        require(length)
        val sub = WspReader(data, position, position + length)
        position += length
        return sub
    }

    fun malformed(detail: String): PduFormatException = PduFormatException(position, detail)

    private fun require(count: Int) {
        if (count < 0 || count > end - position) {
            throw PduFormatException(position, "needed $count octet(s), ${end - position} left", truncated = true)
        }
    }

    // --- Integers -------------------------------------------------------------------------------------------

    /** Uintvar: 7 bits per octet, high bit = continuation; at most 5 octets (32 bits). */
    fun readUintvar(): Long {
        var value = 0L
        repeat(5) {
            val b = readOctet()
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) return value
        }
        throw malformed("uintvar longer than 5 octets")
    }

    fun readUintvarInt(): Int {
        val v = readUintvar()
        if (v > Int.MAX_VALUE) throw malformed("uintvar $v too large")
        return v.toInt()
    }

    /** Short-integer: one octet with the high bit set. */
    fun readShortInteger(): Int {
        val b = readOctet()
        if (b and 0x80 == 0) throw PduFormatException(position - 1, "expected short-integer")
        return b and 0x7F
    }

    /** Long-integer: Short-length (1..30) followed by a big-endian multi-octet integer (must fit in 63 bits). */
    fun readLongInteger(): Long {
        val length = readOctet()
        if (length < 1 || length > 30) throw PduFormatException(position - 1, "bad long-integer length $length")
        var value = 0L
        for (i in 0 until length) {
            val b = readOctet()
            if (length - i > 8 && b != 0) throw malformed("long-integer overflow")
            value = (value shl 8) or b.toLong()
        }
        if (value < 0) throw malformed("long-integer overflow")
        return value
    }

    /** Integer-value: Short-integer | Long-integer. */
    fun readIntegerValue(): Long {
        val b = peek()
        return if (b and 0x80 != 0) {
            position++
            (b and 0x7F).toLong()
        } else {
            readLongInteger()
        }
    }

    /** True when the next octet starts an Integer-value (short form, or a long form's length 1..30). */
    fun nextIsIntegerValue(): Boolean {
        val b = peek()
        return b >= 0x80 || b in 1..30
    }

    /** Value-length: Short-length (0..30) | Length-quote (31) Uintvar. */
    fun readValueLength(): Int {
        val b = readOctet()
        return when {
            b <= 30 -> b
            b == 31 -> readUintvarInt()
            else -> throw PduFormatException(position - 1, "expected value-length, got 0x%02X".format(b))
        }
    }

    // --- Strings --------------------------------------------------------------------------------------------

    /** Raw octets of a Text-string (optional leading Quote 0x7F, then octets up to End-of-string 0x00). */
    fun readTextBytes(): ByteArray {
        if (peek() == QUOTE) position++
        val startPos = position
        while (true) {
            if (position >= end) throw PduFormatException(startPos, "unterminated text-string", truncated = true)
            if (data[position].toInt() == 0) break
            position++
        }
        val out = data.copyOfRange(startPos, position)
        position++ // terminator
        return out
    }

    /** Text-string decoded as UTF-8 (a lenient superset of the US-ASCII the spec requires). */
    fun readTextString(): String = String(readTextBytes(), Charsets.UTF_8)

    /** Text-value: No-value (0) | Token-text | Quoted-string (leading `"` removed). */
    fun readTextValue(): String {
        if (peek() == 0) {
            position++
            return ""
        }
        return readTextString().removeQuotes()
    }

    /** Encoded-string-value: Text-string | Value-length Char-set Text-string. */
    fun readEncodedString(): String {
        val b = peek()
        if (b > 31) return readTextString()
        val value = slice(readValueLength())
        val charset = if (value.hasMore() && value.nextIsIntegerValue()) value.readIntegerValue().toInt() else null
        var bytes = value.readRemaining()
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == QUOTE) bytes = bytes.copyOfRange(1, bytes.size)
        bytes = stripTerminator(bytes, charset)
        return MmsCharset.decode(bytes, charset)
    }

    // --- Generic --------------------------------------------------------------------------------------------

    /**
     * Skips one field value using the generic WSP rule: 0..30 short length, 31 uintvar length, 32..127 text,
     * 128..255 single octet. Used for headers and parameters we do not model.
     */
    fun skipValue() {
        val b = peek()
        when {
            b <= 30 -> {
                position++
                skip(b)
            }
            b == 31 -> {
                position++
                skip(readUintvarInt())
            }
            b < 0x80 -> readTextBytes()
            else -> position++
        }
    }

    private companion object {
        const val QUOTE = 0x7F

        fun String.removeQuotes(): String {
            var s = this
            if (s.startsWith("\"")) s = s.substring(1)
            if (s.endsWith("\"")) s = s.substring(0, s.length - 1)
            return s
        }

        fun stripTerminator(bytes: ByteArray, charset: Int?): ByteArray {
            if (bytes.isEmpty()) return bytes
            if (MmsCharset.isUtf16(charset)) {
                // UTF-16 text has an even length; a single trailing 0x00 is the End-of-string octet.
                return if (bytes.size % 2 == 1 && bytes.last().toInt() == 0) bytes.copyOf(bytes.size - 1) else bytes
            }
            var n = bytes.size
            while (n > 0 && bytes[n - 1].toInt() == 0) n--
            return if (n == bytes.size) bytes else bytes.copyOf(n)
        }
    }
}
