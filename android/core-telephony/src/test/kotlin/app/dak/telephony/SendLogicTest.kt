package app.dak.telephony

import app.dak.telephony.mms.MmsResultCodes
import app.dak.telephony.send.RetryPolicy
import app.dak.telephony.send.SendRateLimiter
import app.dak.telephony.sms.DeliveryOutcome
import app.dak.telephony.sms.DeliveryStatus
import app.dak.telephony.sms.PartProgress
import app.dak.telephony.sms.RespondViaMessage
import app.dak.telephony.sms.SmsResultCodes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendLogicTest {

    @Test
    fun limiterAllowsThirtyThenSpreads() {
        val window = 30 * 60_000L
        val limiter = SendRateLimiter()
        val now = 1_000_000L
        repeat(30) { assertEquals(0L, limiter.reserve(now + it)) }
        // The 31st waits until the first reservation leaves the window.
        assertEquals(window, limiter.reserve(now + 30) + 30)
        // The 32nd is booked after the second one leaves.
        assertEquals(window + 1, limiter.reserve(now + 30) + 30)
    }

    @Test
    fun limiterNeverExceedsTheWindowForBulkSends() {
        val limiter = SendRateLimiter(maxPerWindow = 3, windowMillis = 100)
        val slots = (0 until 10).map { limiter.reserve(0) }
        assertEquals(listOf(0L, 0L, 0L, 100L, 100L, 100L, 200L, 200L, 200L, 300L), slots)
        for (t in 0L..400L) {
            val inWindow = slots.count { it in (t - 99)..t }
            assertTrue(inWindow <= 3, "window ending at $t has $inWindow")
        }
    }

    @Test
    fun limiterFreesSlotsAsTimePasses() {
        val limiter = SendRateLimiter(maxPerWindow = 2, windowMillis = 100)
        assertEquals(0L, limiter.reserve(0))
        assertEquals(0L, limiter.reserve(10))
        assertEquals(90L, limiter.reserve(10))
        assertEquals(0L, limiter.reserve(500))
        assertEquals(1, limiter.pending(500))
    }

    @Test
    fun retryBackoffIsExponentialAndCapped() {
        assertEquals(30_000L, RetryPolicy.delayMillis(1))
        assertEquals(60_000L, RetryPolicy.delayMillis(2))
        assertEquals(120_000L, RetryPolicy.delayMillis(3))
        assertEquals(30 * 60_000L, RetryPolicy.delayMillis(20))
        assertTrue(RetryPolicy.shouldRetry(1, RetryPolicy.MAX_SMS_ATTEMPTS, retryable = true))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.MAX_SMS_ATTEMPTS, RetryPolicy.MAX_SMS_ATTEMPTS, retryable = true))
        assertFalse(RetryPolicy.shouldRetry(1, RetryPolicy.MAX_SMS_ATTEMPTS, retryable = false))
    }

    @Test
    fun smsResultCodes() {
        assertTrue(SmsResultCodes.isRetryable(SmsResultCodes.NO_SERVICE))
        assertTrue(SmsResultCodes.isRetryable(SmsResultCodes.RADIO_OFF))
        assertTrue(SmsResultCodes.isRetryable(SmsResultCodes.GENERIC_FAILURE))
        assertFalse(SmsResultCodes.isRetryable(SmsResultCodes.FDN_CHECK_FAILURE))
        assertFalse(SmsResultCodes.isRetryable(SmsResultCodes.SHORT_CODE_NEVER_ALLOWED))
        assertEquals("No mobile service", SmsResultCodes.describe(SmsResultCodes.NO_SERVICE))
        assertTrue(SmsResultCodes.describe(999).contains("999"))
    }

    @Test
    fun mmsResultCodes() {
        assertTrue(MmsResultCodes.isRetryable(MmsResultCodes.IO_ERROR))
        assertFalse(MmsResultCodes.isRetryable(MmsResultCodes.INVALID_APN))
        assertFalse(MmsResultCodes.isRetryable(MmsResultCodes.DATA_DISABLED))
        assertEquals("The carrier's MMS server returned an error (HTTP 404)", MmsResultCodes.describe(MmsResultCodes.HTTP_FAILURE, 404))
    }

    @Test
    fun gsmDeliveryStatus() {
        assertEquals(DeliveryOutcome.DELIVERED, DeliveryStatus.outcome(0x00, "3gpp"))
        assertEquals(DeliveryOutcome.DELIVERED, DeliveryStatus.outcome(0x02, null))
        assertEquals(DeliveryOutcome.PENDING, DeliveryStatus.outcome(0x20, "3gpp"))
        assertEquals(DeliveryOutcome.FAILED, DeliveryStatus.outcome(0x41, "3gpp"))
        assertEquals(DeliveryOutcome.FAILED, DeliveryStatus.outcome(0x60, "3gpp"))
        assertEquals(0, DeliveryStatus.providerStatus(DeliveryOutcome.DELIVERED))
        assertEquals(32, DeliveryStatus.providerStatus(DeliveryOutcome.PENDING))
        assertEquals(64, DeliveryStatus.providerStatus(DeliveryOutcome.FAILED))
    }

    @Test
    fun cdmaDeliveryStatus() {
        fun cdma(errorClass: Int, code: Int) = (errorClass shl 24) or (code shl 16)
        assertEquals(DeliveryOutcome.DELIVERED, DeliveryStatus.outcome(cdma(0, 2), "3gpp2"))
        assertEquals(DeliveryOutcome.PENDING, DeliveryStatus.outcome(cdma(0, 0), "3gpp2"))
        assertEquals(DeliveryOutcome.PENDING, DeliveryStatus.outcome(cdma(2, 4), "3gpp2"))
        assertEquals(DeliveryOutcome.FAILED, DeliveryStatus.outcome(cdma(3, 4), "3gpp2"))
    }

    @Test
    fun partProgressHandlesAllPartsOrLastOnlyOems() {
        val p = PartProgress(partCount = 3)
        assertFalse(p.withSent(0).isFullySent)
        assertTrue(p.withSent(0).withSent(1).withSent(2).isFullySent)
        assertTrue(p.withSent(2).isFullySent, "OEMs that only report the last part")
        assertFalse(p.withSent(0).withFailure().withSent(2).isFullySent)
        assertTrue(p.withDelivered(2).isFullyDelivered)
        assertFalse(p.withDelivered(0).isFullyDelivered)
    }

    @Test
    fun partProgressRoundTrips() {
        val p = PartProgress(partCount = 4, sentParts = setOf(0, 2), deliveredParts = setOf(1), failed = true, startedAtMillis = 123L)
        assertEquals(p, PartProgress.decode(p.encode()))
        assertEquals(PartProgress(1, startedAtMillis = 5), PartProgress.decode(PartProgress(1, startedAtMillis = 5).encode()))
        assertNull(PartProgress.decode("junk"))
        assertNull(PartProgress.decode(null))
    }

    @Test
    fun respondViaMessageRecipients() {
        assertEquals(listOf("+15551234", "+15559876"), RespondViaMessage.recipients("+15551234,+15559876"))
        assertEquals(listOf("+15551234", "5559876"), RespondViaMessage.recipients(" +15551234 ; 5559876;;+15551234?body=hi"))
        assertEquals(emptyList(), RespondViaMessage.recipients(null))
        assertEquals("hi there", RespondViaMessage.body("+1555?body=hi there"))
        assertNull(RespondViaMessage.body("+1555"))
    }
}
