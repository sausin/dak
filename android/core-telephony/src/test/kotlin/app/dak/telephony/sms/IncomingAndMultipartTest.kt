package app.dak.telephony.sms

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class IncomingSmsPolicyTest {

    private fun handling(pid: Int = 0, classZero: Boolean = false, mwi: Boolean = false, format: String? = "3gpp") =
        IncomingSmsPolicy.handling(format, pid, classZero, mwi)

    @Test
    fun ordinaryMessagesAreStored() {
        assertEquals(IncomingHandling.STORE, handling())
        assertEquals(IncomingHandling.STORE, handling(pid = 0x00, format = null))
        // Other PIDs (e.g. telematic interworking 0x21, return call 0x5F) are ordinary messages to the app.
        assertEquals(IncomingHandling.STORE, handling(pid = 0x21))
        assertEquals(IncomingHandling.STORE, handling(pid = 0x5F))
    }

    @Test
    fun typeZeroIsDroppedEvenIfFlaggedClassZero() {
        assertEquals(IncomingHandling.DROP_TYPE_ZERO, handling(pid = 0x40))
        assertEquals(IncomingHandling.DROP_TYPE_ZERO, handling(pid = 0x40, classZero = true))
    }

    @Test
    fun replaceTypesOneToSeven() {
        for (pid in 0x41..0x47) assertEquals(IncomingHandling.REPLACE, handling(pid = pid), "pid 0x%02X".format(pid))
        assertEquals(IncomingHandling.STORE, handling(pid = 0x48))
        assertTrue(IncomingSmsPolicy.isReplacePid(0x41))
        assertFalse(IncomingSmsPolicy.isReplacePid(0x40))
    }

    @Test
    fun classZeroIsFlashAndWinsOverReplace() {
        assertEquals(IncomingHandling.FLASH, handling(classZero = true))
        assertEquals(IncomingHandling.FLASH, handling(pid = 0x41, classZero = true))
    }

    @Test
    fun discardMwiIsDropped() {
        assertEquals(IncomingHandling.DROP_MWI, handling(mwi = true))
    }

    @Test
    fun pidRulesDoNotApplyToCdma() {
        assertEquals(IncomingHandling.STORE, handling(pid = 0x40, format = "3gpp2"))
        assertEquals(IncomingHandling.STORE, handling(pid = 0x41, format = "3gpp2"))
        assertEquals(IncomingHandling.FLASH, handling(classZero = true, format = "3gpp2"))
    }
}

/** The multipart "no blind whole-message resend" rule, simulated the way SendProgressStore applies it. */
class MultipartOutcomeTest {

    /** Feeds sent results in order; returns the outcome at the report that settled the attempt. */
    private fun run(count: Int, vararg results: Pair<Int, Boolean>): Pair<SendAttemptOutcome, PartProgress> {
        var p = PartProgress(partCount = count, attempt = 1)
        for ((part, ok) in results) {
            p = if (ok) p.withSent(part) else p.withFailedPart(part, 4)
            if (p.isSettled) return p.outcome to p
        }
        return p.outcome to p
    }

    @Test
    fun allPartsSent() {
        assertEquals(SendAttemptOutcome.SENT, run(3, 0 to true, 1 to true, 2 to true).first)
        assertEquals(SendAttemptOutcome.SENT, run(1, 0 to true).first)
    }

    @Test
    fun middlePartFailedIsPartialAndNotRetried() {
        val (outcome, p) = run(3, 0 to true, 1 to false, 2 to true)
        assertEquals(SendAttemptOutcome.PARTIAL, outcome)
        assertEquals(setOf(0, 2), p.sentParts)
        assertEquals(setOf(1), p.failedParts)
    }

    @Test
    fun nothingDecidedWhileLaterPartsAreInFlight() {
        var p = PartProgress(partCount = 3, attempt = 1).withFailedPart(0, 4)
        assertEquals(SendAttemptOutcome.IN_FLIGHT, p.outcome, "part 1 failing must not trigger a resend yet")
        p = p.withSent(1)
        assertEquals(SendAttemptOutcome.IN_FLIGHT, p.outcome)
        p = p.withSent(2)
        assertEquals(SendAttemptOutcome.PARTIAL, p.outcome)
    }

    @Test
    fun everyPartFailedMayBeRetriedAsAWhole() {
        assertEquals(SendAttemptOutcome.ALL_FAILED, run(3, 0 to false, 1 to false, 2 to false).first)
        assertEquals(SendAttemptOutcome.ALL_FAILED, run(1, 0 to false).first)
    }

