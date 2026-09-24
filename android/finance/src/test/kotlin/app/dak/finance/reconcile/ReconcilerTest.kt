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
        // The earlier estimate claims the settlement; the later one stays an estimate.
        assertEquals("est1", result.matches.single().estimateMessageKey)
        assertEquals(mapOf("est1" to true, "est2" to false), result.entries.associate { it.messageKey to it.settled })
    }

    @Test
    fun `a settled estimate records the real rate and settlement date`() {
        val est = estimate("est1", 0L, 1000000L) // AED 120.50 estimated at Rs 10,000
        val actual = settlement("settle1", 2 * day, 1030000L)
        val merged = Reconciler.reconcile(listOf(est, actual)).entries.single()
        // Rs 10,300 / AED 120.50
        assertEquals(0, BigDecimal("85.4771784232").compareTo(merged.rate), "rate ${merged.rate}")
        assertEquals(2 * day, merged.rateDateMillis)
        assertEquals(BigDecimal("3.00"), merged.effectiveMarkupPercent)
        assertEquals(Money(12050, "AED"), merged.original)
        assertEquals(0L, merged.dateMillis) // the entry keeps the date of the spend
    }

    @Test
    fun `tolerance and date window bounds are inclusive`() {
        val est = estimate("est1", 0L, 1000000L)
        assertEquals(1, Reconciler.reconcile(listOf(est, settlement("s", 5 * day, 1060000L))).matches.size)
        assertEquals(0, Reconciler.reconcile(listOf(est, settlement("s", 5 * day + 1, 1000000L))).matches.size)
        assertEquals(0, Reconciler.reconcile(listOf(est, settlement("s", 1 * day, 1060001L))).matches.size)
        // A settlement below the estimate (a favourable rate) is a negative markup.
        val cheaper = Reconciler.reconcile(listOf(est, settlement("s", 1 * day, 980000L))).matches.single()
        assertEquals(BigDecimal("-2.00"), cheaper.markupPercent)
    }

    @Test
    fun `a settlement dated before the estimate is never matched`() {
        val est = estimate("est1", 2 * day, 1000000L)
        val earlier = settlement("s", 1 * day, 1000000L)
        assertTrue(Reconciler.reconcile(listOf(est, earlier)).matches.isEmpty())
    }

    @Test
    fun `a settlement in a different home currency is never matched`() {
        val est = estimate("est1", 0L, 1000000L)
        val usd = settlement("s", 1 * day, 1000000L, currency = "USD")
        assertTrue(Reconciler.reconcile(listOf(est, usd)).matches.isEmpty())
    }

    @Test
    fun `an estimate without an indicative value is left alone`() {
        val noRate = estimate("est1", 0L, 1000000L).copy(indicativeHome = null)
        val result = Reconciler.reconcile(listOf(noRate, settlement("s", 1 * day, 1000000L)))
        assertTrue(result.matches.isEmpty())
        assertEquals(2, result.entries.size)
    }

    /**
     * Bug: a zero-amount foreign estimate (a card verification "USD 0.00 authorised") matched a zero-amount home
     * settlement and the new rate divided by the zero original amount, throwing ArithmeticException out of the
     * reconciler (and so out of the passbook build).
     */
    @Test
    fun `zero amount estimate and settlement do not crash`() {
        val zero = app.dak.finance.ledger.LedgerEntry(
            messageKey = "est0",
            dateMillis = 0L,
            direction = TransactionDirection.DEBIT,
            original = Money(0, "USD"),
            indicativeHome = Money(0, "INR"),
            settled = false,
        )
        val result = Reconciler.reconcile(listOf(zero, settlement("s0", 1 * day, 0L)))
        assertEquals(1, result.matches.size)
        assertEquals(BigDecimal.ZERO, result.matches.single().markupPercent)
        val merged = result.entries.single()
        assertTrue(merged.settled)
        assertEquals(Money(0, "INR"), merged.indicativeHome)
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
