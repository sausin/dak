package app.dak.mms.pdu

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EncoderRoundTripTest {

    private fun roundTrip(pdu: MmsPdu): MmsPdu {
        val bytes = MmsPduEncoder.encode(pdu)
        val decoded = MmsPduDecoder.decode(bytes)
        assertIs<PduDecodeResult.Success>(decoded, "round trip failed for ${hex(bytes)}: $decoded")
        return decoded.pdu
    }

    @Test
    fun sendReqHeaderBytesAreExact() {
        val req = SendReq(
            transactionId = "T1",
            to = listOf("+1 555-123-4567"),
            contentType = ContentType.multipartRelated(start = "<smil>"),
            parts = emptyList(),
            messageClass = null,
        )
        val expected = bytes(
            0x8C, 0x80, // m-send-req
            0x98, text("T1"),
            0x8D, 0x92,
            0x89, 0x01, 0x81, // From: insert-address-token
            0x97, text("+15551234567/TYPE=PLMN"),
            0x84, lp(bytes(0xB3, 0x8A, text("<smil>"), 0x89, text("application/smil"))),
            0x00, // zero parts
        )
        assertContentEquals(expected, MmsPduEncoder.encode(req), hex(MmsPduEncoder.encode(req)))
    }

    @Test
    fun notifyRespAndAcknowledgeBytesAreExact() {
        assertContentEquals(
            bytes(0x8C, 0x83, 0x98, text("tx"), 0x8D, 0x92, 0x95, 0x81, 0x91, 0x80),
            MmsPduEncoder.encode(NotifyRespInd(transactionId = "tx", status = MmsStatus.RETRIEVED, reportAllowed = true)),
        )
        assertContentEquals(
            bytes(0x8C, 0x85, 0x98, text("tx"), 0x8D, 0x92),
            MmsPduEncoder.encode(AcknowledgeInd(transactionId = "tx")),
        )
    }

    @Test
    fun builtSendReqRoundTrips() {
        val image = ByteArray(300) { (it * 7).toByte() }
        val req = MmsMessageBuilder.build(
            to = listOf("+15551234567", "+447700900123"),
            text = "Photos from the trip 📷",
            attachments = listOf(
                MmsMessageBuilder.Attachment("image/jpeg", "IMG 0001.JPG", image),
                MmsMessageBuilder.Attachment("image/jpeg", "IMG 0001.JPG", image.copyOf(10)),
                MmsMessageBuilder.Attachment("video/mp4", "", ByteArray(5)),
            ),
            subject = "Trip ✈",
            transactionId = "Tabc",
            dateSeconds = 1_700_000_000L,
            requestDeliveryReport = true,
        )
        val back = assertIs<SendReq>(roundTrip(req))
        assertEquals(req, back)

        assertEquals(listOf("smil.xml", "IMG_0001.JPG", "IMG_0001_1.JPG", "part_2.mp4", "text_0.txt"), back.parts.map { it.contentLocation })
        val smil = back.parts[0].text()!!
        assertTrue(smil.contains("<img src=\"IMG_0001.JPG\" region=\"Image\"/>"), smil)
        assertTrue(smil.contains("<video src=\"part_2.mp4\" region=\"Image\"/>"), smil)
        assertTrue(smil.contains("<text src=\"text_0.txt\" region=\"Text\"/>"), smil)
        assertEquals("Photos from the trip 📷", back.parts.last().text())
    }

    @Test
    fun longPartsUseLengthQuote() {
        val big = ByteArray(70_000) { it.toByte() }
        val req = MmsMessageBuilder.build(
            to = listOf("+15551234567"),
            text = "x".repeat(500),
            attachments = listOf(MmsMessageBuilder.Attachment("image/png", "a-very-long-file-name-that-needs-a-length-quote.png", big)),
            transactionId = "T",
        )
        assertEquals(req, roundTrip(req))
    }

    @Test
    fun notificationIndRoundTrips() {
        val n = NotificationInd(
            contentLocation = "http://mmsc.example/abc",
            transactionId = "T77",
            from = "+919812345678",
            subject = "नमस्ते",
            messageClass = MessageClass.ADVERTISEMENT,
            messageSize = 123_456L,
            expiry = MmsTime.Absolute(1_800_000_000L),
            priority = Priority.LOW,
            deliveryReport = true,
        )
        assertEquals(n, roundTrip(n))
    }

    @Test
    fun retrieveConfRoundTripsIncludingDispositionAndOtherParams() {
        val r = RetrieveConf(
            contentType = ContentType(ContentType.MULTIPART_MIXED),
            parts = listOf(
                PduPart(
                    contentType = ContentType(
                        mimeType = "text/x-vcard",
                        charset = MmsCharset.UTF_8,
                        otherParameters = mapOf("x-flag" to "on"),
                    ),
                    data = "BEGIN:VCARD".toByteArray(),
                    contentId = "<card>",
                    contentLocation = "card.vcf",
                    contentDisposition = "attachment",
                    dispositionFileName = "Card.vcf",
                ),
                PduPart.text("hello", contentId = "<t>", contentLocation = "t.txt"),
                PduPart(contentType = ContentType("image/heic"), data = byteArrayOf(9)),
            ),
            transactionId = "T",
            messageId = "M",
            dateSeconds = 5L,
            from = "sender@example.com",
            to = listOf("+15551234567"),
            cc = listOf("+15550000000"),
            subject = "s",
            messageClass = MessageClass.PERSONAL,
            priority = Priority.NORMAL,
            deliveryReport = false,
            readReport = true,
            retrieveStatus = RetrieveStatus.OK,
            retrieveText = "fine",
        )
        assertEquals(r, roundTrip(r))
    }

    @Test
    fun otherPduTypesRoundTrip() {
        val pdus = listOf(
            SendConf(responseStatus = ResponseStatus.OK, transactionId = "T", messageId = "M", responseText = "ok"),
            NotifyRespInd(transactionId = "T", status = MmsStatus.DEFERRED, reportAllowed = false),
            AcknowledgeInd(transactionId = "T", reportAllowed = true),
            DeliveryInd(messageId = "M", status = MmsStatus.EXPIRED, to = listOf("+15551234567"), dateSeconds = 99L),
            ReadOrigInd(messageId = "M", from = "+15551234567", to = listOf("+15557654321"), dateSeconds = 1L, readStatus = ReadStatus.DELETED_WITHOUT_BEING_READ),
        )
        for (pdu in pdus) assertEquals(pdu, roundTrip(pdu))
    }

    @Test
    fun relativeExpiryAndTextMessageClassRoundTrip() {
        val req = SendReq(
            transactionId = "T",
            to = listOf("a@example.com"),
            contentType = ContentType(ContentType.MULTIPART_MIXED),
            parts = listOf(PduPart.text("x")),
            messageClass = "x-custom",
            expiry = MmsTime.Relative(604_800L),
        )
        assertEquals(req, roundTrip(req))
    }
}
