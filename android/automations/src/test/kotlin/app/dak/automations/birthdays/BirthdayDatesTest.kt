package app.dak.automations.birthdays

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BirthdayDatesTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val nine = LocalTime.of(9, 0)

    @Test
    fun `parses the formats contacts providers use`() {
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("1990-05-17"))
        assertEquals(ContactDate(5, 17, null), BirthdayDates.parse("--05-17"))
        assertEquals(ContactDate(5, 17, null), BirthdayDates.parse("--0517"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("19900517"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("1990-05-17T00:00:00Z"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("1990-05-17 00:00:00.000"))
        assertEquals(ContactDate(5, 7, 1990), BirthdayDates.parse("1990-5-7"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("1990/05/17"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("17/05/1990"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("05/17/1990"))
        assertEquals(ContactDate(4, 3, 1990), BirthdayDates.parse("03/04/1990"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("17.05.1990"))
        assertEquals(ContactDate(5, 17, 1995), BirthdayDates.parse("17/05/95"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("May 17, 1990"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("17 May 1990"))
        assertEquals(ContactDate(9, 2, null), BirthdayDates.parse("September 2"))
        assertEquals(ContactDate(5, 17, 1990), BirthdayDates.parse("642902400000"))
    }

    @Test
    fun `drops placeholder years and rejects garbage`() {
        assertEquals(ContactDate(5, 17, null), BirthdayDates.parse("1604-05-17"))
        assertEquals(ContactDate(5, 17, null), BirthdayDates.parse("0000-05-17"))
        assertNull(BirthdayDates.parse(""))
        assertNull(BirthdayDates.parse(null))
        assertNull(BirthdayDates.parse("soon"))
        assertNull(BirthdayDates.parse("1990-13-01"))
        assertNull(BirthdayDates.parse("1990-02-30"))
        assertNull(BirthdayDates.parse("1990-02-29"))
        assertEquals(ContactDate(2, 29, 1992), BirthdayDates.parse("1992-02-29"))
        assertEquals(ContactDate(2, 29, null), BirthdayDates.parse("--02-29"))
    }

    @Test
    fun `next occurrence is today if the send time is still ahead, else next year`() {
        val date = ContactDate(9, 23, 1990)
        val morning = ZonedDateTime.of(2026, 9, 23, 7, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, kolkata), BirthdayDates.nextOccurrence(date, morning, nine, kolkata))
        val evening = ZonedDateTime.of(2026, 9, 23, 20, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2027, 9, 23, 9, 0, 0, 0, kolkata), BirthdayDates.nextOccurrence(date, evening, nine, kolkata))
    }

    @Test
    fun `already wished years are skipped`() {
        val date = ContactDate(12, 1)
        val now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        val next = BirthdayDates.nextOccurrence(date, now, nine, kolkata, skipYears = setOf(2026))
        assertEquals(2027, next!!.year)
    }

    @Test
    fun `feb 29 falls on feb 28 in non leap years`() {
        val leapling = ContactDate(2, 29, 1992)
        val now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 2, 28), BirthdayDates.nextOccurrence(leapling, now, nine, kolkata)!!.toLocalDate())
        val in2028 = ZonedDateTime.of(2028, 1, 1, 0, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2028, 2, 29), BirthdayDates.nextOccurrence(leapling, in2028, nine, kolkata)!!.toLocalDate())
        assertEquals(LocalDate.of(2027, 2, 28), leapling.inYear(2027))
    }

    @Test
    fun `days until wraps into next year`() {
        val today = LocalDate.of(2026, 9, 23)
        assertEquals(0, BirthdayDates.daysUntil(ContactDate(9, 23), today))
        assertEquals(1, BirthdayDates.daysUntil(ContactDate(9, 24), today))
        assertEquals(364, BirthdayDates.daysUntil(ContactDate(9, 22), today))
    }

    @Test
    fun `age is known only with a birth year`() {
        assertEquals(36, ContactDate(5, 17, 1990).ageIn(2026))
        assertNull(ContactDate(5, 17).ageIn(2026))
    }
}
