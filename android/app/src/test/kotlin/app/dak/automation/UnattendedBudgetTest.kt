package app.dak.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure halves of [UnattendedSendLimits]: the daily budget and the per-sender auto-reply cooldown. */
class UnattendedBudgetTest {

    // UnattendedSendLimits.REPLY_COOLDOWN_MILLIS / DAILY_LIMIT (literal so this stays a pure JVM test).
    private val cooldown = 30 * 60_000L
    private val dailyLimit = 150

    @Test
    fun theDailyLimitIsExactAndTheUserIsToldOncePerDay() {
        var budget = UnattendedBudget(day = Long.MIN_VALUE, count = 0, notifiedDay = Long.MIN_VALUE)
        var allowed = 0
        var notifications = 0
        repeat(dailyLimit + 50) {
            val d = budget.consume(today = 7, limit = dailyLimit)
            if (d.allowed) allowed++
            if (d.notify) notifications++
            budget = d.budget
        }
        assertEquals(dailyLimit, allowed)
        assertEquals(1, notifications)
        // The next day: a fresh budget and a fresh notification when it runs out again.
        var next = budget
        repeat(dailyLimit) { next = next.consume(today = 8, limit = dailyLimit).budget }
        val refused = next.consume(today = 8, limit = dailyLimit)
        assertFalse(refused.allowed)
        assertTrue(refused.notify)
    }

    @Test
    fun aRefusalDoesNotConsumeAndAZeroLimitRefusesEverything() {
        val spent = UnattendedBudget(day = 3, count = 2, notifiedDay = 3)
        val d = spent.consume(today = 3, limit = 2)
        assertFalse(d.allowed)
        assertFalse(d.notify, "already told today")
        assertEquals(2, d.budget.count)
        assertFalse(UnattendedBudget(Long.MIN_VALUE, 0, Long.MIN_VALUE).consume(today = 1, limit = 0).allowed)
    }

    @Test
    fun aStoredCountFromAnotherDayIsNeverCarriedOver() {
        // Including an earlier day (the clock or time zone moved back): the stored count belongs to another day.
        val d = UnattendedBudget(day = 10, count = 150, notifiedDay = 10).consume(today = 9, limit = 150)
        assertTrue(d.allowed)
        assertEquals(UnattendedBudget(day = 9, count = 1, notifiedDay = 10), d.budget)
    }

    @Test
    fun oneReplyPerSenderPerCooldown() {
        val c = ReplyCooldown(cooldown, maxTracked = 500)
        assertTrue(c.allow("a", 0))
        assertFalse(c.allow("a", 1))
        assertFalse(c.allow("a", cooldown - 1))
        assertTrue(c.allow("b", 1), "other senders are independent")
        assertTrue(c.allow("a", cooldown), "the cooldown is over exactly after its length")
        assertFalse(c.allow("a", cooldown + 1), "and restarts from the reply that went")
    }

    @Test
    fun aClockMovedBackwardsDoesNotBlockRepliesForever() {
        val c = ReplyCooldown(cooldown, maxTracked = 500)
        assertTrue(c.allow("a", 10 * cooldown))
        // Now is before the last reply (negative elapsed time): allowed rather than blocked until the clock catches up.
        assertTrue(c.allow("a", 0))
    }

    @Test
    fun trackingIsBoundedButNeverForgetsASenderStillInCooldown() {
        val c = ReplyCooldown(cooldown, maxTracked = 3)
        for (i in 0 until 3) assertTrue(c.allow("old$i", 0))
        // Past the bound, only entries whose cooldown is over are forgotten.
        assertTrue(c.allow("fresh", cooldown))
        assertEquals(1, c.trackedCount)
        for (i in 0 until 5) assertTrue(c.allow("n$i", cooldown + 1))
        assertEquals(6, c.trackedCount, "all still cooling down: kept, even over the bound")
        assertFalse(c.allow("n0", cooldown + 2))
        assertFalse(c.allow("fresh", cooldown + 2))
    }

    @Test
    fun replyKeysFoldNumberSpellingsButNotDifferentSenders() {
        assertEquals(ReplyCooldown.keyOf("+91 98765 43210"), ReplyCooldown.keyOf("098765-43210"))
        assertEquals(ReplyCooldown.keyOf("+919876543210"), ReplyCooldown.keyOf("9876543210"))
        assertEquals("vm-hdfcbk", ReplyCooldown.keyOf(" VM-HDFCBK "))
        assertEquals(ReplyCooldown.keyOf("VM-HDFCBK"), ReplyCooldown.keyOf("vm-hdfcbk"))
        // Short codes keep their digits as written.
        assertEquals("56070", ReplyCooldown.keyOf("56070"))
        assertTrue(ReplyCooldown.keyOf("9876543210") != ReplyCooldown.keyOf("9876543211"))
    }
}
