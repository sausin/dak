package app.dak.ui.passbook

import androidx.compose.runtime.Composable
import app.dak.finance.money.Money
import app.dak.ui.common.text.MoneyDisplay
import app.dak.ui.common.text.rememberDisplayLocale

/**
 * [money] formatted for the passbook via [MoneyDisplay]: locale-aware grouping, and an ISO code instead of a
 * symbol for any currency other than [homeCurrency] (the account's own currency; defaults to the amount's own, so
 * home-currency amounts keep their familiar symbol).
 */
@Composable
internal fun moneyText(money: Money, homeCurrency: String? = money.currencyUpper, indicative: Boolean = false): String =
    MoneyDisplay.format(money, rememberDisplayLocale(), homeCurrency, indicative)
