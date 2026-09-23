package app.dak.mms.pdu

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** m-read-rec-ind (MMS-ENC 1.3 §6.7.2) encoding, decoding and the "should we send one" decision. */
class ReadReportTest {

    @Test
    fun readRecIndHasTheSpecifiedHeaderOrder() {
        val pdu = ReadRecInd(messageId = "msg-1", to = "+15551234567", dateSeconds = 0x5F000000L)
        // type read-rec-ind, version 1.2, Message-ID, To (/TYPE=PLMN), From insert-address-token, Date, Read-Status Read.
        val expected = bytes(
            0x8C, 0x87, 0x8D, 0x92,
            0x8B, text("msg-1"),
            0x97, text("+15551234567/TYPE=PLMN"),
            0x89, 0x01, 0x81,
            0x85, 0x04, 0x5F, 0x00, 0x00, 0x00,
            0x9B, 0x80,
        )
        assertContentEquals(expected, MmsPduEncoder.encode(pdu), hex(MmsPduEncoder.encode(pdu)))
    }

    @Test
    fun readRecIndRoundTrips() {
        val pdu = ReadRecInd(messageId = "abc@mmsc", to = "friend@example.com", dateSeconds = 1_700_000_000L, readStatus = ReadStatus.DELETED_WITHOUT_BEING_READ)
        val back = assertIs<ReadRecInd>(MmsPduDecoder.decode(MmsPduEncoder.encode(pdu)).getOrNull())
        assertEquals(pdu, back)
    }

    @Test
    fun readRecIndWithoutMessageIdOrToIsRejected() {
        assertIs<PduDecodeResult.Failure>(MmsPduDecoder.decode(bytes(0x8C, 0x87, 0x8D, 0x92, 0x97, text("+15551234567/TYPE=PLMN"), 0x9B, 0x80)))
        assertIs<PduDecodeResult.Failure>(MmsPduDecoder.decode(bytes(0x8C, 0x87, 0x8D, 0x92, 0x8B, text("m"), 0x9B, 0x80)))
    }

    @Test
    fun readReceiptOnlyWhenRequestedWithAnIdAndAPersonalSender() {
        val ok = assertNotNull(MmsClientTransactions.readReceipt("m1", "+919876543210", true, MessageClass.PERSONAL, 100))
        assertEquals("m1", ok.messageId)
        assertEquals("+919876543210", ok.to)
        assertNull(ok.from)
        assertEquals(100L, ok.dateSeconds)
        assertEquals(ReadStatus.READ, ok.readStatus)
        // Wire suffix of the sender is stripped before re-encoding.
        assertEquals("+919876543210", MmsClientTransactions.readReceipt("m1", "+919876543210/TYPE=PLMN", true, null, 1)!!.to)
        assertNotNull(MmsClientTransactions.readReceipt("m1", "a@b.co", true, null, 1))

        assertNull(MmsClientTransactions.readReceipt("m1", "+919876543210", false, null, 1), "not requested")
        assertNull(MmsClientTransactions.readReceipt(null, "+919876543210", true, null, 1), "no Message-ID")
        assertNull(MmsClientTransactions.readReceipt("  ", "+919876543210", true, null, 1))
        assertNull(MmsClientTransactions.readReceipt("m 1", "+919876543210", true, null, 1), "non-printable id")
        assertNull(MmsClientTransactions.readReceipt("x".repeat(MmsLimits.MAX_TOKEN_CHARS + 1), "+919876543210", true, null, 1))
        assertNull(MmsClientTransactions.readReceipt("m1", null, true, null, 1), "hidden sender")
        assertNull(MmsClientTransactions.readReceipt("m1", "insert-address-token", true, null, 1))
        assertNull(MmsClientTransactions.readReceipt("m1", "VM-HDFCBK", true, null, 1), "alphanumeric sender id")
        assertNull(MmsClientTransactions.readReceipt("m1", "56161", true, null, 1), "short code")
        assertNull(MmsClientTransactions.readReceipt("m1", "*121#", true, null, 1))
        assertNull(MmsClientTransactions.readReceipt("m1", "+919876543210", true, MessageClass.ADVERTISEMENT, 1))
        assertNull(MmsClientTransactions.readReceipt("m1", "+919876543210", true, "Auto", 1))
    }

    @Test
    fun personalAddresses() {
        assertTrue(MmsClientTransactions.isPersonalAddress("+1 (555) 123-4567"))
        assertTrue(MmsClientTransactions.isPersonalAddress("9876543"))
        assertFalse(MmsClientTransactions.isPersonalAddress("123456"))
        assertFalse(MmsClientTransactions.isPersonalAddress("@example.com"))
        assertFalse(MmsClientTransactions.isPersonalAddress("a@b@c.com"))
        assertFalse(MmsClientTransactions.isPersonalAddress("a@example."))
        assertFalse(MmsClientTransactions.isPersonalAddress("a b@example.com"))
        assertFalse(MmsClientTransactions.isPersonalAddress(""))
    }

    @Test
    fun reportAllowedIsCarriedOnEveryAnswer() {
        val notification = NotificationInd(contentLocation = "http://mmsc.example.com/x", transactionId = "T1", mmsVersion = 0x20)
        assertEquals(false, MmsClientTransactions.forUnsupportedVersion(notification, reportAllowed = false)!!.reportAllowed)
        assertEquals(true, MmsClientTransactions.deferred(notification, reportAllowed = true)!!.reportAllowed)
        val damaged = bytes(0x8C, 0x82, 0x98, text("T7"), 0x8D, 0x92)
        assertEquals(false, MmsClientTransactions.forUndecodable(damaged, reportAllowed = false)!!.reportAllowed)
        // X-Mms-Report-Allowed No on the wire (0x91 0x81).
        val encoded = MmsPduEncoder.encode(MmsClientTransactions.afterRetrieval("T1", wasDeferred = false, reportAllowed = false)!!)
        assertContentEquals(bytes(0x8C, 0x83, 0x98, text("T1"), 0x8D, 0x92, 0x95, 0x81, 0x91, 0x81), encoded)
    }

    @Test
    fun damagedReadRecIndGetsNoAnswer() {
        assertNull(MmsClientTransactions.forUndecodable(bytes(0x8C, 0x87, 0x98, text("T8"), 0x8D, 0x92)))
    }
}
