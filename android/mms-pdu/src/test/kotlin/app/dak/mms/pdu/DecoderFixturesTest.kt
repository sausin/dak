package app.dak.mms.pdu

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Decoding of hand-built byte fixtures, one per supported PDU type. */
class DecoderFixturesTest {

    private inline fun <reified T : MmsPdu> decodeOk(bytes: ByteArray): T {
        val result = MmsPduDecoder.decode(bytes)
        assertIs<PduDecodeResult.Success>(result, "decode failed: $result for ${hex(bytes)}")
        return assertIs<T>(result.pdu)
    }

    @Test
    fun notificationInd() {
        val from = bytes(0x80, text("+15551234567/TYPE=PLMN"))
        val pdu = bytes(
            0x8C, 0x82, // X-Mms-Message-Type: m-notification-ind
            0x98, text("T1234"), // X-Mms-Transaction-ID
            0x8D, 0x92, // X-Mms-MMS-Version: 1.2
            0x89, lp(from), // From
            0x96, text("Hello"), // Subject
            0x8A, 0x80, // X-Mms-Message-Class: personal
            0x8E, 0x02, 0x01, 0xF4, // X-Mms-Message-Size: 500
            0x88, lp(bytes(0x81, 0x03, 0x02, 0xA3, 0x00)), // X-Mms-Expiry: relative 172800 s
            0x83, text("http://mmsc.example.com/abc?x=1"), // X-Mms-Content-Location
        )
        val n = decodeOk<NotificationInd>(pdu)
        assertEquals("T1234", n.transactionId)
        assertEquals(MmsVersion.V1_2, n.mmsVersion)
        assertEquals("+15551234567", n.from)
        assertEquals("Hello", n.subject)
        assertEquals(MessageClass.PERSONAL, n.messageClass)
        assertEquals(500L, n.messageSize)
        assertEquals(MmsTime.Relative(172800), n.expiry)
        assertEquals(1_000_172_800L, n.expiry!!.toEpochSeconds(1_000_000_000L))
        assertEquals("http://mmsc.example.com/abc?x=1", n.contentLocation)
    }

    @Test
    fun notificationIndWithAbsoluteExpiryInsertAddressAndTokenClass() {
        val pdu = bytes(
            0x8C, 0x82,
            0x98, text("t"),
            0x8D, 0x90,
            0x89, lp(bytes(0x81)), // From: insert-address-token
            0x8A, text("x-carrier"), // token-text message class
            0x8E, 0x03, 0x01, 0x00, 0x00, // 65536 octets
            0x88, lp(bytes(0x80, 0x04, 0x60, 0x00, 0x00, 0x00)), // absolute expiry
            0xB7, text("com.example.app"), // X-Mms-Applic-ID (skipped)
            "X-Custom", 0, text("value"), // application header (ignored)
            0x83, text("http://m/1"),
        )
        val n = decodeOk<NotificationInd>(pdu)
        assertNull(n.from)
        assertEquals("x-carrier", n.messageClass)
        assertEquals(65536L, n.messageSize)
        assertEquals(MmsTime.Absolute(0x60000000L), n.expiry)
        assertEquals(MmsVersion.V1_0, n.mmsVersion)
        assertEquals("http://m/1", n.contentLocation)
    }

