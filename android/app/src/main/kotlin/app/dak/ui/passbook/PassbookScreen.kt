package app.dak.ui.passbook

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountType
import app.dak.finance.ledger.BalanceState
import app.dak.finance.passbook.AccountGroup
import app.dak.index.repo.AccountGroupItem
import app.dak.index.repo.AccountSummary
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.rememberRelativeTimeFormatter
import app.dak.ui.theme.DakTheme

/**
 * Passbook: accounts, cards, wallets, loans and investments found in SMS, grouped by instrument (Bank accounts, Credit
 * cards, Debit cards, Wallets, UPI, Prepaid & forex cards, Loans, Investments, Other). Each collapsible section's
 * header totals its balances / outstanding / spend / current value per currency, and says how many are unknown rather
 * than guessing. A ledger, not a budget.
 */
@Composable
fun PassbookScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: PassbookViewModel = hiltViewModel()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val aliasSuggestions by viewModel.aliasSuggestions.collectAsStateWithLifecycle()
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(viewModel) {
        viewModel.merged.collect { snackbar.showSnackbar(context.getString(R.string.fold_alias_merged_snack)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.hiddenEvents.collect { id ->
            val result = snackbar.showSnackbar(
                context.getString(R.string.pb_hidden_snack),
                actionLabel = context.getString(R.string.scr_action_undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.setHidden(id, hide = false)
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { DakTopAppBar(title = stringResource(R.string.scr_passbook_title), onBack = { navigator.back() }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = groups
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() && hidden.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Receipt,
                    title = stringResource(R.string.scr_passbook_empty_title),
                    body = stringResource(R.string.scr_passbook_empty_body),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(aliasSuggestions, key = { "alias:" + it.a.account.id + "|" + it.b.account.id }) { suggestion ->
                        AccountAliasCard(
                            suggestion = suggestion,
                            sample = viewModel::sample,
                            onSame = { viewModel.confirmSame(suggestion) },
                            onDifferent = { viewModel.confirmDifferent(suggestion) },
                        )
                    }
                    for (group in list) {
                        val isCollapsed = group.type.name in collapsed
                        item(key = "header-${group.type}") {
                            GroupHeader(
                                group = group,
                                collapsed = isCollapsed,
                                onToggle = { collapsed = if (isCollapsed) collapsed - group.type.name else collapsed + group.type.name },
                            )
                        }
                        if (!isCollapsed) {
                            items(group.items, key = { it.summary.account.id }) { item ->
                                AccountRow(
                                    item = item,
                                    onHide = { viewModel.setHidden(item.summary.account.id, hide = true) },
                                ) { navigator.navigate(Routes.passbookAccount(item.summary.account.id)) }
                                HorizontalDivider()
                            }
                        }
                    }
                    if (hidden.isNotEmpty()) {
                        item(key = "hidden-header") {
                            HiddenHeader(count = hidden.size, expanded = showHidden, onToggle = { showHidden = !showHidden })
                        }
                        if (showHidden) {
                            items(hidden, key = { "hidden:" + it.account.id }) { summary ->
                                HiddenAccountRow(
                                    summary = summary,
                                    onOpen = { navigator.navigate(Routes.passbookAccount(summary.account.id)) },
                                    onRestore = { viewModel.setHidden(summary.account.id, hide = false) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: AccountGroup<AccountGroupItem>, collapsed: Boolean, onToggle: () -> Unit) {
    val title = stringResource(groupTitleRes(group.type))
    val toggleLabel = stringResource(if (collapsed) R.string.inst_group_expand else R.string.inst_group_collapse, title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = toggleLabel, onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.inst_group_header, title, group.items.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                totalsLine(group.totals),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = toggleLabel,
        )
    }
}

/** "Hidden accounts (3)": the accounts removed from the Passbook, collapsed by default. */
@Composable
private fun HiddenHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val title = stringResource(R.string.pb_hidden_header, count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.pb_hidden_summary), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = title)
    }
}

@Composable
private fun HiddenAccountRow(summary: AccountSummary, onOpen: () -> Unit, onRestore: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { InstitutionBadge(summary.account.institution) },
        headlineContent = { Text(accountTitle(summary.account), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { TextButton(onClick = onRestore) { Text(stringResource(R.string.pb_restore)) } },
    )
}

/** One account; long-press offers "Remove from Passbook" for accounts that are not relevant (a relative's card, …). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountRow(item: AccountGroupItem, onHide: () -> Unit, onClick: () -> Unit) {
    val summary = item.summary
    val account = summary.account
    var menu by remember { mutableStateOf(false) }
    val hideLabel = stringResource(R.string.pb_hide)
    Box {
        ListItem(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = hideLabel)
                .semantics { customActions = listOf(CustomAccessibilityAction(hideLabel) { onHide(); true }) },
            leadingContent = { InstitutionBadge(account.institution) },
            headlineContent = { Text(accountTitle(account), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            overlineContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TypeChip(account.type, instrument = account.instrument)
                    val masked = maskedSuffix(account)
                    if (masked.isNotEmpty()) Text(masked, style = MaterialTheme.typography.labelSmall)
                }
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    when (account.type) {
                        AccountType.CREDIT_CARD -> {
                            val outstanding = item.outstanding
                            if (outstanding != null) {
                                Text(stringResource(R.string.inst_row_outstanding, moneyText(outstanding)), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                BalanceLine(summary.balance)
                            }
                        }
                        AccountType.DEBIT_CARD -> {
                            val linked = item.linked
                            if (linked != null) {
                                Text(
                                    stringResource(R.string.inst_row_linked_to, accountTitle(linked.account)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                BalanceLine(linked.balance)
                            } else if (summary.balance is BalanceState.NoInfo) {
                                Text(
                                    stringResource(R.string.inst_row_card_no_balance),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                BalanceLine(summary.balance)
                            }
                        }
                        AccountType.INVESTMENT -> {
                            // The current value as the fund / broker last stated it, and the units held when known.
                            if (summary.balance is BalanceState.NoInfo) {
                                Text(
                                    stringResource(R.string.inst_row_no_value),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                BalanceLine(summary.balance)
                            }
                            summary.unitsHeld?.let { units ->
                                Text(
                                    stringResource(R.string.inst_units_held, unitsText(units)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        AccountType.UPI, AccountType.UNKNOWN -> Unit
                        else -> BalanceLine(summary.balance)
                    }
                    if (item.spentThisMonth.isNotEmpty()) {
                        Text(
                            stringResource(R.string.inst_row_spent_month, moneyList(item.spentThisMonth)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.scr_passbook_entries, summary.entryCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = { Icon(iconFor(account.type), contentDescription = null) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(hideLabel) },
                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                onClick = { menu = false; onHide() },
            )
        }
    }
}

/** "HDFC Bank debit card ••1234", "PEAKMF mutual fund folio ••1234". */
@Composable
fun accountTitle(account: Account): String {
    val kind = stringResource(kindRes(account))
    // Every digit the bank shows (e.g. ••440065), so two formats of one number are told apart.
    val digits = maskedSuffix(account).let { if (it.isEmpty()) "" else " $it" }
    return "${account.institution} $kind$digits"
}

/**
 * The balance exactly as the bank last stated it, with its date; after an unsettled (usually foreign) transaction
 * it says "unknown since <date>" instead of inventing a number, and shows the last stated value for context.
 */
@Composable
fun BalanceLine(balance: BalanceState, prominent: Boolean = false) {
    val formatter = rememberRelativeTimeFormatter()
    val style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium
    when (balance) {
        BalanceState.NoInfo -> Text(stringResource(R.string.scr_passbook_no_balance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is BalanceState.Known -> Column {
            Text(moneyText(balance.balance), style = style.merge(DakTheme.typography.amount.copy(fontSize = style.fontSize)))
            Text(
                stringResource(R.string.scr_passbook_balance_as_of, formatter.formatAbsolute(balance.asOfMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is BalanceState.Unknown -> Column {
            Text(
                stringResource(R.string.scr_passbook_balance_unknown_since, formatter.formatAbsolute(balance.sinceMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = DakTheme.colors.warning.accent,
            )
            balance.lastKnown?.let { known ->
                Text(
                    stringResource(R.string.scr_passbook_last_stated, moneyText(known.balance), formatter.formatAbsolute(known.asOfMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
