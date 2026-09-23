package app.dak.ui.conversation

import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.core.model.TransactionDirection
import app.dak.index.MessageItem
import app.dak.index.OtpItem
import app.dak.index.TransactionItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Double-tap copy ([QuickCopy]), the pasteable amount ([plainAmount]) and reply quotes ([withQuote]). */
class QuickCopyTest {

    @Test
    fun theWindowEndsExactlyADayAfterArrival() {
        val arrived = 1_000_000L
        assertTrue(isOtpCopyable(otp(arrived), arrived))
        assertTrue(isOtpCopyable(otp(arrived), arrived + OTP_COPY_WINDOW_MILLIS - 1))
        assertFalse(isOtpCopyable(otp(arrived), arrived + OTP_COPY_WINDOW_MILLIS))
        // A code dated far in the future (a wrong phone clock) is fresh, never expired early.
        assertTrue(isOtpCopyable(otp(arrived + 365 * OTP_COPY_WINDOW_MILLIS), arrived))
    }

    @Test
    fun aStaleCodeWithoutAnAmountCopiesNothing() {
        val arrived = 0L
        assertNull(QuickCopy.of(otp(arrived), arrived + OTP_COPY_WINDOW_MILLIS))
        assertNull(QuickCopy.of(item(), otpCopyable = true), "no OTP, no amount")
        assertNull(QuickCopy.of(item(otp = OtpItem("", null, null, false)), otpCopyable = true))
    }

    @Test
    fun theCodeWinsOverTheAmountWhileFresh() {
        val both = otp(0).copy(transaction = txn(125_050, "INR"))
        assertEquals(QuickCopy.Code("482913"), QuickCopy.of(both, otpCopyable = true))
        assertEquals(QuickCopy.Amount("1250.50"), QuickCopy.of(both, otpCopyable = false))
    }

    @Test
    fun plainAmountUsesTheCurrencysMinorUnitsAndNoSign() {
        assertEquals("1250.50", plainAmount(txn(125_050, "INR")))
        assertEquals("0.05", plainAmount(txn(5, "INR")))
        assertEquals("1250", plainAmount(txn(1_250, "JPY")), "no minor unit")
        assertEquals("1.250", plainAmount(txn(1_250, "KWD")), "three decimals")
        assertEquals("12.50", plainAmount(txn(-1_250, "INR")), "never a minus sign in an amount field")
        // Grouping-free and locale-free: pastes into any banking app's amount field.
        assertEquals("1234567.89", plainAmount(txn(123_456_789, "INR")))
        assertEquals("12.50", plainAmount(txn(1_250, "XYZ")), "unknown currency: two decimals")
    }

    @Test
    fun aQuotedOtpIsMaskedSoAReplyCannotLeakIt() {
        val quoted = withQuote("thanks", otp(0))
        assertEquals("> •••• is your OTP\nthanks", quoted)
        assertFalse(quoted.contains("482913"))
        // Every occurrence is masked.
        val twice = item(body = "482913 is your OTP. Never share 482913.", otp = OtpItem("482913", null, null, false))
        assertFalse(withQuote("", twice).contains("482913"))
    }

    @Test
    fun quotesAreOneBoundedLineAndAreNotAddedTwice() {
        val long = item(body = "line one\n\nline   two\t" + "x".repeat(200))
        val quoted = withQuote("", long)
        val quoteLine = quoted.removeSuffix("\n")
        assertFalse(quoteLine.contains('\n'))
        assertTrue(quoteLine.startsWith("> line one line two x"), quoteLine)
        assertTrue(quoteLine.endsWith("…"))
        assertTrue(quoteLine.length <= "> ".length + 80 + 1, "length ${quoteLine.length}")
        // Quoting the same message again (double tap on Reply) does not stack quotes.
        assertEquals(quoted + "draft", withQuote(quoted + "draft", long))
    }

    private fun txn(minor: Long, currency: String) = TransactionItem(TransactionDirection.DEBIT, minor, currency, null, null, null)

    private fun otp(date: Long) =
        item(body = "482913 is your OTP", date = date).copy(category = Category.OTP, otp = OtpItem("482913", null, null, false))

    private fun item(body: String = "hello", date: Long = 0, otp: OtpItem? = null) = MessageItem(
        key = MessageKey(MessageKind.SMS, 1),
        conversationId = "c1",
        threadId = 1,
        address = "VM-BANKX",
        body = body,
        dateMillis = date,
        box = MessageBox.INBOX,
        read = true,
        subId = 1,
        attachments = emptyList(),
        category = Category.PERSONAL,
        confidence = 1f,
        canonicalSender = null,
        labels = emptySet(),
        otp = otp,
        transaction = null,
        hasLink = false,
        starred = false,
        archived = false,
        enriched = true,
    )
}
