package app.dak.ui.passbook

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Payment
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.core.model.InstrumentType
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountType
import app.dak.finance.money.CurrencyTable
import app.dak.finance.money.Money
import app.dak.finance.passbook.GroupTotals
import app.dak.finance.passbook.TotalKind
import app.dak.ui.common.text.MoneyDisplay
import app.dak.ui.common.text.rememberDisplayLocale

/** Section title of a Passbook group. */
@StringRes
internal fun groupTitleRes(type: AccountType): Int = when (type) {
    AccountType.BANK_ACCOUNT -> R.string.inst_group_bank_accounts
    AccountType.CREDIT_CARD -> R.string.inst_group_credit_cards
    AccountType.DEBIT_CARD -> R.string.inst_group_debit_cards
    AccountType.WALLET -> R.string.inst_group_wallets
    AccountType.UPI -> R.string.inst_group_upi
    AccountType.PREPAID_CARD -> R.string.inst_group_prepaid
    AccountType.LOAN -> R.string.inst_group_loans
    AccountType.INVESTMENT -> R.string.inst_group_investments
    AccountType.UNKNOWN -> R.string.inst_group_other
}

/** Lower-case kind used in account titles ("HDFC Bank debit card ••1234"). */
@StringRes
internal fun kindRes(type: AccountType): Int = when (type) {
    AccountType.BANK_ACCOUNT -> R.string.inst_kind_account
    AccountType.CREDIT_CARD -> R.string.inst_kind_credit_card
    AccountType.DEBIT_CARD -> R.string.inst_kind_debit_card
    AccountType.WALLET -> R.string.inst_kind_wallet
    AccountType.UPI -> R.string.inst_kind_upi
    AccountType.PREPAID_CARD -> R.string.inst_kind_prepaid
    AccountType.LOAN -> R.string.inst_kind_loan
    AccountType.INVESTMENT -> R.string.inst_kind_investment
    AccountType.UNKNOWN -> R.string.inst_kind_other
}

/** Kind of [account] in its title: a mutual fund folio or demat account within investments, else [kindRes] of its type. */
@StringRes
internal fun kindRes(account: Account): Int = when (account.instrument) {
    InstrumentType.MUTUAL_FUND -> R.string.inst_kind_mutual_fund
    InstrumentType.DEMAT -> R.string.inst_kind_demat
    else -> kindRes(account.type)
}

@StringRes
private fun chipRes(type: AccountType, instrument: InstrumentType?): Int = when (type) {
    AccountType.BANK_ACCOUNT -> R.string.inst_chip_account
    AccountType.CREDIT_CARD -> R.string.inst_chip_credit_card
    AccountType.DEBIT_CARD -> R.string.inst_chip_debit_card
    AccountType.WALLET -> R.string.inst_chip_wallet
    AccountType.UPI -> R.string.inst_chip_upi
    AccountType.PREPAID_CARD -> R.string.inst_chip_prepaid
    AccountType.LOAN -> R.string.inst_chip_loan
    AccountType.INVESTMENT -> if (instrument == InstrumentType.DEMAT) R.string.inst_chip_demat else R.string.inst_chip_mutual_fund
    AccountType.UNKNOWN -> R.string.inst_chip_other
}

/** Option label in the "What is this?" type picker. */
@StringRes
internal fun typeOptionRes(instrument: InstrumentType): Int = when (instrument) {
    InstrumentType.BANK_ACCOUNT -> R.string.inst_type_option_account
    InstrumentType.CREDIT_CARD -> R.string.inst_type_option_credit_card
    InstrumentType.DEBIT_CARD -> R.string.inst_type_option_debit_card
    InstrumentType.WALLET -> R.string.inst_type_option_wallet
    InstrumentType.UPI -> R.string.inst_type_option_upi
    InstrumentType.PREPAID_CARD -> R.string.inst_type_option_prepaid
    InstrumentType.LOAN -> R.string.inst_type_option_loan
    InstrumentType.MUTUAL_FUND -> R.string.inst_type_option_mutual_fund
    InstrumentType.DEMAT -> R.string.inst_type_option_demat
    InstrumentType.UNKNOWN -> R.string.inst_type_option_other
}

