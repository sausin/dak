package app.dak.finance.reconcile

import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.money.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [Reconciler.reconcile] to a verbatim copy of its original O(estimates x settlements) implementation
 * ([ReferenceReconciler]), over randomized ledgers with a fixed seed. Any faster matcher (a date-sorted window instead
 * of a full scan per estimate) must return the identical [ReconciliationResult]: the same entries in the same order,
 * the same matches, the same markups and rates to the last digit, and the same tie-breaks (closest amount, then
 * closest date, then the earliest candidate in date order, with duplicate message keys consumed together).
 *
 * The generator deliberately produces the edge cases a windowed scan can get wrong: date ties, a settlement exactly
 * [Reconciler.DEFAULT_MAX_DATE_DELTA_MILLIS] after its estimate, amounts exactly on the tolerance, equal amount
 * distances, zero-amount estimates, settlements in another currency or direction, estimates without an indicative
 * value, and duplicate message keys.
 */
class ReconcilerEquivalenceTest {

    private val hour = TimeUnit.HOURS.toMillis(1)
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun `reconcile equals the reference on randomized ledgers with default parameters`() {
        var matches = 0
        for (seed in 0 until 400) {
            val rnd = Random(seed)
            val entries = randomLedger(rnd, size = 5 + rnd.nextInt(60))
            val expected = ReferenceReconciler.reconcile(entries)
            val actual = Reconciler.reconcile(entries)
            assertEquals(expected, actual, "seed $seed")
            matches += expected.matches.size
        }
        // The generator must actually exercise matching, or the comparison proves little.
        assertTrue(matches > 1_000, "only $matches matches generated")
    }

    @Test
    fun `reconcile equals the reference for other tolerances and date windows`() {
        val tolerances = listOf(BigDecimal.ZERO, BigDecimal("0.01"), BigDecimal("0.06"), BigDecimal("0.5"))
        val windows = listOf(0L, hour, 5 * day, 30 * day)
        for (seed in 1_000 until 1_150) {
            val rnd = Random(seed)
            val entries = randomLedger(rnd, size = 5 + rnd.nextInt(50))
            for (tolerance in tolerances) {
                for (window in windows) {
                    assertEquals(
                        ReferenceReconciler.reconcile(entries, tolerance, window),
                        Reconciler.reconcile(entries, tolerance, window),
                        "seed $seed tolerance $tolerance window $window",
                    )
                }
            }
        }
    }

    @Test
    fun `input order does not matter beyond the stable date sort`() {
        for (seed in 2_000 until 2_100) {
            val rnd = Random(seed)
            val entries = randomLedger(rnd, size = 10 + rnd.nextInt(40))
            val shuffled = entries.shuffled(Random(seed + 7))
            assertEquals(ReferenceReconciler.reconcile(shuffled), Reconciler.reconcile(shuffled), "seed $seed")
        }
    }

    @Test
    fun `empty and settlement-only ledgers`() {
        assertEquals(ReconciliationResult(emptyList(), emptyList()), Reconciler.reconcile(emptyList()))
        val settledOnly = randomLedger(Random(3), 30).filter { it.settled }
        assertEquals(ReferenceReconciler.reconcile(settledOnly), Reconciler.reconcile(settledOnly))
    }

