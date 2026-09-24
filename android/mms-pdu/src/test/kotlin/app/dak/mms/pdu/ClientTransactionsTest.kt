package app.dak.mms.pdu

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientTransactionsTest {

    private val notification = NotificationInd(contentLocation = "http://mmsc.example.com/abc", transactionId = "T42")

    @Test
    fun immediateRetrievalIsAnsweredRetrieved() {
        val pdu = assertIs<NotifyRespInd>(MmsClientTransactions.afterRetrieval("T42", wasDeferred = false))
        assertEquals(MmsStatus.RETRIEVED, pdu.status)
        assertEquals("T42", pdu.transactionId)
        // Wire form: type notifyresp, TID, version 1.2, X-Mms-Status Retrieved.
        assertContentEquals(bytes(0x8C, 0x83, 0x98, text("T42"), 0x8D, 0x92, 0x95, 0x81), MmsPduEncoder.encode(pdu))
    }

    @Test
    fun deferredThenRetrievedIsAcknowledged() {
        val deferred = assertIs<NotifyRespInd>(MmsClientTransactions.deferred(notification))
        assertEquals(MmsStatus.DEFERRED, deferred.status)
        assertContentEquals(bytes(0x8C, 0x83, 0x98, text("T42"), 0x8D, 0x92, 0x95, 0x83), MmsPduEncoder.encode(deferred))

        val ack = assertIs<AcknowledgeInd>(MmsClientTransactions.afterRetrieval("T42", wasDeferred = true))
        assertEquals("T42", ack.transactionId)
        assertContentEquals(bytes(0x8C, 0x85, 0x98, text("T42"), 0x8D, 0x92), MmsPduEncoder.encode(ack))
    }

    @Test
    fun noTransactionIdMeansNothingToAnswer() {
        assertNull(MmsClientTransactions.deferred(notification.copy(transactionId = null)))
        assertNull(MmsClientTransactions.deferred(notification.copy(transactionId = "")))
        assertNull(MmsClientTransactions.afterRetrieval(null, wasDeferred = true))
        assertNull(MmsClientTransactions.unrecognised(null))
    }

    @Test
    fun reportAllowedIsCarried() {
        val pdu = assertIs<NotifyRespInd>(MmsClientTransactions.afterRetrieval("T1", wasDeferred = false, reportAllowed = false))
        assertEquals(false, pdu.reportAllowed)
        assertEquals(false, assertIs<AcknowledgeInd>(MmsClientTransactions.afterRetrieval("T1", true, reportAllowed = false)).reportAllowed)
    }

    @Test
    fun unsupportedMajorVersionIsUnrecognised() {
        assertTrue(MmsClientTransactions.isSupportedVersion(MmsVersion.V1_0))
        assertTrue(MmsClientTransactions.isSupportedVersion(MmsVersion.V1_3))
        assertNull(MmsClientTransactions.forUnsupportedVersion(notification))

        // A 2.0 notification decodes fine but must not be fetched.
        val v2 = MmsPduDecoder.decodeAs<NotificationInd>(
            bytes(0x8C, 0x82, 0x98, text("T9"), 0x8D, 0xA0, 0x83, text("http://mmsc.example.com/v2")),
        )!!
        assertEquals(0x20, v2.mmsVersion)
        val answer = assertIs<NotifyRespInd>(MmsClientTransactions.forUnsupportedVersion(v2))
        assertEquals(MmsStatus.UNRECOGNISED, answer.status)
        assertEquals("T9", answer.transactionId)
        // We answer in the version we speak.
        assertEquals(MmsVersion.DEFAULT, answer.mmsVersion)
    }

    @Test
    fun undecodableNotificationIsAnsweredUnrecognised() {
        // Truncated inside the From header: decode fails, but type and TID come first.
        val truncated = bytes(0x8C, 0x82, 0x98, text("T77"), 0x8D, 0x92, 0x89, 0x1E, 0x80)
        assertIs<PduDecodeResult.Failure>(MmsPduDecoder.decode(truncated))
        val answer = assertIs<NotifyRespInd>(MmsClientTransactions.forUndecodable(truncated))
        assertEquals(MmsStatus.UNRECOGNISED, answer.status)
        assertEquals("T77", answer.transactionId)

        // Missing Content-Location (mandatory).
        val noLocation = bytes(0x8C, 0x82, 0x98, text("T78"), 0x8D, 0x92)
        assertIs<PduDecodeResult.Failure>(MmsPduDecoder.decode(noLocation))
        assertEquals("T78", MmsClientTransactions.forUndecodable(noLocation)?.transactionId)
    }

    @Test
    fun unknownMessageTypeWithTransactionIdIsUnrecognised() {
        val unknown = bytes(0x8C, 0xA0, 0x98, text("T5"), 0x8D, 0x92)
        assertIs<PduDecodeResult.Failure>(MmsPduDecoder.decode(unknown))
        assertEquals("T5", MmsClientTransactions.forUndecodable(unknown)?.transactionId)
    }

    @Test
    fun damagedReportsAndGarbageGetNoAnswer() {
        // A delivery report missing its Message-ID: nothing sensible to answer.
        val report = bytes(0x8C, 0x86, 0x98, text("T6"), 0x8D, 0x92)
        assertIs<PduDecodeResult.Failure>(MmsPduDecoder.decode(report))
        assertNull(MmsClientTransactions.forUndecodable(report))
        // No transaction id.
        assertNull(MmsClientTransactions.forUndecodable(bytes(0x8C, 0x82, 0x8D, 0x92)))
        assertNull(MmsClientTransactions.forUndecodable(ByteArray(0)))
        assertNull(MmsClientTransactions.forUndecodable(bytes(0x01, 0x02, 0x03)))
    }

    @Test
    fun peekPreambleNeverThrowsAndBoundsTheTransactionId() {
        val random = java.util.Random(7)
        repeat(2_000) {
            val junk = ByteArray(random.nextInt(64)) { random.nextInt(256).toByte() }
            MmsPduDecoder.peekPreamble(junk)
        }
        val huge = bytes(0x8C, 0x82, 0x98, "x".repeat(MmsLimits.MAX_TOKEN_CHARS + 1), 0, 0x8D, 0x92)
        val preamble = MmsPduDecoder.peekPreamble(huge)!!
        assertNull(preamble.transactionId)
        assertEquals(MessageType.NOTIFICATION_IND, preamble.messageType)
        assertEquals(MmsVersion.V1_2, preamble.mmsVersion)
    }
}