    @Test
    fun lastPartOnlyOems() {
        // OEMs that only fire the last part's intent: its result decides.
        assertEquals(SendAttemptOutcome.SENT, run(3, 2 to true).first)
        assertEquals(SendAttemptOutcome.ALL_FAILED, run(3, 2 to false).first)
    }

    @Test
    fun firstFailureCodeIsKept() {
        var p = PartProgress(partCount = 2).withFailedPart(0, 4).withFailedPart(1, 2)
        assertEquals(4, p.failureCode)
        p = PartProgress(partCount = 2).withSent(0).withFailedPart(1, 17)
        assertEquals(17, p.failureCode)
        assertEquals(SendAttemptOutcome.PARTIAL, p.outcome)
    }

    @Test
    fun progressRoundTripsAndReadsTheOldFormat() {
        val p = PartProgress(
            partCount = 3, sentParts = setOf(0, 2), deliveredParts = setOf(0), failed = true, startedAtMillis = 9L,
            failedParts = setOf(1), failureCode = 4, attempt = 2, resolved = true,
        )
        assertEquals(p, PartProgress.decode(p.encode()))
        // Written by the previous version (5 fields).
        assertEquals(PartProgress(partCount = 2, sentParts = setOf(1), failed = false, startedAtMillis = 7L), PartProgress.decode("2;1;;0;7"))
        assertNull(PartProgress.decode("2;1;;0;7;extra"))
    }

    @Test
    fun partialDescriptionIsAccurate() {
        val text = SmsResultCodes.describePartial(2, 3)
        assertTrue(text.contains("2 of 3"), text)
    }
}

class RespondViaMessageUriTest {

    @Test
    fun bodyIsDecodedExactlyOnceAndKeepsAmpersandAndPercent() {
        assertEquals("a&b%c", RespondViaMessage.body("+1?body=a%26b%25c"))
        // "%2525" is a literal "%25" after one decode, not "%".
        assertEquals("50%25 off", RespondViaMessage.body("+1?body=50%2525%20off"))
        assertEquals("50% off", RespondViaMessage.body("+1?body=50%25%20off"))
    }

    @Test
    fun otherHfieldsAreIgnoredAndBodyIsCaseInsensitive() {
        assertEquals("x", RespondViaMessage.body("+1?foo=1&body=x"))
        assertEquals("x", RespondViaMessage.body("+1?BODY=x&foo=2"))
        assertEquals("", RespondViaMessage.body("+1?body"))
        assertNull(RespondViaMessage.body("+1?foo=1"))
        assertNull(RespondViaMessage.body("+1"))
        assertNull(RespondViaMessage.body(null))
    }

    @Test
    fun plusIsAPlusAndUnicodeSurvives() {
        assertEquals("1+1=2", RespondViaMessage.body("+1?body=1+1%3D2"))
        assertEquals("नमस्ते 🙏", RespondViaMessage.body("+1?body=%E0%A4%A8%E0%A4%AE%E0%A4%B8%E0%A5%8D%E0%A4%A4%E0%A5%87%20%F0%9F%99%8F"))
        assertEquals("नमस्ते", RespondViaMessage.body("+1?body=नमस्ते"))
    }

    @Test
    fun malformedEscapesAreKeptLiterally() {
        assertEquals("100%", RespondViaMessage.body("+1?body=100%"))
        assertEquals("%zz", RespondViaMessage.body("+1?body=%zz"))
        assertEquals("%4", RespondViaMessage.body("+1?body=%4"))
    }

    @Test
    fun recipientsSplitOnCommaOrSemicolonAndAreDecoded() {
        assertEquals(listOf("+15551234", "+15559876"), RespondViaMessage.recipients("+15551234,+15559876"))
        assertEquals(listOf("+15551234", "5559876"), RespondViaMessage.recipients(" +15551234 ; 5559876;;+15551234?body=hi"))
        assertEquals(listOf("+15551234", "5559876"), RespondViaMessage.recipients("%2B15551234,5559876"))
        assertEquals(listOf("+91 98765 43210"), RespondViaMessage.recipients("%2B91%2098765%2043210"))
        assertEquals(listOf("+15551234"), RespondViaMessage.recipients("//+15551234?body=x"))
        assertEquals(emptyList(), RespondViaMessage.recipients(null))
        assertEquals(emptyList(), RespondViaMessage.recipients("?body=x"))
    }
}
