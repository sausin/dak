package app.dak.automations

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats the values [TemplateRenderer] puts into `{time}`, `{amount}` and `{sim}`. `:app` supplies one that follows
 * the app language (CLDR date skeleton, locale digits and separators, a translated SIM name); [Default] keeps the
 * locale-independent English output ("15 Jul, 08:05", "INR 500.00", "SIM 2", "sub 3") for JVM tests and callers
 * without a locale.
 */
public interface PlaceholderFormatter {
    /** `{time}`: [epochMillis] shown in [zone]. */
    public fun time(epochMillis: Long, zone: ZoneId): String

    /** `{amount}`: [amountMinor] minor units of [currency] (ISO 4217). */
    public fun amount(amountMinor: Long, currency: String): String

    /** `{sim}`: the receiving SIM, by 0-based [slot] when known, else by [subId]. */
    public fun sim(slot: Int?, subId: Int): String

    public companion object {
        /** Locale-independent English formatting (the behaviour before app-language formatting existed). */
        public val Default: PlaceholderFormatter = object : PlaceholderFormatter {
            private val timeFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US)

            override fun time(epochMillis: Long, zone: ZoneId): String =
                timeFormat.withZone(zone).format(Instant.ofEpochMilli(epochMillis))

            override fun amount(amountMinor: Long, currency: String): String =
                "%s %.2f".format(Locale.US, currency, amountMinor / 100.0)

            override fun sim(slot: Int?, subId: Int): String = slot?.let { "SIM ${it + 1}" } ?: "sub $subId"
        }
    }
}
