package app.dak.finance.reconcile

import app.dak.finance.ledger.LedgerEntry
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** One successful reconciliation: which estimate was matched to which settlement, and the markup found. */
data class ReconciliationMatch(
    val estimateMessageKey: String,
    val settlementMessageKey: String,
    val markupPercent: BigDecimal,
)

data class ReconciliationResult(val entries: List<LedgerEntry>, val matches: List<ReconciliationMatch>)

/**
 * Matches a foreign-currency estimate (an unsettled [LedgerEntry] with an indicative home-currency
 * value) to the bank's later settlement SMS in home currency, replacing the indicative value with
 * the real one and marking the entry settled. Matching is deterministic: same account (callers
 * pass one account's entries), same direction, later date within [maxDateDeltaMillis], and amount
 * within [toleranceFraction] of the indicative estimate (default ±6%, to cover typical forex
 * markup). When more than one settlement candidate qualifies, the closest by amount wins, ties
 * broken by the closest date.
 */
object Reconciler {

    val DEFAULT_TOLERANCE: BigDecimal = BigDecimal("0.06")
    val DEFAULT_MAX_DATE_DELTA_MILLIS: Long = TimeUnit.DAYS.toMillis(5)

    fun reconcile(
        entries: List<LedgerEntry>,
        toleranceFraction: BigDecimal = DEFAULT_TOLERANCE,
        maxDateDeltaMillis: Long = DEFAULT_MAX_DATE_DELTA_MILLIS,
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
                // A zero-amount estimate (a card verification) has no rate to learn; keep the one it had.
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
                entry.messageKey in consumed -> null // merged into the estimate entry it settled
                else -> entry
            }
        }

        return ReconciliationResult(resultEntries, matches)
    }
}
