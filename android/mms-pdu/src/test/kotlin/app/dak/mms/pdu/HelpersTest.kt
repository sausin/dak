package app.dak.mms.pdu

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HelpersTest {

    @Test
    fun addressWireFormat() {
        assertEquals("+15551234567/TYPE=PLMN", MmsAddress.toWire("+1 (555) 123-4567"))
        assertEquals("user@example.com", MmsAddress.toWire("user@example.com"))
        assertEquals("HDFCBK", MmsAddress.toWire("HDFCBK"))
        assertEquals("12345/TYPE=PLMN", MmsAddress.toWire("12345"))
        assertEquals("+1555/TYPE=PLMN", MmsAddress.toWire("+1555/TYPE=PLMN"))
        assertEquals("+1555", MmsAddress.fromWire(" +1555/type=plmn "))
        assertEquals("10.0.0.1", MmsAddress.fromWire("10.0.0.1/TYPE=IPv4"))
        assertTrue(MmsAddress.isPhoneNumber("*123#"))
        assertFalse(MmsAddress.isPhoneNumber("+"))
    }

    @Test
    fun charsetsResolveAndFallBack() {
        assertEquals(Charsets.UTF_8, MmsCharset.toCharset(MmsCharset.UTF_8))
        assertEquals(Charsets.ISO_8859_1, MmsCharset.toCharset(MmsCharset.ISO_8859_1))
        assertEquals(Charsets.UTF_8, MmsCharset.toCharset(null))
        assertEquals(Charsets.UTF_8, MmsCharset.toCharset(999_999))
        assertEquals(MmsCharset.UTF_8, MmsCharset.fromName("UTF-8"))
        assertEquals(MmsCharset.UTF_16BE, MmsCharset.fromName("utf-16be"))
        assertEquals(null, MmsCharset.fromName("klingon"))
    }

    @Test
    fun smilEscapesAndOrdersTextLast() {
        val smil = Smil.build(listOf(Smil.Item("t.txt", Smil.Kind.TEXT), Smil.Item("a&b\".png", Smil.Kind.IMAGE), Smil.Item("s.amr", Smil.Kind.AUDIO)))
        assertTrue(smil.startsWith("<smil><head><layout>"))
        assertTrue(smil.indexOf("a&amp;b&quot;.png") < smil.indexOf("t.txt"))
        assertTrue(smil.contains("<audio src=\"s.amr\"/>"))
        assertEquals(3, Regex("<par ").findAll(smil).count())
    }

    @Test
    fun builderWithoutTextOrAttachments() {
        val req = MmsMessageBuilder.build(to = listOf("+15551234567"), text = null, attachments = emptyList(), transactionId = "T")
        assertEquals(1, req.parts.size)
        assertEquals(ContentType.SMIL, req.parts[0].contentType.mimeType)
    }

    @Test
    fun fileNamesAreSanitised() {
        assertEquals("my_photo.jpg", MmsMessageBuilder.sanitizeFileName("/sdcard/my photo.jpg", "image/jpeg", 0))
        assertEquals("part_3.png", MmsMessageBuilder.sanitizeFileName("", "image/png", 3))
        assertEquals("____.gif", MmsMessageBuilder.sanitizeFileName("фото.gif", "image/gif", 0))
        assertEquals("noext.jpg", MmsMessageBuilder.sanitizeFileName("noext", "image/jpeg", 0))
    }

    @Test
    fun transactionIdsArePrintableAndDistinct() {
        val a = MmsMessageBuilder.newTransactionId(Random(1), 1000)
        val b = MmsMessageBuilder.newTransactionId(Random(2), 1000)
        assertNotEquals(a, b)
        assertTrue(a.all { it.code in 33..126 })
    }

    @Test
    fun responseStatusDescriptions() {
        assertEquals("OK", ResponseStatus.describe(ResponseStatus.OK))
        assertTrue(ResponseStatus.isTransient(0xC1))
        assertFalse(ResponseStatus.isTransient(0xE1))
        assertTrue(ResponseStatus.describe(0xDE).contains("Temporary"))
    }

    @Test
    fun decodeAsFiltersByType() {
        val bytes = MmsPduEncoder.encode(AcknowledgeInd("T"))
        assertEquals("T", MmsPduDecoder.decodeAs<AcknowledgeInd>(bytes)?.transactionId)
        assertEquals(null, MmsPduDecoder.decodeAs<NotificationInd>(bytes))
    }
}
