package app.dak.ui.common

import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelativeTimeFormatterTest {
    private val labels = RelativeTimeFormatter.Labels(now = "Now", minutesFormat = "%d min", yesterday = "Yesterday")
    private val at = LocalDateTime.of(2025, 9, 12, 14, 32).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Stand-in for CLDR's best pattern: fixed patterns per skeleton, recording what was asked. */
    private class Patterns(private val table: Map<String, String>) : (Locale, String) -> String {
        val asked = mutableListOf<String>()
        override fun invoke(locale: Locale, skeleton: String): String {
            asked += skeleton
            return table.getValue(skeleton)
        }
    }

    private fun formatter(patterns: Patterns, is24Hour: Boolean = true) =
        RelativeTimeFormatter(Locale.US, ZoneOffset.UTC, is24Hour, labels, patterns)

    private val uk = mapOf(
        "Hm" to "HH:mm", "hm" to "h:mm a", "EEE" to "EEE", "dMMM" to "d MMM", "dMMMy" to "d MMM y",
        "dMMMyHm" to "d MMM y, HH:mm", "dMMMyhm" to "d MMM y, h:mm a",
    )

    @Test
    fun `absolute time comes from one date-time skeleton`() {
        val p = Patterns(uk)
        assertEquals("12 Sep 2025, 14:32", formatter(p).formatAbsolute(at))
        assertTrue("dMMMyHm" in p.asked)
        val p12 = Patterns(uk)
        assertTrue("dMMMyhm" in p12.asked.also { formatter(p12, is24Hour = false) })
    }

    @Test
    fun `the locale decides order and joiner`() {
        // A locale whose pattern puts the time first with its own joiner is honoured as is.
        val timeFirst = uk + ("dMMMyHm" to "HH:mm 'on' d MMM y")
        assertEquals("14:32 on 12 Sep 2025", formatter(Patterns(timeFirst)).formatAbsolute(at))
    }

    @Test
    fun `relative labels are unchanged`() {
        val f = formatter(Patterns(uk))
        assertEquals("Now", f.format(at, nowMillis = at + 30_000))
        assertEquals("5 min", f.format(at, nowMillis = at + 5 * 60_000))
        assertEquals("14:32", f.format(at, nowMillis = at + 60 * 60_000))
        assertEquals("Yesterday", f.format(at, nowMillis = at + 24 * 60 * 60_000))
    }
}