    /**
     * Timing of a card-heavy account: [estimates] foreign spends and [settlements] home-currency entries over a year.
     * Printed only (no threshold: CI machines vary); the result must still equal the reference.
     */
    @Test
    fun `timing on a large account`() {
        val estimates = 1_000
        val settlements = 10_000
        val rnd = Random(99)
        val span = 365 * day
        val entries = ArrayList<LedgerEntry>(estimates + settlements)
        repeat(estimates) { i ->
            val date = rnd.nextLong(span)
            val home = 1_000L + rnd.nextLong(500_000L)
            entries += estimate("e$i", date, home, TransactionDirection.DEBIT)
        }
        repeat(settlements) { i ->
            entries += settlement("s$i", rnd.nextLong(span), 1_000L + rnd.nextLong(600_000L), "INR", TransactionDirection.DEBIT)
        }
        lateinit var expected: ReconciliationResult
        lateinit var actual: ReconciliationResult
        val referenceNanos = measureNanoTime { expected = ReferenceReconciler.reconcile(entries) }
        val nanos = measureNanoTime { actual = Reconciler.reconcile(entries) }
        assertEquals(expected, actual)
        println(
            "RECONCILER-BENCH estimates=$estimates settlements=$settlements matches=${actual.matches.size} " +
                "reference=${referenceNanos / 1_000_000} ms current=${nanos / 1_000_000} ms",
        )
    }

    // ---- generator ----

    private fun randomLedger(rnd: Random, size: Int): List<LedgerEntry> {
        val out = ArrayList<LedgerEntry>(size * 2)
        var key = 0
        fun nextKey(): String = if (out.isNotEmpty() && rnd.nextInt(100) < 4) out[rnd.nextInt(out.size)].messageKey else "k${key++}"
        val span = 20 * day
        fun date(): Long = when (rnd.nextInt(4)) {
            0 -> rnd.nextLong(span / hour) * hour // on the hour: ties are common
            else -> rnd.nextLong(span)
        }
        fun direction() = if (rnd.nextInt(5) == 0) TransactionDirection.CREDIT else TransactionDirection.DEBIT
        repeat(size) {
            when (rnd.nextInt(10)) {
                in 0..3 -> {
                    // A foreign estimate, with settlements placed around it.
                    val d = date()
                    val home = when (rnd.nextInt(10)) {
                        0 -> 0L
                        else -> 100L + rnd.nextLong(300_000L)
                    }
                    val dir = direction()
                    val homeCurrency = if (rnd.nextInt(8) == 0) "EUR" else "INR"
                    out += estimate(nextKey(), d, home, dir, homeCurrency, withIndicative = rnd.nextInt(12) != 0, zeroOriginal = home == 0L && rnd.nextBoolean())
                    repeat(rnd.nextInt(4)) {
                        val delta = when (rnd.nextInt(6)) {
                            0 -> 0L
                            1 -> Reconciler.DEFAULT_MAX_DATE_DELTA_MILLIS // exactly on the window
                            2 -> Reconciler.DEFAULT_MAX_DATE_DELTA_MILLIS + 1
                            3 -> -rnd.nextLong(1, day) // before the estimate: never a candidate
                            else -> rnd.nextLong(3 * day)
                        }
                        val tolerance = home * 6 / 100
                        val amount = when (rnd.nextInt(6)) {
                            0 -> home + tolerance // exactly on the tolerance (when it divides evenly)
                            1 -> home - tolerance
                            2 -> home + tolerance + 1
                            3 -> home + rnd.nextLong(-3, 4) // equal distances either side
                            else -> (home + rnd.nextLong(-(tolerance + 2), tolerance + 3)).coerceAtLeast(0L)
                        }
                        val currency = if (rnd.nextInt(10) == 0) "AED" else homeCurrency
                        val settledDir = if (rnd.nextInt(10) == 0) direction() else dir
                        out += settlement(nextKey(), d + delta, amount, currency, settledDir)
                    }
                }
                in 4..8 -> out += settlement(nextKey(), date(), rnd.nextLong(400_000L), if (rnd.nextInt(10) == 0) "EUR" else "INR", direction())
                else -> {
                    // An unsettled entry with no indicative value (no rate): never matched, still kept.
                    out += estimate(nextKey(), date(), 0L, direction(), withIndicative = false)
                }
            }
        }
        return out.shuffled(rnd)
    }

