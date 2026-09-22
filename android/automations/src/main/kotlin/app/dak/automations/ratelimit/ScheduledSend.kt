package app.dak.automations.ratelimit

import app.dak.automations.rule.Recurrence
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

public enum class ScheduledSendState { PENDING, SENT, FAILED, CANCELLED }

/** One user-scheduled send (the "Scheduled and recurring send" feature), one-shot or [recurrence]-driven. */
public data class ScheduledSend(
    val id: String,
    val addresses: List<String>,
    val body: String,
    val subId: Int?,
    val atMillis: Long,
    val recurrence: Recurrence? = null,
    val state: ScheduledSendState = ScheduledSendState.PENDING,
)

/**
 * The next time [recurrence] fires strictly after [after] (epoch millis), in [recurrence]'s own zone.
 * Uses `java.time`'s zone rules, so a local time that a DST transition skips or repeats resolves the
 * way [ZonedDateTime.of] always does (the later offset in a gap, the earlier one in an overlap) —
 * deterministic, if not always "the" intuitive instant. [Recurrence.Monthly.dayOfMonth] beyond the
 * month's length clamps to that month's last day (e.g. 31 -> 28/29 Feb, 30 Apr).
 */
public fun nextOccurrence(recurrence: Recurrence, after: Long): Long {
    val zone = ZoneId.of(recurrence.zoneId)
    val afterZdt = Instant.ofEpochMilli(after).atZone(zone)
    val time = LocalTime.of(recurrence.hour, recurrence.minute)

    val candidate: ZonedDateTime = when (recurrence) {
        is Recurrence.Daily -> {
            var d = ZonedDateTime.of(afterZdt.toLocalDate(), time, zone)
            if (!d.toInstant().isAfter(afterZdt.toInstant())) d = d.plusDays(1)
            d
        }
        is Recurrence.Weekly -> {
            val targetDow = java.time.DayOfWeek.of(recurrence.dayOfWeek)
            var date = afterZdt.toLocalDate()
            var d = ZonedDateTime.of(date.with(TemporalAdjusters.nextOrSame(targetDow)), time, zone)
            if (!d.toInstant().isAfter(afterZdt.toInstant())) {
                date = date.plusDays(1)
                d = ZonedDateTime.of(date.with(TemporalAdjusters.nextOrSame(targetDow)), time, zone)
            }
            d
        }
        is Recurrence.Monthly -> {
            fun candidateFor(ym: YearMonth): ZonedDateTime {
                val day = minOf(recurrence.dayOfMonth, ym.lengthOfMonth())
                return ZonedDateTime.of(ym.atDay(day), time, zone)
            }
            var ym = YearMonth.from(afterZdt)
            var d = candidateFor(ym)
            if (!d.toInstant().isAfter(afterZdt.toInstant())) {
                ym = ym.plusMonths(1)
                d = candidateFor(ym)
            }
            d
        }
        is Recurrence.Unknown -> afterZdt // no schedule to compute; caller should have filtered these out
    }
    return candidate.toInstant().toEpochMilli()
}
