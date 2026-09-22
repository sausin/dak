package app.dak.automations.ratelimit

import app.dak.automations.rule.Recurrence
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledSendTest {

    private fun millis(iso: String, zone: String): Long =
        ZonedDateTime.parse(iso).withZoneSameInstant(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun atZone(millis: Long, zone: String): ZonedDateTime =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.of(zone))

    @Test
    fun `daily recurrence in Asia_Kolkata rolls to the next day once past the time`() {
        val recurrence = Recurrence.Daily(hour = 9, minute = 30, zoneId = "Asia/Kolkata")
        val before = millis("2026-09-22T08:00:00+05:30", "Asia/Kolkata")
        val after = millis("2026-09-22T09:30:01+05:30", "Asia/Kolkata")

        val nextBefore = atZone(nextOccurrence(recurrence, before), "Asia/Kolkata")
        assertEquals("2026-09-22T09:30", nextBefore.toLocalDateTime().toString())

        val nextAfter = atZone(nextOccurrence(recurrence, after), "Asia/Kolkata")
        assertEquals("2026-09-23T09:30", nextAfter.toLocalDateTime().toString())
    }

    @Test
    fun `weekly recurrence lands on the correct day of week and rolls a full week`() {
        // 2026-09-22 is a Tuesday. Ask for Friday (5).
        val recurrence = Recurrence.Weekly(dayOfWeek = 5, hour = 10, minute = 0, zoneId = "Asia/Kolkata")
        val tuesday = millis("2026-09-22T00:00:00+05:30", "Asia/Kolkata")
        val next = atZone(nextOccurrence(recurrence, tuesday), "Asia/Kolkata")
        assertEquals("2026-09-25T10:00", next.toLocalDateTime().toString())

        val friday10am = millis("2026-09-25T10:00:00+05:30", "Asia/Kolkata")
        val nextAfterFiring = atZone(nextOccurrence(recurrence, friday10am), "Asia/Kolkata")
        assertEquals("2026-10-02T10:00", nextAfterFiring.toLocalDateTime().toString())
    }

    @Test
    fun `monthly recurrence on the 31st clamps to the last day of shorter months`() {
        val recurrence = Recurrence.Monthly(dayOfMonth = 31, hour = 12, minute = 0, zoneId = "Asia/Kolkata")

        val afterJan31 = millis("2026-01-31T12:00:01+05:30", "Asia/Kolkata")
        val febOccurrence = atZone(nextOccurrence(recurrence, afterJan31), "Asia/Kolkata")
        assertEquals("2026-02-28T12:00", febOccurrence.toLocalDateTime().toString()) // 2026 is not a leap year

        val afterFeb = millis("2026-02-28T12:00:01+05:30", "Asia/Kolkata")
        val marchOccurrence = atZone(nextOccurrence(recurrence, afterFeb), "Asia/Kolkata")
        assertEquals("2026-03-31T12:00", marchOccurrence.toLocalDateTime().toString())

        val afterMarch = millis("2026-03-31T12:00:01+05:30", "Asia/Kolkata")
        val aprilOccurrence = atZone(nextOccurrence(recurrence, afterMarch), "Asia/Kolkata")
        assertEquals("2026-04-30T12:00", aprilOccurrence.toLocalDateTime().toString()) // April has 30 days
    }

    @Test
    fun `monthly recurrence clamps correctly over a leap-year February`() {
        val recurrence = Recurrence.Monthly(dayOfMonth = 31, hour = 0, minute = 0, zoneId = "UTC")
        val afterJan31 = millis("2028-01-31T00:00:01Z", "UTC") // 2028 is a leap year
        val febOccurrence = atZone(nextOccurrence(recurrence, afterJan31), "UTC")
        assertEquals("2028-02-29T00:00", febOccurrence.toLocalDateTime().toString())
    }

    @Test
    fun `daily recurrence in Europe_London skips forward correctly across the spring-forward DST gap`() {
        // UK clocks spring forward 01:00 -> 02:00 on 2026-03-29. A 01:30 daily local time does not exist that day.
        val recurrence = Recurrence.Daily(hour = 1, minute = 30, zoneId = "Europe/London")
        val dayBefore = millis("2026-03-28T12:00:00Z", "UTC")
        val next = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextOccurrence(recurrence, dayBefore)), ZoneId.of("Europe/London"))
        // java.time resolves a gap by shifting forward by the gap length (01:30 local -> 02:30 local, UTC+1).
        assertEquals("2026-03-29T02:30", next.toLocalDateTime().toString())
        assertEquals(java.time.ZoneOffset.ofHours(1), next.offset)
    }

    @Test
    fun `daily recurrence in Europe_London handles the autumn fall-back overlap deterministically`() {
        // UK clocks fall back 02:00 -> 01:00 on 2026-10-25; 01:30 local happens twice.
        val recurrence = Recurrence.Daily(hour = 1, minute = 30, zoneId = "Europe/London")
        val dayBefore = millis("2026-10-24T12:00:00Z", "UTC")
        val next = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextOccurrence(recurrence, dayBefore)), ZoneId.of("Europe/London"))
        assertEquals("2026-10-25T01:30", next.toLocalDateTime().toString())
        // ZonedDateTime.of resolves an overlap to the earlier offset (still-summer-time, +1).
        assertEquals(java.time.ZoneOffset.ofHours(1), next.offset)
    }

    @Test
    fun `nextOccurrence is strictly after the given instant, never equal`() {
        val recurrence = Recurrence.Daily(hour = 9, minute = 30, zoneId = "Asia/Kolkata")
        val exact = millis("2026-09-22T09:30:00+05:30", "Asia/Kolkata")
        val next = nextOccurrence(recurrence, exact)
        assert(next > exact)
    }
}
