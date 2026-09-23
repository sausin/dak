package app.dak.mms.pdu

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WspPrimitivesTest {

    private fun writer(block: WspWriter.() -> Unit): ByteArray = WspWriter().apply(block).toByteArray()

    @Test
    fun uintvarRoundTripsAtBoundaries() {
        for (v in listOf(0L, 1L, 127L, 128L, 16383L, 16384L, 0x0FFFFFFFL, 0xFFFFFFFFL)) {
            val encoded = writer { uintvar(v) }
            assertEquals(v, WspReader(encoded).readUintvar(), "value $v")
        }
        assertContentEquals(bytes(0x81, 0x00), writer { uintvar(128) })
        assertContentEquals(bytes(0x7F), writer { uintvar(127) })
    }

    @Test
    fun uintvarLongerThanFiveOctetsIsMalformed() {
        val e = assertFailsWith<PduFormatException> { WspReader(bytes(0x81, 0x81, 0x81, 0x81, 0x81, 0x01)).readUintvar() }
        assertTrue(!e.truncated)
    }

    @Test
    fun longIntegerIsMinimalBigEndian() {
        assertContentEquals(bytes(0x01, 0x00), writer { longInteger(0) })
        assertContentEquals(bytes(0x02, 0x01, 0xF4), writer { longInteger(500) })
        assertContentEquals(bytes(0x04, 0x5F, 0x5E, 0x10, 0x00), writer { longInteger(0x5F5E1000) })
        assertEquals(0x5F5E1000L, WspReader(bytes(0x04, 0x5F, 0x5E, 0x10, 0x00)).readLongInteger())
    }

    @Test
    fun longIntegerToleratesLeadingZeroPaddingButRejectsOverflow() {
        assertEquals(5L, WspReader(bytes(0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5)).readLongInteger())
        assertFailsWith<PduFormatException> { WspReader(bytes(0x09, 1, 0, 0, 0, 0, 0, 0, 0, 0)).readLongInteger() }
        assertFailsWith<PduFormatException> { WspReader(bytes(0x00)).readLongInteger() }
    }

    @Test
    fun integerValueUsesShortFormUpTo127() {
        assertContentEquals(bytes(0xEA), writer { integerValue(106) })
        assertContentEquals(bytes(0x02, 0x03, 0xF7), writer { integerValue(1015) })
        assertEquals(106L, WspReader(bytes(0xEA)).readIntegerValue())
        assertEquals(1015L, WspReader(bytes(0x02, 0x03, 0xF7)).readIntegerValue())
    }

    @Test
    fun valueLengthSwitchesToLengthQuoteAbove30() {
        assertContentEquals(bytes(30), writer { valueLength(30) })
        assertContentEquals(bytes(31, 31), writer { valueLength(31) })
        assertContentEquals(bytes(31, 0x81, 0x00), writer { valueLength(128) })
        assertEquals(128, WspReader(bytes(31, 0x81, 0x00)).readValueLength())
        assertFailsWith<PduFormatException> { WspReader(bytes(0x20)).readValueLength() }
    }

    @Test
    fun textStringQuotesHighFirstOctetAndDropsNul() {
        assertContentEquals(bytes("abc", 0), writer { textString("abc") })
        val encoded = writer { textString("é") }
        assertEquals(0x7F, encoded[0].toInt())
        assertEquals("é", WspReader(encoded).readTextString())
        assertContentEquals(bytes("ab", 0), writer { textString("a\u0000b") })
    }

    @Test
    fun unterminatedTextIsTruncated() {
        val e = assertFailsWith<PduFormatException> { WspReader(bytes("abc")).readTextString() }
        assertTrue(e.truncated)
    }

    @Test
    fun encodedStringAsciiIsPlainAndUnicodeCarriesUtf8Charset() {
        assertContentEquals(text("Hello"), writer { encodedString("Hello") })
        val unicode = writer { encodedString("नमस्ते") }
        assertTrue(unicode[0] < 31, "value-length form")
        assertEquals(0xEA, unicode[1].toInt() and 0xFF)
        assertEquals("नमस्ते", WspReader(unicode).readEncodedString())
        // Empty strings must not look like a zero value-length.
        assertEquals("", WspReader(writer { encodedString("") }).readEncodedString())
    }

    @Test
    fun encodedStringDecodesLatin1AndUtf16() {
        assertEquals("café", WspReader(bytes(0x06, 0x84, "caf", 0xE9, 0x00)).readEncodedString())
        // UTF-16 (MIBenum 1015) with a BOM: the embedded 0x00 octets must not terminate the string.
        val utf16 = bytes(0xFE, 0xFF, 0x00, 'H'.code, 0x00, 'i'.code)
        val value = bytes(0x02, 0x03, 0xF7, 0x7F, utf16, 0x00)
        assertEquals("Hi", WspReader(bytes(value.size, value)).readEncodedString())
    }

    @Test
    fun skipValueFollowsGenericRules() {
        val r = WspReader(bytes(0x02, 0xAA, 0xBB, "tok", 0, 0x85, 31, 0x01, 0xCC, 0x42))
        r.skipValue()
        r.skipValue()
        r.skipValue()
        r.skipValue()
        assertEquals(0x42, r.readOctet())
    }

    @Test
    fun sliceCannotReadPastItsWindow() {
        val r = WspReader(bytes(1, 2, 3, 4))
        val s = r.slice(2)
        assertEquals(1, s.readOctet())
        assertEquals(2, s.readOctet())
        assertFailsWith<PduFormatException> { s.readOctet() }
        assertEquals(3, r.readOctet())
    }
}
