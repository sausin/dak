package app.dak.ui.passbook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.StringRes
import app.dak.R
import app.dak.core.model.InvestmentAction
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountType
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.money.Money
import app.dak.finance.passbook.MonthlyTotal
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.rememberRelativeTimeFormatter
import app.dak.ui.common.text.MoneyDisplay
import app.dak.ui.common.text.rememberDisplayLocale
import app.dak.ui.theme.DakTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Receipt
import kotlinx.coroutines.launch
import java.math.RoundingMode

/**
 * One account or card: honest balance, card outstanding for the current cycle (with the statement day), monthly
 * totals, and the ledger. Foreign entries show the amount as written plus "≈ home value at rate (date)"; settled
 * ones show the bank's actual amount and the effective markup. Tapping an entry shows its raw SMS, one more tap
 * opens it in the thread.
 */
@Composable
fun AccountScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: AccountViewModel = hiltViewModel()
    val summary by viewModel.account.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val outstanding by viewModel.outstanding.collectAsStateWithLifecycle()
    val monthly by viewModel.monthly.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var statementDialog by remember { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }
    var typeDialog by remember { mutableStateOf(false) }
    val linked by viewModel.linked.collectAsStateWithLifecycle()
    val mergedIds by viewModel.mergedIds.collectAsStateWithLifecycle()
    val mergeCandidates by viewModel.mergeCandidates.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val account = summary?.account
    Scaffold(
        modifier = modifier,
        topBar = {
            DakTopAppBar(
                title = account?.let { accountTitle(it) } ?: stringResource(R.string.scr_passbook_title),
                onBack = { navigator.back() },
                actions = {
                    if (account != null) {
                        TextButton(onClick = { mergeDialog = true }) { Text(stringResource(R.string.fold_alias_merge_with)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = entries
            if (summary == null || list == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        summary?.let { s ->
                            TypeRow(s, onChange = { typeDialog = true })
                            AccountHeader(
                                summary = s,
                                linked = linked,
                                onOpenLinked = { id -> navigator.navigate(Routes.passbookAccount(id)) },
                            )
                        }
                        if (account != null && account.type == AccountType.CREDIT_CARD) {
                            CardCycle(
                                statementDay = account.statementDay,
                                outstanding = outstanding,
                                onEdit = { statementDialog = true },
                            )
                        }
                    }
                }
                if (mergedIds.isNotEmpty()) {
                    item { MergedNumbers(mergedIds, onUnmerge = viewModel::unmerge) }
                }
                val investment = account?.type == AccountType.INVESTMENT
                val latestMonth = monthly.lastOrNull()
                if (latestMonth != null) item { MonthSummary(latestMonth, investment) }
                item { HorizontalDivider() }
                if (list.isEmpty()) {
                    item { EmptyState(icon = Icons.Outlined.Receipt, title = stringResource(R.string.scr_account_no_entries), body = null) }
                }
                items(list, key = { it.messageKey }) { entry ->
                    EntryRow(
                        entry = entry,
                        investment = investment,
                        expanded = expanded == entry.messageKey,
                        onToggle = { expanded = if (expanded == entry.messageKey) null else entry.messageKey },
                        source = { viewModel.source(entry) },
                        onOpenThread = {
                            scope.launch { viewModel.routeTo(entry)?.let { navigator.navigate(it) } }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (mergeDialog) {
        MergeWithDialog(
            candidates = mergeCandidates,
            onPick = { mergeDialog = false; viewModel.mergeWith(it) },
            onDismiss = { mergeDialog = false },
        )
    }
    if (typeDialog && summary != null) {
        TypeDialog(
            current = summary?.account?.instrument,
            overridden = summary?.typeOverridden == true,
            onPick = { typeDialog = false; viewModel.setType(it) },
            onDismiss = { typeDialog = false },
        )
    }
    if (statementDialog) {
        StatementDayDialog(
            current = account?.statementDay,
            onSave = { statementDialog = false; viewModel.setStatementDay(it) },
            onDismiss = { statementDialog = false },
        )
    }
}

/** "Also includes A/c ••40065 [Unmerge]": other formats of this account's number the user merged in. */
@Composable
private fun MergedNumbers(aliasIds: List<String>, onUnmerge: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.fold_alias_merged_header), style = MaterialTheme.typography.labelLarge)
        for (id in aliasIds) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val digits = Account.partsOf(id)?.third ?: id
                Text(stringResource(R.string.fold_alias_account_number, digits), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onUnmerge(id) }) { Text(stringResource(R.string.fold_alias_unmerge)) }
            }
        }
    }
}

@Composable
private fun CardCycle(statementDay: Int?, outstanding: Money?, onEdit: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (statementDay == null) {
                Text(stringResource(R.string.scr_account_statement_day_unknown), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.scr_account_outstanding), style = MaterialTheme.typography.labelLarge)
                Text(outstanding?.let { moneyText(it) } ?: "—", style = DakTheme.typography.amount)
                Text(stringResource(R.string.scr_account_statement_day, statementDay), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.scr_account_set_statement_day)) }
        }
    }
}

