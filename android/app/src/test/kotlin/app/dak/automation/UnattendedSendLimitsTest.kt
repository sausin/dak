package app.dak.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnattendedSendLimitsTest {

    @Test
    fun `budget allows up to the limit per day, then refuses and notifies once`() {
        var budget = UnattendedBudget(day = Long.MIN_VALUE, count = 0, notifiedDay = Long.MIN_VALUE)
        repeat(3) {
            val d = budget.consume(today = 100, limit = 3)
            assertTrue(d.allowed)
            budget = d.budget
        }
        val first = budget.consume(today = 100, limit = 3)
        assertFalse(first.allowed)
        assertTrue(first.notify)
        val second = first.budget.consume(today = 100, limit = 3)
        assertFalse(second.allowed)
        assertFalse(second.notify)
    }

    @Test
    fun `a new day resets the budget`() {
        val spent = UnattendedBudget(day = 100, count = 3, notifiedDay = 100)
        val d = spent.consume(today = 101, limit = 3)
        assertTrue(d.allowed)
        assertEquals(1, d.budget.count)
    }

    @Test
    fun `reply key folds the ways a number is written`() {
        assertEquals(UnattendedSendLimits.replyKey("+91 98765 43210"), UnattendedSendLimits.replyKey("09876543210"))
        assertEquals("vm-hdfcbk", UnattendedSendLimits.replyKey(" VM-HDFCBK "))
    }
}
