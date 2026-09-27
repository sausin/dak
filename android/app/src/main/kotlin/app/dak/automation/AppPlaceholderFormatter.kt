package app.dak.automation

import android.content.Context
import app.dak.R
import app.dak.automations.PlaceholderFormatter
import app.dak.finance.money.Money
import app.dak.ui.common.text.MoneyDisplay
import java.time.Instant
import java.time.ZoneId

/**
 * `{time}`, `{amount}` and `{sim}` of automation templates (forwards, relays, webhooks, Notify) in the app language:
 * the time from the `dMMM` + hour CLDR skeleton (12/24 h as set), the amount with the locale's digits and separators
 * ([MoneyDisplay]; always with its ISO code, never a symbol, so a forwarded SMS stays in the GSM alphabet when it can),
 * and "SIM 2" translated. Built per run, so a language change applies to the next message.
 */
class AppPlaceholderFormatter(private val context: Context) : PlaceholderFormatter {

    private val locale = AppLocaleText.locale(context)
    private val timeFormat by lazy { AppLocaleText.formatter(context, "dMMM" + AppLocaleText.hourSkeleton(context)) }

    override fun time(epochMillis: Long, zone: ZoneId): String = timeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    override fun amount(amountMinor: Long, currency: String): String {
        if (currency.isBlank()) return PlaceholderFormatter.Default.amount(amountMinor, currency)
        // A home currency that matches nothing makes MoneyDisplay write the ISO code ("INR 1,234.50").
        return MoneyDisplay.format(Money(amountMinor, currency), locale, homeCurrency = NO_HOME_CURRENCY)
    }

    override fun sim(slot: Int?, subId: Int): String =
        if (slot != null) context.getString(R.string.auto_tpl_sim, slot + 1) else context.getString(R.string.auto_tpl_sim_sub, subId)

    private companion object {
        /** ISO 4217 "no currency". */
        const val NO_HOME_CURRENCY = "XXX"
    }
}
