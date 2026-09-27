package app.dak.ui.search

import android.icu.text.DateFormat
import android.icu.text.DateIntervalFormat
import android.icu.text.NumberFormat
import android.icu.util.DateInterval
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.dak.R
import app.dak.search.Filter
import app.dak.search.Folder
import app.dak.ui.common.categoryLabel
import app.dak.ui.common.text.rememberDisplayLocale
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Text of an active filter chip in the app language. `Chip.label` from `:search` is an English fallback for tests
 * and logs only; it is never shown.
 */
@Composable
internal fun filterChipText(filter: Filter): String = when (filter) {
    is Filter.From -> stringResource(R.string.search_chip_from, filter.value)
    is Filter.CategoryIs -> categoryLabel(filter.category)
    is Filter.Sim -> filter.value.trim().let { v ->
        val slot = v.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }?.toIntOrNull()
        if (slot != null) stringResource(R.string.sim_n, slot) else stringResource(R.string.search_chip_sim, v)
    }
    Filter.HasAttachment -> stringResource(R.string.search_chip_has_attachment)
    Filter.HasLink -> stringResource(R.string.search_chip_has_link)
    Filter.HasOtp -> stringResource(R.string.search_chip_has_otp)
    is Filter.AmountRange -> amountChipText(filter)
    is Filter.DateRange -> dateChipText(filter)
    is Filter.InFolder -> stringResource(
        when (filter.folder) {
            Folder.INBOX -> R.string.search_chip_in_inbox
            Folder.ARCHIVE -> R.string.scr_filter_archive
            Folder.BIN -> R.string.scr_filter_bin
        },
    )
    Filter.IsStarred -> stringResource(R.string.scr_filter_starred)
    Filter.IsUnread -> stringResource(R.string.scr_filter_unread)
    Filter.IsRead -> stringResource(R.string.search_chip_read)
    is Filter.Not -> stringResource(R.string.search_chip_not, filterChipText(filter.filter))
}

/** Same bounds as the query text (`amount:>500` is stored as `minMinor = 50001`), shown as numbers in the app locale. */
@Composable
private fun amountChipText(filter: Filter.AmountRange): String {
    val locale = rememberDisplayLocale()
    val format = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
    }
    fun amount(hundredths: Long): String = format.format(BigDecimal.valueOf(hundredths, 2))
    val min = filter.minMinor
    val max = filter.maxMinor
    return when {
        min != null && max != null && min == max -> stringResource(R.string.search_chip_amount_exact, amount(min))
        min != null && max != null -> stringResource(R.string.search_chip_amount_between, amount(min), amount(max))
        min != null -> stringResource(R.string.search_chip_amount_over, amount(min - 1))
        max != null -> stringResource(R.string.search_chip_amount_under, amount(max + 1))
        else -> stringResource(R.string.search_chip_amount_any)
    }
}

/** A localized date or date range ("12–18 Sep", "12 Sep 2025 – 3 Jan 2026") instead of the stored epoch millis. */
@Composable
private fun dateChipText(filter: Filter.DateRange): String {
    val locale = rememberDisplayLocale()
    val start = filter.startMillis
    val end = filter.endMillis
    return when {
        start != null && end != null && end > start ->
            remember(locale, start, end) { SearchDateText.range(locale, start, end) }
        start != null -> stringResource(R.string.search_chip_date_since, remember(locale, start) { SearchDateText.day(locale, start) })
        // `before:x` keeps `[.., startOfDay(x))`: the chip names the day the range stops before.
        end != null -> stringResource(R.string.search_chip_date_before, remember(locale, end) { SearchDateText.day(locale, end) })
        else -> stringResource(R.string.search_chip_date_any)
    }
}

/** CLDR date (interval) formatting in the app locale; the year is left out when every date falls in this year. */
private object SearchDateText {
    private fun skeleton(vararg millis: Long): String {
        val zone = ZoneId.systemDefault()
        val thisYear = Instant.now().atZone(zone).year
        return if (millis.all { Instant.ofEpochMilli(it).atZone(zone).year == thisYear }) "MMMd" else "yMMMd"
    }

    fun day(locale: Locale, millis: Long): String =
        DateFormat.getInstanceForSkeleton(skeleton(millis), locale).format(Date(millis))

    /** [endExclusive] is the half-open bound; the last millisecond before it is the last day shown. */
    fun range(locale: Locale, start: Long, endExclusive: Long): String {
        val last = endExclusive - 1
        return DateIntervalFormat.getInstance(skeleton(start, last), locale).format(DateInterval(start, last))
    }
}
