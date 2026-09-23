package app.dak.finance.reconcile

import app.dak.core.model.TransactionDirection
import app.dak.finance.money.Money
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconcilerTest {

    private val day = TimeUnit.DAYS.toMillis(1)

    private fun estimate(key: String, date: Long, homeMinor: Long, homeCurrency: String = "INR") = app.dak.finance.ledger.LedgerEntry(
        messageKey = key,
        dateMillis = date,
        direction = TransactionDirection.DEBIT,
        original = Money(12050, "AED"),
        indicativeHome = Money(homeMinor, homeCurrency),
        settled = false,
    )

    private fun settlement(key: String, date: Long, amountMinor: Long, currency: String = "INR", direction: TransactionDirection = TransactionDirection.DEBIT) =
        app.dak.finance.ledger.LedgerEntry(
            messageKey = key,
            dateMillis = date,
            direction = direction,
            original = Money(amountMinor, currency),
            indicativeHome = Money(amountMinor, currency),
            settled = true,
        )

    @Test
    fun `matches a settlement within tolerance and date proximity, replacing the indicative value`() {
        val est = estimate("est1", 0L, 1000000L) // ~ Rs 10,000 indicative
        val actual = settlement("settle1", 2 * day, 1030000L) // Rs 10,300 actual (3% markup)

        val result = Reconciler.reconcile(listOf(est, actual))

        assertEquals(1, result.entries.size) // settlement merged into the estimate
        assertEquals(1, result.matches.size)
        val merged = result.entries.single()
        assertTrue(merged.settled)
        assertEquals(Money(1030000L, "INR"), merged.indicativeHome)
        assertEquals(BigDecimal("3.00"), result.matches.single().markupPercent)
    }

    @Test
    fun `does not match a settlement outside the tolerance band`() {
        val est = estimate("est1", 0L, 1000000L)
        val tooFarOff = settlement("settle1", 2 * day, 1500000L) // 50% off, way outside +-6%

        val result = Reconciler.reconcile(listOf(est, tooFarOff))

        assertEquals(2, result.entries.size) // nothing merged
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `does not match a settlement outside the date window`() {
        val est = estimate("est1", 0L, 1000000L)
        val tooLate = settlement("settle1", 10 * day, 1010000L)

        val result = Reconciler.reconcile(listOf(est, tooLate), maxDateDeltaMillis = 5 * day)

        assertEquals(2, result.entries.size)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `ambiguous candidates resolve by closest amount then closest date`() {
        val est = estimate("est1", 0L, 1000000L)
        val closerAmount = settlement("settle-close-amount", 3 * day, 1010000L) // 1% off, 3 days later
        val closerDate = settlement("settle-close-date", 1 * day, 1050000L) // 5% off, 1 day later

        val result = Reconciler.reconcile(listOf(est, closerAmount, closerDate))

        assertEquals(1, result.matches.size)
        assertEquals("settle-close-amount", result.matches.single().settlementMessageKey)
    }

    @Test
    fun `two equally-close-amount candidates break the tie by date`() {
        val est = estimate("est1", 0L, 1000000L)
        val sameAmountFarDate = settlement("settle-far", 4 * day, 1010000L)
        val sameAmountNearDate = settlement("settle-near", 1 * day, 1010000L)

        val result = Reconciler.reconcile(listOf(est, sameAmountFarDate, sameAmountNearDate))

        assertEquals(1, result.matches.size)
        assertEquals("settle-near", result.matches.single().settlementMessageKey)
    }

    @Test
    fun `a settlement is never matched to more than one estimate`() {
        val est1 = estimate("est1", 0L, 1000000L)
        val est2 = estimate("est2", 1 * day, 1000000L)
        val onlySettlement = settlement("settle1", 2 * day, 1005000L)

        val result = Reconciler.reconcile(listOf(est1, est2, onlySettlement))

        assertEquals(1, result.matches.size)
        assertEquals(2, result.entries.size) // one settled estimate, one still-unsettled estimate
    }

    @Test
    fun `unrelated same-currency entries never get merged when there is no unsettled estimate`() {
        val a = settlement("a", 0L, 50000L)
        val b = settlement("b", 1 * day, 51000L)
        val result = Reconciler.reconcile(listOf(a, b))
        assertEquals(2, result.entries.size)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `does not match across different directions`() {
        val est = estimate("est1", 0L, 1000000L)
        val wrongDirection = settlement("settle1", 1 * day, 1010000L, direction = TransactionDirection.CREDIT)
        val result = Reconciler.reconcile(listOf(est, wrongDirection))
        assertEquals(2, result.entries.size)
        assertTrue(result.matches.isEmpty())
        assertNull(result.entries.first { it.messageKey == "est1" }.effectiveMarkupPercent)
    }
}