    @Test
    fun retrieveConfMultipartWithParametersAndPartHeaders() {
        val smil = "<smil><body/></smil>"
        val smilPart = entry(
            bytes(
                lp(bytes(text("application/smil"), 0x81, 0xEA)), // CT general form: extension media + charset utf-8
                0xC0, 0x22, text("<smil>"), // Content-ID (quoted-string)
                0x8E, text("smil.xml"), // Content-Location
            ),
            smil.toByteArray(),
        )
        val body = "Hi ☃ there"
        val textPart = entry(
            bytes(
                lp(bytes(0x83, 0x81, 0xEA, 0x85, text("note.txt"))), // text/plain; charset=utf-8; name=note.txt
                "Content-ID", 0, text("<t1>"), // textual header
                0xAE, lp(bytes(0x81, 0x86, text("note-file.txt"))), // Content-Disposition: attachment; filename
            ),
            body.toByteArray(),
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        val imagePart = entry(bytes(0x9E, 0x8E, text("photo.jpg")), jpeg) // image/jpeg, Content-Location

        val subject = "Привет"
        val subjectValue = bytes(0xEA, 0x7F, subject, 0)
        val pdu = bytes(
            0x8C, 0x84,
            0x98, text("tid-9"),
            0x8D, 0x92,
            0x8B, text("msgid-1"), // Message-ID
            0x85, 0x04, 0x5F, 0x00, 0x00, 0x00, // Date
            0x89, lp(bytes(0x80, text("+911234567890/TYPE=PLMN"))),
            0x97, text("+919876543210/TYPE=PLMN"), // To
            0x97, text("friend@example.com"), // To (e-mail)
            0x82, text("+14155550100/TYPE=PLMN"), // Cc
            0x96, lp(subjectValue), // Subject (UTF-8 encoded-string)
            0x8F, 0x82, // Priority high
            0x86, 0x81, // Delivery-Report: no
            0x99, 0x80, // Retrieve-Status: ok
            0x84, lp(bytes(0xB3, 0x8A, text("<smil>"), 0x89, text("application/smil"))), // multipart.related
            0x03, smilPart, textPart, imagePart,
        )

        val r = decodeOk<RetrieveConf>(pdu)
        assertEquals("tid-9", r.transactionId)
        assertEquals("msgid-1", r.messageId)
        assertEquals(0x5F000000L, r.dateSeconds)
        assertEquals("+911234567890", r.from)
        assertEquals(listOf("+919876543210", "friend@example.com"), r.to)
        assertEquals(listOf("+14155550100"), r.cc)
        assertEquals(subject, r.subject)
        assertEquals(Priority.HIGH, r.priority)
        assertEquals(false, r.deliveryReport)
        assertTrue(r.isRetrieveOk)
        assertEquals(ContentType.MULTIPART_RELATED, r.contentType.mimeType)
        assertEquals("<smil>", r.contentType.start)
        assertEquals("application/smil", r.contentType.type)

        assertEquals(3, r.parts.size)
        val (s, t, img) = r.parts
        assertEquals("application/smil", s.contentType.mimeType)
        assertEquals(MmsCharset.UTF_8, s.charset)
        assertEquals("<smil>", s.contentId)
        assertEquals("smil.xml", s.contentLocation)
        assertEquals(smil, s.text())

        assertEquals("text/plain", t.contentType.mimeType)
        assertEquals("note.txt", t.contentType.name)
        assertEquals("<t1>", t.contentId)
        assertEquals("attachment", t.contentDisposition)
        assertEquals("note-file.txt", t.fileName)
        assertEquals(body, t.text())

        assertEquals("image/jpeg", img.contentType.mimeType)
        assertEquals("photo.jpg", img.fileName)
        assertTrue(img.data.contentEquals(jpeg))
        assertNull(img.text())
    }

    @Test
    fun retrieveConfPartCharsets() {
        fun part(charset: ByteArray, data: ByteArray) = entry(lp(bytes(0x83, 0x81, charset)), data)
        val pdu = bytes(
            0x8C, 0x84, 0x8D, 0x92,
            0x84, 0xA3, // application/vnd.wap.multipart.mixed, no parameters
            0x04,
            part(bytes(0x83), "plain ascii".toByteArray()), // US-ASCII
            part(bytes(0x84), bytes("r", 0xE9, "sum", 0xE9)), // ISO-8859-1
            part(bytes(0x02, 0x03, 0xF7), bytes(0xFE, 0xFF, 0x00, 'o'.code, 0x00, 'k'.code)), // UTF-16 + BOM
            part(bytes(0x02, 0x03, 0xE8), bytes(0x09, 0x28)), // UCS-2 (big-endian) U+0928
        )
        val r = decodeOk<RetrieveConf>(pdu)
        assertEquals(listOf("plain ascii", "résumé", "ok", "न"), r.parts.map { it.text() })
        assertEquals(listOf(MmsCharset.US_ASCII, MmsCharset.ISO_8859_1, MmsCharset.UTF_16, MmsCharset.UCS_2), r.parts.map { it.charset })
    }

    @Test
    fun retrieveConfSinglePartBody() {
        val pdu = bytes(0x8C, 0x84, 0x8D, 0x92, 0x84, 0x83, "just text")
        val r = decodeOk<RetrieveConf>(pdu)
        assertEquals(1, r.parts.size)
        assertEquals("just text", r.parts[0].text())
    }

    @Test
    fun retrieveConfWithErrorStatus() {
        val pdu = bytes(0x8C, 0x84, 0x8D, 0x92, 0x99, 0xE2, 0x9A, text("gone"), 0x84, 0xA3, 0x00)
        val r = decodeOk<RetrieveConf>(pdu)
        assertEquals(RetrieveStatus.ERROR_PERMANENT_MESSAGE_NOT_FOUND, r.retrieveStatus)
        assertEquals("gone", r.retrieveText)
        assertTrue(!r.isRetrieveOk)
        assertTrue(r.parts.isEmpty())
    }

    @Test
    fun nestedMultipartIsFlattened() {
        val inner = bytes(
            0x02,
            entry(bytes(0x83), "a".toByteArray()),
            entry(bytes(0x83), "b".toByteArray()),
        )
        val pdu = bytes(
            0x8C, 0x84, 0x8D, 0x92, 0x84, 0xA3,
            0x02,
            entry(bytes(0xA6), inner), // application/vnd.wap.multipart.alternative
            entry(bytes(0x9D), bytes(1, 2)), // image/gif
        )
        val r = decodeOk<RetrieveConf>(pdu)
        assertEquals(listOf("text/plain", "text/plain", "image/gif"), r.parts.map { it.contentType.mimeType })
    }

    @Test
    fun sendConf() {
        val ok = decodeOk<SendConf>(bytes(0x8C, 0x81, 0x98, text("T1"), 0x8D, 0x92, 0x92, 0x80, 0x8B, text("mid-42")))
        assertTrue(ok.isOk)
        assertEquals("mid-42", ok.messageId)
        assertEquals("T1", ok.transactionId)

        val failed = decodeOk<SendConf>(bytes(0x8C, 0x81, 0x98, text("T2"), 0x8D, 0x92, 0x92, 0xC3, 0x93, text("try later")))
        assertEquals(ResponseStatus.ERROR_TRANSIENT_NETWORK_PROBLEM, failed.responseStatus)
        assertTrue(ResponseStatus.isTransient(failed.responseStatus))
        assertEquals("try later", failed.responseText)
    }

    @Test
    fun deliveryInd() {
        val d = decodeOk<DeliveryInd>(
            bytes(0x8C, 0x86, 0x8D, 0x92, 0x8B, text("mid-42"), 0x97, text("+15551234567/TYPE=PLMN"), 0x85, 0x04, 0x60, 0, 0, 1, 0x95, 0x81),
        )
        assertEquals("mid-42", d.messageId)
        assertEquals(MmsStatus.RETRIEVED, d.status)
        assertEquals(listOf("+15551234567"), d.to)
        assertEquals(0x60000001L, d.dateSeconds)
    }

    @Test
    fun readOrigIndIsTolerant() {
        val full = decodeOk<ReadOrigInd>(
            bytes(
                0x8C, 0x88, 0x8D, 0x90,
                0x8B, text("mid-7"),
                0x89, lp(bytes(0x80, text("+15550001111/TYPE=PLMN"))),
                0xC0, 0x03, 0x01, 0x02, 0x03, // unknown field 0x40 with length-prefixed value
                0x9B, 0x80,
            ),
        )
        assertEquals("mid-7", full.messageId)
        assertEquals("+15550001111", full.from)
        assertEquals(ReadStatus.READ, full.readStatus)

        val bare = decodeOk<ReadOrigInd>(bytes(0x8C, 0x88))
        assertNull(bare.messageId)
        assertNull(bare.readStatus)
    }

    @Test
    fun octetFieldWithNonOctetValueIsSkippedNotFatal() {
        // Priority encoded (wrongly) as a text string must not break the rest of the headers.
        val n = decodeOk<NotificationInd>(bytes(0x8C, 0x82, 0x8F, text("high"), 0x83, text("http://m/2")))
        assertNull(n.priority)
        assertEquals("http://m/2", n.contentLocation)
    }
}
