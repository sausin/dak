package app.dak.finance.ledger

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * A credit card's monthly billing cycle, defined by the day of month the statement is generated.
 * Configurable per card (Settings -> Finance -> Advanced -> reconciliation/statement day); all
 * dates are treated in UTC for determinism, since only the day-of-month matters here.
 */
data class BillingCycle(val statementDay: Int) {
    init {
        require(statementDay in 1..31) { "statementDay must be 1..31, was $statementDay" }
    }

    private fun statementDateFor(yearMonth: YearMonth): LocalDate {
        val day = statementDay.coerceAtMost(yearMonth.lengthOfMonth())
        return yearMonth.atDay(day)
    }

    /** The statement date that closes the cycle containing [asOfMillis] (on or after that instant's date). */
    fun statementDateFor(asOfMillis: Long): LocalDate {
        val date = Instant.ofEpochMilli(asOfMillis).atZone(ZoneOffset.UTC).toLocalDate()
        var candidate = statementDateFor(YearMonth.from(date))
        if (candidate.isBefore(date)) candidate = statementDateFor(YearMonth.from(date).plusMonths(1))
        return candidate
    }

    /** The `[start, end)` millisecond range of the billing cycle containing [asOfMillis]. */
    fun cycleRange(asOfMillis: Long): LongRange {
        val end = statementDateFor(asOfMillis)
        val start = statementDateFor(YearMonth.from(end).minusMonths(1)).plusDays(1)
        val startMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endMillis = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return startMillis until endMillis
    }
}
