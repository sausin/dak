package app.dak.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeatCollapseTest {

    @Test
    fun `template masks digits and number separators`() {
        assertEquals(
            RepeatCollapse.template("Your A/c balance is Rs 1,200.50 on 12-03"),
            RepeatCollapse.template("Your  a/c balance is Rs 98,450.00 on 13-03"),
        )
        assertEquals("otp is #", RepeatCollapse.template(" OTP is 123456 "))
    }

    @Test
    fun `exact keeps digits`() {
        assertTrue(RepeatCollapse.exact("Meet at 5") != RepeatCollapse.exact("Meet at 6"))
        assertEquals(RepeatCollapse.exact("Hello  there"), RepeatCollapse.exact("hello there "))
    }

    @Test
    fun `repeat only inside the window`() {
        val w = RepeatCollapse.WINDOW_MILLIS
        assertTrue(RepeatCollapse.isRepeat("a", 1_000, "a", 1_000 + w))
        assertFalse(RepeatCollapse.isRepeat("a", 1_000, "a", 1_001 + w))
        assertFalse(RepeatCollapse.isRepeat("a", 1_000, "b", 1_001))
        assertFalse(RepeatCollapse.isRepeat(null, 0, "a", 1))
        assertFalse(RepeatCollapse.isRepeat("", 0, "", 1))
        assertFalse(RepeatCollapse.isRepeat("a", 5_000, "a", 1_000), "clock going backwards is not a repeat")
    }

    @Test
    fun `withCount adds suffix from two`() {
        assertEquals("hi", RepeatCollapse.withCount("hi", 1))
        assertEquals("hi ×3", RepeatCollapse.withCount("hi", 3))
    }

    @Test
    fun `next counts repeats and keeps the latest text`() {
        var s = RepeatCollapse.next(null, "Bal Rs 10", "k", 0)
        assertEquals(1, s.count)
        assertFalse(s.isRepeat)
        s = RepeatCollapse.next(s, "Bal Rs 20", "k", 1_000)
        s = RepeatCollapse.next(s, "Bal Rs 30", "k", 2_000)
        assertEquals(3, s.count)
        assertEquals(listOf("Bal Rs 30"), s.lines)
        assertEquals(listOf("Bal Rs 30 ×3"), s.displayLines())
        assertEquals(3, s.total)
    }

    @Test
    fun `next appends distinct messages up to the line limit`() {
        var s: RepeatCollapse.State? = null
        repeat(RepeatCollapse.MAX_LINES + 2) { i -> s = RepeatCollapse.next(s, "msg $i", "k$i", i * 10L) }
        val state = s!!
        assertEquals(RepeatCollapse.MAX_LINES, state.lines.size)
        assertEquals("msg ${RepeatCollapse.MAX_LINES + 1}", state.lines.last())
        assertEquals(RepeatCollapse.MAX_LINES + 2, state.total)
        assertEquals(1, state.count)
    }

    @Test
    fun `repeat after window starts a new line`() {
        val first = RepeatCollapse.next(null, "x", "k", 0)
        val later = RepeatCollapse.next(first, "x", "k", RepeatCollapse.WINDOW_MILLIS + 1)
        assertEquals(listOf("x", "x"), later.lines)
        assertEquals(1, later.count)
    }

    @Test
    fun `sim channel ids round trip`() {
        val id = SimChannelIds.channelId(NotificationChannels.OTP, 1)
        assertEquals("otp.sim2", id)
        assertEquals(NotificationChannels.OTP, SimChannelIds.baseOf(id))
        assertEquals(1, SimChannelIds.slotOf(id))
        assertNull(SimChannelIds.baseOf(NotificationChannels.OTP_CONSUMED))
        assertNull(SimChannelIds.slotOf("personal"))
        assertEquals(NotificationChannels.SPAM, ChannelCatalog.specOf(SimChannelIds.channelId(NotificationChannels.SPAM, 0))?.id)
    }

    @Test
    fun `conversation channel ids round trip`() {
        val id = ConversationChannels.channelIdFor("m:HDFCBK")
        assertEquals("m:HDFCBK", ConversationChannels.conversationIdOf(id))
        assertNull(ConversationChannels.conversationIdOf("personal"))
    }
}
