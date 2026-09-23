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

class MessageSelectionTest {

    @Test
    fun toggleAddsThenRemoves() {
        val once = MessageSelection.toggled(emptySet(), "sms:1")
        assertEquals(setOf("sms:1"), once)
        assertEquals(setOf("sms:1", "mms:2"), MessageSelection.toggled(once, "mms:2"))
        assertEquals(emptySet<String>(), MessageSelection.toggled(once, "sms:1"))
    }

    @Test
    fun selectAllKeepsExistingAndAddsLoaded() {
        val all = MessageSelection.withAll(setOf("sms:9"), listOf("sms:1", "sms:2", "sms:1"))
        assertEquals(setOf("sms:9", "sms:1", "sms:2"), all)
    }

    @Test
    fun resolveReturnsLoadedSelectedMessagesOldestFirst() {
        // Paging hands the thread newest first.
        val loaded = listOf(message(3, "third", date = 300), message(2, "second", date = 200), message(1, "first", date = 100))
        val resolved = MessageSelection.resolve(setOf("sms:1", "sms:3", "sms:404"), loaded)
        assertEquals(listOf("first", "third"), resolved.map { it.body })
    }

    @Test
    fun resolveBreaksDateTiesByProviderId() {
        val loaded = listOf(message(8, "b", date = 100), message(7, "a", date = 100))
        assertEquals(listOf("a", "b"), MessageSelection.resolve(setOf("sms:7", "sms:8"), loaded).map { it.body })
    }

    @Test
    fun copyTextSeparatesMessagesWithABlankLineAndSkipsEmptyBodies() {
        val ordered = listOf(message(1, "  Hi\n"), message(2, ""), message(3, "Line one\nLine two"), message(4, "   "))
        assertEquals("Hi\n\nLine one\nLine two", MessageSelection.copyText(ordered))
    }

    @Test
    fun copyTextOfNothingIsEmpty() {
        assertEquals("", MessageSelection.copyText(emptyList()))
        assertEquals("", MessageSelection.joinBodies(listOf("", " ")))
    }

    @Test
    fun otpIsCopyableForLessThanADay() {
        val now = 10 * DAY
        assertTrue(isOtpCopyable(otp(date = now - 60_000), now))
        assertTrue(isOtpCopyable(otp(date = now - (DAY - 1)), now))
        assertFalse(isOtpCopyable(otp(date = now - DAY), now))
        assertFalse(isOtpCopyable(otp(date = now - 3 * DAY), now))
        assertTrue(isOtpCopyable(otp(date = now + 30_000), now)) // clock skew: slightly in the future
    }

    @Test
    fun onlyOtpMessagesAreCopyable() {
        assertFalse(isOtpCopyable(message(1, "hello", date = 0), 0))
        assertFalse(isOtpCopyable(message(1, "code", date = 0).copy(otp = OtpItem("  ", null, null, false)), 0))
    }

    @Test
    fun quickCopyDropsAStaleCodeButKeepsTheAmount() {
        val now = 10 * DAY
        assertEquals(QuickCopy.Code("482913"), QuickCopy.of(otp(date = now - 60_000), now))
        assertNull(QuickCopy.of(otp(date = now - 2 * DAY), now))

        val paid = otp(date = now - 2 * DAY).copy(
            transaction = TransactionItem(TransactionDirection.DEBIT, 125_050, "INR", null, null, null),
        )
        assertEquals(QuickCopy.Amount("1250.50"), QuickCopy.of(paid, now))
        assertEquals(QuickCopy.Code("482913"), QuickCopy.of(paid, otpCopyable = true))
    }

    private fun otp(date: Long) =
        message(1, "482913 is your OTP", date = date).copy(category = Category.OTP, otp = OtpItem("482913", null, null, false))

    private fun message(id: Long, body: String, date: Long = id * 1_000) = MessageItem(
        key = MessageKey(MessageKind.SMS, id),
        conversationId = "c1",
        threadId = 1,
        address = "+911234567890",
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
        otp = null,
        transaction = null,
        hasLink = false,
        starred = false,
        archived = false,
        enriched = true,
    )

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
