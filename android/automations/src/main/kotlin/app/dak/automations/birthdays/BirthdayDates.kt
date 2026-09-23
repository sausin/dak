package app.dak.automations.birthdays

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** A contact event date as stored by a contacts provider: month/day always, year when the source had one. */
public data class ContactDate(val month: Int, val day: Int, val year: Int? = null) {
    init {
        require(month in 1..12) { "month out of range: $month" }
        require(day in 1..Month.of(month).maxLength()) { "day out of range: $day" }
    }

    /** This date's occurrence in [year]; Feb 29 falls on Feb 28 in non-leap years. */
    public fun inYear(year: Int): LocalDate {
        val day = if (month == 2 && day == 29 && !Year.isLeap(year.toLong())) 28 else day
        return LocalDate.of(year, month, day)
    }

    /** Age reached on the occurrence in [year], or null when the birth year is unknown (or in the future). */
    public fun ageIn(year: Int): Int? = this.year?.let { year - it }?.takeIf { it > 0 }
}

/**
 * Robust parsing and next-occurrence math for contact birthdays/anniversaries
 * (`ContactsContract.CommonDataKinds.Event.START_DATE`), which in the wild comes as:
 * `1990-05-17`, `--05-17` (no year, vCard style), `--0517`, `19900517`, `1990-05-17T00:00:00Z`,
 * `1990-05-17 00:00:00.000`, `17/05/1990` or `05/17/1990` (disambiguated when one side is > 12, else day-first),
 * `17.05.1990`, `May 17, 1990`, `17 May 1990`, `May 17`, and epoch-millis strings from some OEM sync adapters.
 * Placeholder years used for "no year" (0000, 1604 from Apple, 1900 is kept as real) are dropped.
 */
public object BirthdayDates {

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val ISO_FULL = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:[T ].*)?$""")
    private val NO_YEAR = Regex("""^--(\d{2})-?(\d{2})$""")
    private val COMPACT = Regex("""^(\d{4})(\d{2})(\d{2})$""")
    private val NUMERIC = Regex("""^(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?$""")
    private val YEAR_FIRST_SLASH = Regex("""^(\d{4})[/.](\d{1,2})[/.](\d{1,2})$""")
    private val EPOCH = Regex("""^-?\d{9,13}$""")
    private val WORDS = Regex("""[A-Za-z]+|\d+""")

    /** Parses [raw], or returns null when it is not a recognisable (valid) date. Never throws. */
    public fun parse(raw: String?): ContactDate? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching { parseOrNull(s) }.getOrNull()
    }

    private fun parseOrNull(s: String): ContactDate? {
        NO_YEAR.matchEntire(s)?.let { m -> return date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null) }
        ISO_FULL.matchEntire(s)?.let { m ->
            return date(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1].toInt())
        }
        YEAR_FIRST_SLASH.matchEntire(s)?.let { m ->
            return date(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1].toInt())
        }
        COMPACT.matchEntire(s)?.let { m ->
            return date(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1].toInt())
        }
        if (EPOCH.matches(s)) {
            val d = Instant.ofEpochMilli(s.toLong()).atZone(ZoneOffset.UTC).toLocalDate()
            return date(d.monthValue, d.dayOfMonth, d.year)
        }
        NUMERIC.matchEntire(s)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::expandYear)
            // Day-first unless that is impossible (Indian/European contacts are the common case).
            return if (a > 12 || b <= 12) date(b, a, year) else date(a, b, year)
        }
        return parseWords(s)
    }

    private fun parseWords(s: String): ContactDate? {
        val tokens = WORDS.findAll(s).map { it.value }.toList()
        val monthIndex = tokens.indexOfFirst { it.length >= 3 && MONTHS.containsKey(it.take(3).lowercase()) }
        if (monthIndex < 0) return null
        val month = MONTHS.getValue(tokens[monthIndex].take(3).lowercase())
        val numbers = tokens.filterIndexed { i, t -> i != monthIndex && t.all(Char::isDigit) }.map { it.toInt() }
        val day = numbers.firstOrNull { it in 1..31 } ?: return null
        val year = numbers.firstOrNull { it > 31 }
        return date(month, day, year)
    }

    private fun expandYear(text: String): Int {
        val y = text.toInt()
        return if (text.length == 2) (if (y > 30) 1900 + y else 2000 + y) else y
    }

    private fun date(month: Int, day: Int, year: Int?): ContactDate? {
        if (month !in 1..12 || day !in 1..Month.of(month).maxLength()) return null
        val cleanYear = year?.takeIf { it in 1800..2200 && it != PLACEHOLDER_YEAR_APPLE }
        // Feb 29 with a real non-leap year is not a date.
        if (month == 2 && day == 29 && cleanYear != null && !Year.isLeap(cleanYear.toLong())) return null
        return ContactDate(month, day, cleanYear)
    }

    /** iOS / macOS store year-less birthdays with this year. */
    private const val PLACEHOLDER_YEAR_APPLE = 1604

    /**
     * The next time strictly after [afterMillis] at which a wish for [date] should go out: the occurrence date
     * (Feb 29 → Feb 28 in non-leap years) at [sendAt] in [zone], skipping any year in [skipYears] (already wished).
     * DST gaps resolve the way [ZonedDateTime.of] does. Looks at most 10 years ahead, then gives up (null).
     */
    public fun nextOccurrence(
        date: ContactDate,
        afterMillis: Long,
        sendAt: LocalTime,
        zone: ZoneId,
        skipYears: Set<Int> = emptySet(),
    ): ZonedDateTime? {
        val startYear = Instant.ofEpochMilli(afterMillis).atZone(zone).year
        for (year in startYear..startYear + MAX_YEARS_AHEAD) {
            if (year in skipYears) continue
            val at = ZonedDateTime.of(date.inYear(year), sendAt, zone)
            if (at.toInstant().toEpochMilli() > afterMillis) return at
        }
        return null
    }

    /** Days from [today] to the next occurrence of [date] (0 = today). */
    public fun daysUntil(date: ContactDate, today: LocalDate): Int {
        var next = date.inYear(today.year)
        if (next.isBefore(today)) next = date.inYear(today.year + 1)
        return ChronoUnit.DAYS.between(today, next).toInt()
    }

    private const val MAX_YEARS_AHEAD = 10
}
