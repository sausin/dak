package app.dak.telephony

import app.dak.mms.pdu.ContentType
import app.dak.mms.pdu.MmsCharset
import app.dak.mms.pdu.MmsMessageBuilder
import app.dak.mms.pdu.MmsTime
import app.dak.mms.pdu.NotificationInd
import app.dak.mms.pdu.PduPart
import app.dak.mms.pdu.RetrieveConf
import app.dak.telephony.mms.AddrRow
import app.dak.telephony.mms.MmsDownloadStateCodec
import app.dak.telephony.mms.MmsProviderMapping
import app.dak.telephony.mms.StoredPart
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MmsMappingTest {
    private val now = 1_700_000_000_123L

    @Test
    fun notificationRowCarriesDownloadFields() {
        val n = NotificationInd(
            contentLocation = "http://mmsc/1",
            transactionId = "T1",
            from = "+15551234567",
            subject = "Hi",
            messageSize = 2048,
            expiry = MmsTime.Relative(3600),
        )
        val row = MmsProviderMapping.notificationRow(n, subId = 3, threadId = 9, nowMillis = now)
        assertEquals(130, row["m_type"])
        assertEquals(1, row["msg_box"])
        assertEquals("http://mmsc/1", row["ct_l"])
        assertEquals("T1", row["tr_id"])
        assertEquals(1_700_000_000L + 3600, row["exp"])
        assertEquals(2048L, row["m_size"])
        assertEquals(1_700_000_000L, row["date"])
        assertEquals(3, row["sub_id"])
        assertEquals(9L, row["thread_id"])
        assertEquals("Hi", row["sub"])
        assertEquals(106, row["sub_cs"])
        assertEquals(0, row["read"])
        assertEquals(listOf(AddrRow("+15551234567", 137)), MmsProviderMapping.notificationAddresses(n))
    }

    @Test
    fun missingSubIdIsOmitted() {
        val row = MmsProviderMapping.notificationRow(NotificationInd(contentLocation = "x"), subId = -1, threadId = 1, nowMillis = now)
        assertTrue("sub_id" !in row)
        assertTrue("sub" !in row)
        assertTrue("exp" !in row)
    }

    @Test
    fun retrievedRowPartsAndAddresses() {
        val jpeg = byteArrayOf(1, 2, 3)
        val r = RetrieveConf(
            contentType = ContentType.multipartRelated("<smil>"),
            parts = listOf(
                PduPart(ContentType(ContentType.SMIL, charset = MmsCharset.UTF_8), "<smil/>".toByteArray(), contentId = "<smil>", contentLocation = "smil.xml"),
                PduPart(ContentType("text/plain", charset = MmsCharset.UTF_16), "hé".toByteArray(Charsets.UTF_16), contentLocation = "t.txt"),
                PduPart(ContentType("image/jpeg", name = "p.jpg"), jpeg, contentId = "<p>", contentLocation = "p.jpg"),
            ),
            messageId = "M1",
            dateSeconds = 1_600_000_000L,
            from = "+15550001111",
            to = listOf("+15552223333", "+15554445555"),
            cc = listOf("+15556667777"),
        )
        val row = MmsProviderMapping.retrievedRow(r, subId = 2, threadId = 7, nowMillis = now)
        assertEquals(132, row["m_type"])
        assertEquals("M1", row["m_id"])
        assertEquals(1_600_000_000L, row["date_sent"])
        assertEquals(1_700_000_000L, row["date"])
        assertEquals(ContentType.MULTIPART_RELATED, row["ct_t"])
        assertEquals(0, row["text_only"])

        val parts = MmsProviderMapping.partRows(r.parts)
        assertEquals(listOf(0, 1, 2), parts.map { it.values["seq"] })
        assertEquals("<smil/>", parts[0].text)
        assertEquals("hé", parts[1].text)
        assertEquals(106, parts[1].values["chset"], "inline text is re-stored as UTF-8")
        assertNull(parts[2].text)
        assertContentEquals(jpeg, parts[2].data)
        assertEquals("<p>", parts[2].values["cid"])
        assertEquals("p.jpg", parts[2].values["cl"])
        assertEquals("p.jpg", parts[2].values["name"])

        assertEquals(
            listOf(AddrRow("+15550001111", 137), AddrRow("+15552223333", 151), AddrRow("+15554445555", 151), AddrRow("+15556667777", 130)),
            MmsProviderMapping.retrievedAddresses(r),
        )
    }

    @Test
    fun outgoingRowAndAddresses() {
        val req = MmsMessageBuilder.build(to = listOf("+15551234567"), text = "hi", attachments = emptyList(), transactionId = "T9")
        val row = MmsProviderMapping.outgoingRow(req, subId = 1, threadId = 4, nowMillis = now, encodedSize = 321)
        assertEquals(128, row["m_type"])
        assertEquals(4, row["msg_box"])
        assertEquals("T9", row["tr_id"])
        assertEquals(321, row["m_size"])
        assertEquals(1, row["text_only"])
        assertEquals(1, row["read"])
        assertEquals(listOf(AddrRow("insert-address-token", 137), AddrRow("+15551234567", 151)), MmsProviderMapping.outgoingAddresses(req))
    }

    @Test
    fun threadRecipientsForOneToOneAndGroup() {
        val own = setOf("+15550000000")
        // 1:1: the single To is us, even when our number is unknown.
        assertEquals(setOf("+15551111111"), MmsProviderMapping.threadRecipients("+15551111111", listOf("+15559999999"), emptyList()) { false })
        // Group: sender + others, minus us.
        assertEquals(
            setOf("+15551111111", "+15552222222", "+15553333333"),
            MmsProviderMapping.threadRecipients("+15551111111", listOf("+15550000000", "+15552222222"), listOf("+15553333333")) { it in own },
        )
        // Sender also listed in To is not duplicated.
        assertEquals(
            setOf("+15551111111", "+15552222222"),
            MmsProviderMapping.threadRecipients("+15551111111", listOf("+15551111111", "+15552222222"), emptyList()) { false },
        )
        assertEquals(emptySet(), MmsProviderMapping.threadRecipients(null, emptyList(), emptyList()) { false })
    }

    @Test
    fun bodyAndAttachmentsFromStoredParts() {
        val (body, attachments) = MmsProviderMapping.bodyAndAttachments(
            listOf(
                StoredPart(10, "application/smil", "<smil/>", null, null, "smil.xml"),
                StoredPart(11, "text/plain", "line one", null, null, "a.txt"),
                StoredPart(12, "IMAGE/JPEG", null, "img.jpg", null, "img.jpg"),
                StoredPart(13, "text/plain", "line two", null, null, null),
                StoredPart(14, "text/x-vcard", null, null, "card.vcf", null),
            ),
        )
        assertEquals("line one\nline two", body)
        assertEquals(listOf("image/jpeg", "text/x-vcard"), attachments.map { it.mimeType })
        assertEquals("content://mms/part/12", attachments[0].uri)
        assertEquals("img.jpg", attachments[0].name)
        assertEquals("card.vcf", attachments[1].name)
    }

    @Test
    fun downloadStateCodecRoundTrips() {
        val states = listOf(
            MmsDownloadState.Pending,
            MmsDownloadState.Downloading,
            MmsDownloadState.Done,
            MmsDownloadState.Failed("No mobile data | try Wi-Fi calling", 3),
        )
        for (s in states) assertEquals(s, MmsDownloadStateCodec.decode(MmsDownloadStateCodec.encode(s)))
        assertNull(MmsDownloadStateCodec.decode("f|x|y"))
        assertNull(MmsDownloadStateCodec.decode("?"))
    }
}
