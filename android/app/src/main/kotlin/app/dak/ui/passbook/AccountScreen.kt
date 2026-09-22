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
import app.dak.R
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.AccountType
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.money.Money
import app.dak.finance.passbook.MonthlyTotal
import app.dak.navigation.DakNavigator
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.rememberRelativeTimeFormatter
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
    val scope = rememberCoroutineScope()

    val account = summary?.account
    Scaffold(
        modifier = modifier,
        topBar = {
            DakTopAppBar(title = account?.let { accountTitle(it) } ?: stringResource(R.string.scr_passbook_title), onBack = { navigator.back() })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = entries
            if (summary == null || list == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.scr_account_balance), style = MaterialTheme.typography.labelLarge)
                        summary?.let { BalanceLine(it.balance, prominent = true) }
                        if (account != null && account.type == AccountType.CREDIT_CARD) {
                            CardCycle(
                                statementDay = account.statementDay,
                                outstanding = outstanding,
                                onEdit = { statementDialog = true },
                            )
                        }
                    }
                }
                val latestMonth = monthly.lastOrNull()
                if (latestMonth != null) item { MonthSummary(latestMonth) }
                item { HorizontalDivider() }
                if (list.isEmpty()) {
                    item { EmptyState(icon = Icons.Outlined.Receipt, title = stringResource(R.string.scr_account_no_entries), body = null) }
                }
                items(list, key = { it.messageKey }) { entry ->
                    EntryRow(
                        entry = entry,
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

    if (statementDialog) {
        StatementDayDialog(
            current = account?.statementDay,
            onSave = { statementDialog = false; viewModel.setStatementDay(it) },
            onDismiss = { statementDialog = false },
        )
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
                Text(outstanding?.format() ?: "—", style = DakTheme.typography.amount)
                Text(stringResource(R.string.scr_account_statement_day, statementDay), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.scr_account_set_statement_day)) }
        }
    }
}

@Composable
private fun MonthSummary(month: MonthlyTotal) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.scr_account_month, month.yearMonth), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                Text(stringResource(R.string.scr_account_spent), style = MaterialTheme.typography.labelSmall)
                Text(month.debitsHome.format(), style = DakTheme.typography.amount, color = DakTheme.colors.financeDebit)
            }
            Column {
                Text(stringResource(R.string.scr_account_received), style = MaterialTheme.typography.labelSmall)
                Text(month.creditsHome.format(), style = DakTheme.typography.amount, color = DakTheme.colors.financeCredit)
            }
        }
        val foreign = month.debitsByCurrency.keys.filter { it != month.debitsHome.currencyUpper }
        if (foreign.isNotEmpty()) {
            Text(
                stringResource(R.string.scr_account_month_foreign, foreign.joinToString { month.debitsByCurrency.getValue(it).format() }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: LedgerEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    source: () -> kotlinx.coroutines.flow.Flow<app.dak.index.MessageItem?>,
    onOpenThread: () -> Unit,
) {
    val formatter = rememberRelativeTimeFormatter()
    val debit = entry.direction == TransactionDirection.DEBIT
    val amountColor = if (debit) DakTheme.colors.financeDebit else DakTheme.colors.financeCredit
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.merchant ?: stringResource(if (debit) R.string.scr_account_debit else R.string.scr_account_credit),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatter.formatAbsolute(entry.dateMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((if (debit) "−" else "+") + entry.original.format(), style = DakTheme.typography.amount, color = amountColor)
                ForeignLine(entry)
                entry.balanceAfter?.let {
                    Text(stringResource(R.string.scr_account_balance_after, it.format()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (markup != null) stringResource(R.string.scr_account_settled_markup, home.format(), markup) else stringResource(R.string.scr_account_settled, home.format()),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        else -> {
            val rate = entry.rate?.setScale(4, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()
            val date = entry.rateDateMillis?.let { formatter.formatAbsolute(it).substringBefore(',') }
            Text(
                if (rate != null) stringResource(R.string.scr_account_indicative_rate, home.formatIndicative(), rate, date ?: "—") else home.formatIndicative(),
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
