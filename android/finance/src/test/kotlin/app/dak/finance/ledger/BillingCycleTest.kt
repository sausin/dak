package app.dak.finance.ledger

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BillingCycleTest {

    private fun millis(y: Int, m: Int, d: Int, hour: Int = 0): Long =
        LocalDate.of(y, m, d).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `statement day must be a day of month`() {
        assertFailsWith<IllegalArgumentException> { BillingCycle(0) }
        assertFailsWith<IllegalArgumentException> { BillingCycle(32) }
        BillingCycle(1)
        BillingCycle(31)
    }

    @Test
    fun `the statement date is on or after the given day`() {
        val cycle = BillingCycle(15)
        assertEquals(LocalDate.of(2026, 9, 15), cycle.statementDateFor(millis(2026, 9, 1)))
        assertEquals(LocalDate.of(2026, 9, 15), cycle.statementDateFor(millis(2026, 9, 15, hour = 23)))
        assertEquals(LocalDate.of(2026, 10, 15), cycle.statementDateFor(millis(2026, 9, 16)))
        // Year rollover.
        assertEquals(LocalDate.of(2027, 1, 15), cycle.statementDateFor(millis(2026, 12, 20)))
    }

    @Test
    fun `a statement day past the month's end falls on its last day`() {
        val cycle = BillingCycle(31)
        assertEquals(LocalDate.of(2026, 2, 28), cycle.statementDateFor(millis(2026, 2, 10)))
        assertEquals(LocalDate.of(2028, 2, 29), cycle.statementDateFor(millis(2028, 2, 10))) // leap year
        assertEquals(LocalDate.of(2026, 4, 30), cycle.statementDateFor(millis(2026, 4, 1)))
        assertEquals(LocalDate.of(2026, 3, 31), cycle.statementDateFor(millis(2026, 3, 1)))
    }

    @Test
    fun `cycle range starts the day after the previous statement and ends after the statement day`() {
        val range = BillingCycle(5).cycleRange(millis(2026, 8, 22))
        assertEquals(millis(2026, 8, 6), range.first)
        assertEquals(millis(2026, 9, 6) - 1, range.last)
        // Consecutive cycles tile time with no gap or overlap.
        val next = BillingCycle(5).cycleRange(millis(2026, 9, 6))
        assertEquals(range.last + 1, next.first)
    }

    @Test
    fun `cycle range across a short month with a clamped statement day`() {
        // Statement on the 31st: February's statement is on the 28th, so March's cycle starts on 1 March.
        val range = BillingCycle(31).cycleRange(millis(2026, 3, 10))
        assertEquals(millis(2026, 3, 1), range.first)
        assertEquals(millis(2026, 4, 1) - 1, range.last)
    }
}
