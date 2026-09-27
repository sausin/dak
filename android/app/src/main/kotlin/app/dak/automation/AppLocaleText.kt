package app.dak.automation

import android.content.Context
import android.icu.text.ListFormatter
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatting in the app language for automation screens and notifications: the locale is
 * `resources.configuration.locales[0]` (never `Locale.getDefault()`, which stays the phone's on Android 8–12), dates
 * and times come from CLDR skeletons (the locale picks the order and the joiner), and lists use the locale's
 * list pattern ("A, B and C", "A, B y C", "A, B et C", "A, B और C").
 */
object AppLocaleText {

    /** The app language's locale. */
    fun locale(context: Context): Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()

    /** [items] joined the way [locale] writes a list. */
    fun list(locale: Locale, items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> ListFormatter.getInstance(locale).format(items)
    }

    /** [items] joined in the app language. */
    fun list(context: Context, items: List<String>): String = list(locale(context), items)

    /** `Hm` or `hm`, following the device's 12/24-hour setting. */
    fun hourSkeleton(context: Context): String = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"

    /** A formatter for the CLDR [skeleton] (e.g. `dMMMy`) in the app language. */
    fun formatter(context: Context, skeleton: String): DateTimeFormatter {
        val locale = locale(context)
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    /** [millis] in the device's zone with [skeleton]. */
    fun format(context: Context, skeleton: String, millis: Long): String =
        formatter(context, skeleton).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** "23 Sep 2026, 17:30" / "Sep 23, 2026, 5:30 PM": date and time from one skeleton, 12/24 h as set. */
    fun dateTime(context: Context, millis: Long): String = format(context, "dMMMy" + hourSkeleton(context), millis)

    /** "23 Sep 2026" / "Sep 23, 2026". */
    fun date(context: Context, millis: Long): String = format(context, "dMMMy", millis)

    /** "17:30" / "5:30 PM". */
    fun time(context: Context, millis: Long): String = format(context, hourSkeleton(context), millis)
}
