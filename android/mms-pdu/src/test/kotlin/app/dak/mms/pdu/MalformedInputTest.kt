package app.dak.mms.pdu

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MalformedInputTest {

    private fun failure(bytes: ByteArray): PduError {
        val result = MmsPduDecoder.decode(bytes)
        return assertIs<PduDecodeResult.Failure>(result, "expected failure for ${hex(bytes)}").error
    }

    /** Decodes and asserts no internal bug surfaced (the decoder maps unexpected exceptions to offset -1). */
    private fun decodeNoBug(bytes: ByteArray) {
        val result = MmsPduDecoder.decode(bytes)
        if (result is PduDecodeResult.Failure) {
            val e = result.error
            if (e is PduError.Malformed && e.offset == -1) throw AssertionError("internal decoder error for ${hex(bytes)}: ${e.detail}")
        }
    }

    @Test
    fun emptyInput() {
        assertEquals(PduError.Empty, failure(ByteArray(0)))
    }

    @Test
    fun missingMessageType() {
        assertEquals(PduError.UnsupportedMessageType(-1), failure(bytes(0x8D, 0x92)))
    }

    @Test
    fun unsupportedMessageType() {
        assertEquals(PduError.UnsupportedMessageType(0x89), failure(bytes(0x8C, 0x89, 0x8D, 0x92)))
    }

    @Test
    fun notificationWithoutContentLocation() {
        assertEquals(PduError.MissingHeader("X-Mms-Content-Location"), failure(bytes(0x8C, 0x82, 0x98, text("t"))))
    }

    @Test
    fun sendConfWithoutStatus() {
        assertIs<PduError.MissingHeader>(failure(bytes(0x8C, 0x81, 0x8D, 0x92)))
    }

    @Test
    fun unterminatedTextIsTruncated() {
        assertIs<PduError.Truncated>(failure(bytes(0x8C, 0x82, 0x83, "http://no-terminator")))
    }

    @Test
    fun lengthPastEndIsTruncated() {
        assertIs<PduError.Truncated>(failure(bytes(0x8C, 0x82, 0x88, 0x05, 0x81, 0x01)))
    }

    @Test
    fun badTimeTokenIsMalformed() {
        assertIs<PduError.Malformed>(failure(bytes(0x8C, 0x82, 0x88, 0x02, 0x85, 0x81, 0x83, text("x"))))
    }

    @Test
    fun strayControlOctetInHeadersIsMalformed() {
        assertIs<PduError.Malformed>(failure(bytes(0x8C, 0x82, 0x05, 0x83, text("x"))))
    }

    @Test
    fun absurdPartCountIsMalformed() {
        assertIs<PduError.Malformed>(failure(bytes(0x8C, 0x84, 0x84, 0xA3, 0x8F, 0xFF, 0xFF, 0x7F, 0x00)))
    }

    @Test
    fun partDataPastEndIsTruncated() {
        assertIs<PduError.Truncated>(failure(bytes(0x8C, 0x84, 0x84, 0xA3, 0x01, 0x01, 0x10, 0x83, "short")))
    }

    @Test
    fun everyTruncationOfValidPdusFailsCleanly() {
        val fixtures = listOf(
            MmsPduEncoder.encode(
                MmsMessageBuilder.build(
                    to = listOf("+15551234567", "+15557654321"),
                    text = "Hello ✓",
                    attachments = listOf(MmsMessageBuilder.Attachment("image/png", "a.png", ByteArray(40) { it.toByte() })),
                    subject = "Sübject",
                    transactionId = "T1",
                ),
            ),
            MmsPduEncoder.encode(NotificationInd(contentLocation = "http://m/x", transactionId = "t", from = "+1555", messageSize = 1234, expiry = MmsTime.Relative(3600))),
        )
        for (full in fixtures) {
            for (len in 0 until full.size) {
                // Must return (success for a few prefixes that happen to be complete, failure otherwise) and never throw.
                decodeNoBug(full.copyOf(len))
            }
        }
    }

    @Test
    fun randomGarbageNeverThrows() {
        val random = Random(1234)
        repeat(20_000) {
            val size = random.nextInt(0, 96)
            val data = random.nextBytes(size)
            if (size > 1 && random.nextBoolean()) {
                data[0] = 0x8C.toByte()
                data[1] = (0x80 + random.nextInt(0, 9)).toByte()
            }
            decodeNoBug(data)
        }
    }

    @Test
    fun randomMutationsOfValidPduNeverThrow() {
        val base = MmsPduEncoder.encode(
            MmsMessageBuilder.build(
                to = listOf("+15551234567"),
                text = "mutate me",
                attachments = listOf(MmsMessageBuilder.Attachment("image/jpeg", "p.jpg", ByteArray(64) { 7 })),
                transactionId = "T9",
            ),
        )
        val random = Random(99)
        repeat(20_000) {
            val copy = base.copyOf()
            repeat(random.nextInt(1, 4)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
            decodeNoBug(copy)
        }
    }
}
