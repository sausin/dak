package app.dak.ui.common

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.dak.R
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Compact relative timestamps for thread rows and bubbles:
 * "Now", "5 min", "14:32" (today, honours the 12/24 h setting), "Yesterday", "Mon" (this week),
 * "12 Sep" (this year), "12 Sep 2025".
 */
class RelativeTimeFormatter(
    private val locale: Locale,
    private val zone: ZoneId,
    is24Hour: Boolean,
    private val labels: Labels,
    /** Best pattern for a skeleton in [locale] (CLDR); a parameter only so JVM tests can supply one. */
    private val bestPattern: (Locale, String) -> String = DateFormat::getBestDateTimePattern,
) {
    /** Localised words the formatter needs. */
    data class Labels(val now: String, val minutesFormat: String, val yesterday: String)

    private fun formatter(skeleton: String) = DateTimeFormatter.ofPattern(bestPattern(locale, skeleton), locale)

    private val hourSkeleton = if (is24Hour) "Hm" else "hm"
    private val time = formatter(hourSkeleton)
    private val weekday = formatter("EEE")
    private val dayMonth = formatter("dMMM")
    private val dayMonthYear = formatter("dMMMy")

    /**
     * Date and time from one skeleton, so the locale decides their order and the joiner ("12 Sep 2025, 14:32",
     * "Sep 12, 2025, 2:32 PM", "12 سبتمبر 2025، 2:32 م") instead of a hard-coded "date, time".
     */
    private val dateTime = formatter("dMMMy$hourSkeleton")

    /** Formats [epochMillis] relative to [nowMillis]. Future times (clock skew) are treated as "now". */
    fun format(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diff = nowMillis - epochMillis
        if (diff < 60_000L) return labels.now
        if (diff < 60 * 60_000L) return String.format(locale, labels.minutesFormat, diff / 60_000L)
        val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), today)
        return when {
            days <= 0L -> time.format(then)
            days == 1L -> labels.yesterday
            days < 7L -> weekday.format(then)
            then.year == today.year -> dayMonth.format(then)
            else -> dayMonthYear.format(then)
        }
    }

    /** Absolute time for accessibility / detail sheets, e.g. "12 Sep 2025, 14:32". */
    fun formatAbsolute(epochMillis: Long): String = dateTime.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    companion object {
        /** Formatter for the device's current locale, zone and 12/24 h preference. */
        fun create(context: Context): RelativeTimeFormatter = RelativeTimeFormatter(
            locale = context.resources.configuration.locales[0] ?: Locale.getDefault(),
            zone = ZoneId.systemDefault(),
            is24Hour = DateFormat.is24HourFormat(context),
            labels = Labels(
                now = context.getString(R.string.time_now),
                minutesFormat = context.getString(R.string.time_minutes_format),
                yesterday = context.getString(R.string.time_yesterday),
            ),
        )
    }
}

/** Remembers a device-configured [RelativeTimeFormatter]. */
@Composable
fun rememberRelativeTimeFormatter(): RelativeTimeFormatter {
    val context = LocalContext.current
    return remember(context) { RelativeTimeFormatter.create(context) }
}

/** Relative time text for [epochMillis] that refreshes itself every minute while shown. */
@Composable
fun relativeTime(epochMillis: Long): String {
    val formatter = rememberRelativeTimeFormatter()
    val text by produceState(initialValue = formatter.format(epochMillis), epochMillis, formatter) {
        while (true) {
            value = formatter.format(epochMillis)
            delay(60_000L)
        }
    }
    return text
}