/**
 * The latest month's totals. A bank account shows spending and income (transfers into the user's own investments are
 * not spending: they are shown on their own line); an investment account shows what was invested, redeemed / sold and
 * paid out as dividends.
 */
@Composable
private fun MonthSummary(month: MonthlyTotal, investment: Boolean) {
    if (investment) InvestmentMonthSummary(month) else AccountMonthSummary(month)
}

@Composable
private fun InvestmentMonthSummary(month: MonthlyTotal) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.scr_account_month, month.yearMonth), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                Text(stringResource(R.string.inst_month_invested), style = MaterialTheme.typography.labelSmall)
                Text(moneyText(month.transfersInHome), style = DakTheme.typography.amount, color = DakTheme.colors.financeCredit)
            }
            Column {
                Text(stringResource(R.string.inst_month_redeemed), style = MaterialTheme.typography.labelSmall)
                Text(moneyText(month.transfersOutHome), style = DakTheme.typography.amount, color = DakTheme.colors.financeDebit)
            }
            if (month.creditsHome.amountMinor != 0L) {
                Column {
                    Text(stringResource(R.string.inst_month_dividends), style = MaterialTheme.typography.labelSmall)
                    Text(moneyText(month.creditsHome), style = DakTheme.typography.amount, color = DakTheme.colors.financeCredit)
                }
            }
        }
    }
}

