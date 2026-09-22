package app.dak.ui.passbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountType
import app.dak.finance.ledger.BalanceState
import app.dak.index.repo.AccountSummary
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.rememberRelativeTimeFormatter
import app.dak.ui.theme.DakTheme

/** Passbook: accounts, cards and wallets found in bank SMS, each with an honest balance. A ledger, not a budget. */
@Composable
fun PassbookScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: PassbookViewModel = hiltViewModel()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = { DakTopAppBar(title = stringResource(R.string.scr_passbook_title), onBack = { navigator.back() }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = accounts
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Receipt,
                    title = stringResource(R.string.scr_passbook_empty_title),
                    body = stringResource(R.string.scr_passbook_empty_body),
                )
                else -> {
                    val groups = remember(list) {
                        list.sortedByDescending { it.lastActivityMillis }.groupBy { it.account.type }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        for (type in listOf(AccountType.BANK_ACCOUNT, AccountType.CREDIT_CARD, AccountType.WALLET, AccountType.UNKNOWN)) {
                            val inGroup = groups[type].orEmpty()
                            if (inGroup.isEmpty()) continue
                            item(key = "header-$type") {
                                Text(
                                    stringResource(groupTitle(type)),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(inGroup, key = { it.account.id }) { summary ->
                                AccountRow(summary) { navigator.navigate(Routes.passbookAccount(summary.account.id)) }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(summary: AccountSummary, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(iconFor(summary.account.type), contentDescription = null) },
        headlineContent = { Text(accountTitle(summary.account)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BalanceLine(summary.balance)
                Text(
                    stringResource(R.string.scr_passbook_entries, summary.entryCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** "HDFC Bank credit card ••1234". */
@Composable
fun accountTitle(account: Account): String {
    val kind = stringResource(
        when (account.type) {
            AccountType.BANK_ACCOUNT -> R.string.scr_passbook_kind_account
            AccountType.CREDIT_CARD -> R.string.scr_passbook_kind_card
            AccountType.WALLET -> R.string.scr_passbook_kind_wallet
            AccountType.UNKNOWN -> R.string.scr_passbook_kind_other
        },
    )
    val last4 = account.last4?.let { " ••$it" }.orEmpty()
    return "${account.institution} $kind$last4"
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
            Text(balance.balance.format(), style = style.merge(DakTheme.typography.amount.copy(fontSize = style.fontSize)))
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
                    stringResource(R.string.scr_passbook_last_stated, known.balance.format(), formatter.formatAbsolute(known.asOfMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun iconFor(type: AccountType): ImageVector = when (type) {
    AccountType.BANK_ACCOUNT -> Icons.Outlined.AccountBalance
    AccountType.CREDIT_CARD -> Icons.Outlined.CreditCard
    AccountType.WALLET -> Icons.Outlined.AccountBalanceWallet
    AccountType.UNKNOWN -> Icons.Outlined.Receipt
}

private fun groupTitle(type: AccountType): Int = when (type) {
    AccountType.BANK_ACCOUNT -> R.string.scr_passbook_group_accounts
    AccountType.CREDIT_CARD -> R.string.scr_passbook_group_cards
    AccountType.WALLET -> R.string.scr_passbook_group_wallets
    AccountType.UNKNOWN -> R.string.scr_passbook_group_other
}