    private fun estimate(
        key: String,
        date: Long,
        homeMinor: Long,
        direction: TransactionDirection,
        homeCurrency: String = "INR",
        withIndicative: Boolean = true,
        zeroOriginal: Boolean = false,
    ) = LedgerEntry(
        messageKey = key,
        dateMillis = date,
        direction = direction,
        original = Money(if (zeroOriginal) 0L else (homeMinor / 83).coerceAtLeast(1L), "USD"),
        indicativeHome = if (withIndicative) Money(homeMinor, homeCurrency) else null,
        rate = if (withIndicative) BigDecimal("83.1234") else null,
        rateDateMillis = if (withIndicative) date else null,
        settled = false,
        merchant = "M$key",
    )

    private fun settlement(key: String, date: Long, amountMinor: Long, currency: String, direction: TransactionDirection) = LedgerEntry(
        messageKey = key,
        dateMillis = date,
        direction = direction,
        original = Money(amountMinor, currency),
        indicativeHome = Money(amountMinor, currency),
        settled = true,
        balanceAfter = if (amountMinor % 3 == 0L) Money(amountMinor * 10, currency) else null,
    )
}

/** A verbatim copy of the original `Reconciler.reconcile` (O(U x S)), kept as the equivalence oracle. */
internal object ReferenceReconciler {
    fun reconcile(
        entries: List<LedgerEntry>,
        toleranceFraction: BigDecimal = Reconciler.DEFAULT_TOLERANCE,
        maxDateDeltaMillis: Long = Reconciler.DEFAULT_MAX_DATE_DELTA_MILLIS,
    ): ReconciliationResult {
        val sorted = entries.sortedBy { it.dateMillis }
        val settlementCandidates = sorted.filter { it.settled }.toMutableList()
        val consumed = mutableSetOf<String>()
        val updates = mutableMapOf<String, LedgerEntry>()
        val matches = mutableListOf<ReconciliationMatch>()

        val unsettled = sorted.filter { !it.settled && it.indicativeHome != null }.sortedBy { it.dateMillis }

        for (estimate in unsettled) {
            val estimateHome = estimate.indicativeHome ?: continue
            val estimateAmount = BigDecimal.valueOf(estimateHome.amountMinor)
            val toleranceAbs = estimateAmount.abs().multiply(toleranceFraction)

            val candidates = settlementCandidates.filter { candidate ->
                candidate.messageKey !in consumed &&
                    candidate.direction == estimate.direction &&
                    candidate.original.currencyUpper == estimateHome.currencyUpper &&
                    candidate.dateMillis >= estimate.dateMillis &&
                    (candidate.dateMillis - estimate.dateMillis) <= maxDateDeltaMillis &&
                    BigDecimal.valueOf(abs(candidate.original.amountMinor - estimateHome.amountMinor)) <= toleranceAbs
            }

            val best = candidates.minWithOrNull(
                compareBy<LedgerEntry> { abs(it.original.amountMinor - estimateHome.amountMinor) }
                    .thenBy { it.dateMillis - estimate.dateMillis },
            ) ?: continue

            consumed += best.messageKey
            val markup = if (estimateAmount.signum() != 0) {
                BigDecimal.valueOf(best.original.amountMinor - estimateHome.amountMinor)
                    .divide(estimateAmount, MathContext(8))
                    .multiply(BigDecimal(100))
                    .setScale(2, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }

            matches += ReconciliationMatch(estimate.messageKey, best.messageKey, markup)
            updates[estimate.messageKey] = estimate.copy(
                indicativeHome = best.original,
                settled = true,
                effectiveMarkupPercent = markup,
                rate = if (estimate.original.amountMinor == 0L) {
                    estimate.rate
                } else {
                    best.original.toBigDecimal().divide(estimate.original.toBigDecimal(), MathContext(12))
                },
                rateDateMillis = best.dateMillis,
            )
        }

        val resultEntries = sorted.mapNotNull { entry ->
            when {
                entry.messageKey in updates -> updates[entry.messageKey]
                entry.messageKey in consumed -> null
                else -> entry
            }
        }

        return ReconciliationResult(resultEntries, matches)
    }
}