@Composable
private fun AccountMonthSummary(month: MonthlyTotal) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.scr_account_month, month.yearMonth), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                Text(stringResource(R.string.scr_account_spent), style = MaterialTheme.typography.labelSmall)
                Text(moneyText(month.debitsHome), style = DakTheme.typography.amount, color = DakTheme.colors.financeDebit)
            }
            Column {
                Text(stringResource(R.string.scr_account_received), style = MaterialTheme.typography.labelSmall)
                Text(moneyText(month.creditsHome), style = DakTheme.typography.amount, color = DakTheme.colors.financeCredit)
            }
        }
        if (month.transfersOutHome.amountMinor != 0L || month.transfersInHome.amountMinor != 0L) {
            Text(
                stringResource(R.string.inst_month_transfers, moneyText(month.transfersOutHome), moneyText(month.transfersInHome)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val foreign = month.debitsByCurrency.keys.filter { it != month.debitsHome.currencyUpper }
        if (foreign.isNotEmpty()) {
            val locale = rememberDisplayLocale()
            Text(
                // Foreign-currency amounts always show their ISO code (never a bare symbol like
                // "$"), since it is ambiguous while travelling (USD/CAD/AUD/SGD/HKD/...).
                stringResource(
                    R.string.scr_account_month_foreign,
                    foreign.joinToString {
                        MoneyDisplay.format(month.debitsByCurrency.getValue(it), locale, homeCurrency = month.debitsHome.currencyUpper)
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: LedgerEntry,
    investment: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    source: () -> kotlinx.coroutines.flow.Flow<app.dak.index.MessageItem?>,
    onOpenThread: () -> Unit,
) {
    val formatter = rememberRelativeTimeFormatter()
    val debit = entry.direction == TransactionDirection.DEBIT
    val amountColor = if (debit) DakTheme.colors.financeDebit else DakTheme.colors.financeCredit
    val action = entry.investmentAction
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.merchant
                        ?: action?.let { stringResource(investmentActionRes(it)) }
                        ?: stringResource(if (debit) R.string.scr_account_debit else R.string.scr_account_credit),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatter.formatAbsolute(entry.dateMillis), style = MaterialTheme.typography.labelSmall, color = muted)
                if (action != null && entry.merchant != null) {
                    Text(stringResource(investmentActionRes(action)), style = MaterialTheme.typography.labelSmall, color = muted)
                }
                unitsLine(entry)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
                // A bank's SIP / mutual-fund / trading-account debit: money moved to the user's own investments.
                if (entry.transfer && !investment) {
                    Text(stringResource(R.string.inst_entry_own_transfer), style = MaterialTheme.typography.labelSmall, color = muted)
                }
                entry.viaAccountId?.let { via ->
                    Text(
                        stringResource(R.string.inst_row_via, viaLabel(via)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (entry.isValuation) {
                    // A statement of value: no money moved, so no signed amount.
                    entry.balanceAfter?.let { Text(moneyText(it), style = DakTheme.typography.amount) }
                    Text(stringResource(R.string.inst_entry_value), style = MaterialTheme.typography.labelSmall, color = muted)
                } else {
                    Text((if (debit) "−" else "+") + moneyText(entry.original, homeCurrency = entry.homeValue.currencyUpper), style = DakTheme.typography.amount, color = amountColor)
                    ForeignLine(entry)
                    entry.balanceAfter?.let {
                        val label = if (investment) R.string.inst_entry_value_after else R.string.scr_account_balance_after
                        Text(stringResource(label, moneyText(it)), style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            val flow = remember(entry.messageKey) { source() }
            val message by flow.collectAsStateWithLifecycle(initialValue = null)
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.scr_account_raw_sms), style = MaterialTheme.typography.labelMedium)
                    Text(message?.body ?: stringResource(R.string.scr_account_raw_unavailable), style = MaterialTheme.typography.bodyMedium)
                    message?.let { Text(it.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    entry.reference?.let { Text(stringResource(R.string.scr_account_reference, it), style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = onOpenThread, enabled = message != null) { Text(stringResource(R.string.scr_account_open_thread)) }
                }
            }
        }
    }
}

/** Label of an investment entry: "Purchase / SIP", "Redemption", "Switch", "Dividend / IDCW", "Bought", "Sold", "Valuation". */
@StringRes
private fun investmentActionRes(action: InvestmentAction): Int = when (action) {
    InvestmentAction.PURCHASE -> R.string.inst_action_purchase
    InvestmentAction.REDEMPTION -> R.string.inst_action_redemption
    InvestmentAction.SWITCH -> R.string.inst_action_switch
    InvestmentAction.DIVIDEND -> R.string.inst_action_dividend
    InvestmentAction.BUY -> R.string.inst_action_buy
    InvestmentAction.SELL -> R.string.inst_action_sell
    InvestmentAction.VALUATION -> R.string.inst_action_valuation
}

/** "45.678 units @ ₹109.4563", "10 units", "NAV ₹21.55": what an investment entry moved, as the SMS stated it. */
@Composable
private fun unitsLine(entry: LedgerEntry): String? {
    val units = entry.units
    val price = entry.unitPrice
    val currency = entry.original.currencyUpper
    return when {
        units != null && price != null -> stringResource(R.string.inst_entry_units_at, unitsText(units), unitPriceText(price, currency))
        units != null -> stringResource(R.string.inst_entry_units, unitsText(units))
        price != null -> stringResource(R.string.inst_entry_price, unitPriceText(price, currency))
        else -> null
    }
}

/** "≈ ₹3,512.40 at 83.43 (12 Sep)" for foreign estimates; "Settled ₹3,601.10 · markup 2.5%" once reconciled. */
@Composable
private fun ForeignLine(entry: LedgerEntry) {
    if (!entry.isForeign) return
    val formatter = rememberRelativeTimeFormatter()
    val home = entry.indicativeHome
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        home == null -> Text(stringResource(R.string.scr_account_no_rate), style = MaterialTheme.typography.labelSmall, color = muted)
        entry.settled -> {
            val markup = entry.effectiveMarkupPercent?.setScale(2, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()
            Text(
                if (markup != null) stringResource(R.string.scr_account_settled_markup, moneyText(home), markup) else stringResource(R.string.scr_account_settled, moneyText(home)),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        else -> {
            val rate = entry.rate?.setScale(4, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()
            val date = entry.rateDateMillis?.let { formatter.formatAbsolute(it).substringBefore(',') }
            Text(
                if (rate != null) {
                    stringResource(R.string.scr_account_indicative_rate, moneyText(home, indicative = true), rate, date ?: "—")
                } else {
                    moneyText(home, indicative = true)
                },
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
    }
}

@Composable
private fun StatementDayDialog(current: Int?, onSave: (Int?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current?.toString().orEmpty()) }
    val day = text.toIntOrNull()?.takeIf { it in 1..31 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scr_account_set_statement_day)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(2) },
                singleLine = true,
                label = { Text(stringResource(R.string.scr_account_statement_day_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = text.isNotEmpty() && day == null,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(day) }, enabled = text.isEmpty() || day != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
