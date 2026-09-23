package app.dak.index.sync

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The "Tonight" backfill window (01:00 to 05:00 local time by default), as pure arithmetic so it can be tested
 * without WorkManager.
 */
class NightWindow(
    val start: LocalTime = LocalTime.of(1, 0),
    val end: LocalTime = LocalTime.of(5, 0),
) {
    init {
        require(start < end) { "window must not cross midnight" }
    }

    /** True if [now] falls inside `[start, end)`. */
    fun contains(now: ZonedDateTime): Boolean {
        val t = now.toLocalTime()
        return !t.isBefore(start) && t.isBefore(end)
    }

    /**
     * Delay from [now] until the window next opens: zero while inside the window, otherwise until today's start
     * (if still ahead) or tomorrow's. DST gaps are resolved by [ZonedDateTime.with].
     */
    fun delayUntilOpen(now: ZonedDateTime): Duration {
        if (contains(now)) return Duration.ZERO
        return delayUntilNextStart(now)
    }

    /**
     * Delay until the next window start strictly after [now], even when [now] is inside the window (used to
     * re-enqueue for the following night once tonight's window has been used up or missed).
     */
    fun delayUntilNextStart(now: ZonedDateTime): Duration = Duration.between(now, nextStartAfter(now))

    private fun nextStartAfter(now: ZonedDateTime): ZonedDateTime {
        val todayStart = now.with(start).withSecond(0).withNano(0)
        return if (now.isBefore(todayStart)) todayStart else todayStart.plusDays(1).with(start)
    }
}