/** The picker's options, in Passbook group order. */
internal val TYPE_OPTIONS: List<InstrumentType> = listOf(
    InstrumentType.BANK_ACCOUNT,
    InstrumentType.CREDIT_CARD,
    InstrumentType.DEBIT_CARD,
    InstrumentType.WALLET,
    InstrumentType.UPI,
    InstrumentType.PREPAID_CARD,
    InstrumentType.LOAN,
    InstrumentType.MUTUAL_FUND,
    InstrumentType.DEMAT,
    InstrumentType.UNKNOWN,
)

internal fun iconFor(type: AccountType): ImageVector = when (type) {
    AccountType.BANK_ACCOUNT -> Icons.Outlined.AccountBalance
    AccountType.CREDIT_CARD -> Icons.Outlined.CreditCard
    AccountType.DEBIT_CARD -> Icons.Outlined.Payment
    AccountType.WALLET -> Icons.Outlined.AccountBalanceWallet
    AccountType.UPI -> Icons.Outlined.PhoneAndroid
    AccountType.PREPAID_CARD -> Icons.Outlined.CardGiftcard
    AccountType.LOAN -> Icons.Outlined.MonetizationOn
    AccountType.INVESTMENT -> Icons.Outlined.PieChart
    AccountType.UNKNOWN -> Icons.Outlined.Receipt
}

/** Small type label ("Debit card", "Mutual fund") next to an account; [instrument] tells a fund from a demat account. */
@Composable
internal fun TypeChip(type: AccountType, modifier: Modifier = Modifier, instrument: InstrumentType? = null) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Text(
            stringResource(chipRes(type, instrument)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Institution logo placeholder: the institution's initials in a tinted circle ("HB" for HDFC Bank). */
@Composable
internal fun InstitutionBadge(institution: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(institution),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Up to two initials of an institution name ("HDFC Bank" -> "HB", "Paytm" -> "P"); "?" when there are none. */
internal fun initialsOf(name: String): String {
    val words = name.split(' ', '-', '_').filter { w -> w.isNotBlank() && w.first().isLetterOrDigit() }
    val initials = words.take(2).map { it.first().uppercaseChar() }.joinToString("")
    return initials.ifEmpty { "?" }
}

/** "••440065": every digit the bank shows, or empty. */
internal fun maskedSuffix(account: Account): String = account.visibleDigits?.let { "••$it" }.orEmpty()

/** Amounts per currency, each via [MoneyDisplay] (foreign ones with their ISO code), joined with " · ". */
@Composable
internal fun moneyList(amounts: List<Money>): String {
    val locale = rememberDisplayLocale()
    return amounts.joinToString(" · ") { MoneyDisplay.format(it, locale, homeCurrency = it.currencyUpper) }
}

/** The header line of a group: its balance / outstanding / spend per currency, honest about what is unknown. */
@Composable
internal fun totalsLine(totals: GroupTotals): String {
    val main = when {
        totals.amounts.isNotEmpty() -> {
            val list = moneyList(totals.amounts)
            when (totals.kind) {
                TotalKind.BALANCE -> stringResource(R.string.inst_total_balance, list)
                TotalKind.OUTSTANDING -> stringResource(R.string.inst_total_outstanding, list)
                TotalKind.SPENT_THIS_MONTH -> stringResource(R.string.inst_total_spent, list)
                TotalKind.CURRENT_VALUE -> stringResource(R.string.inst_total_value, list)
            }
        }
        totals.kind == TotalKind.BALANCE -> stringResource(R.string.inst_total_no_balance)
        totals.kind == TotalKind.CURRENT_VALUE -> stringResource(R.string.inst_total_no_value)
        totals.kind == TotalKind.OUTSTANDING -> stringResource(R.string.inst_total_no_outstanding)
        else -> stringResource(R.string.inst_total_nothing_spent)
    }
    return if (totals.amounts.isNotEmpty() && totals.missingCount > 0) {
        main + " · " + stringResource(R.string.inst_total_missing, totals.missingCount)
    } else {
        main
    }
}

/** "1,127.89" style: a units / shares figure as the SMS stated it, grouped for reading; the raw string if unparsable. */
internal fun unitsText(units: String): String = runCatching {
    val value = java.math.BigDecimal(units)
    val format = java.text.NumberFormat.getNumberInstance()
    format.maximumFractionDigits = maxOf(0, value.scale())
    format.minimumFractionDigits = 0
    format.format(value)
}.getOrDefault(units)

/** "₹109.4563": a NAV / price per unit with every decimal the SMS gave, in [currency]'s symbol (or ISO code). */
internal fun unitPriceText(price: String, currency: String): String = CurrencyTable.symbolFor(currency) + unitsText(price)
