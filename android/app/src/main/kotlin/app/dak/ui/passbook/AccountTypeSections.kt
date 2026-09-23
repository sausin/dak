package app.dak.ui.passbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.core.model.InstrumentType
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountType
import app.dak.finance.ledger.BalanceState
import app.dak.index.repo.AccountSummary

/** "Type: [Debit card] Detected from messages · Change type". */
@Composable
internal fun TypeRow(summary: AccountSummary, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.inst_type_label), style = MaterialTheme.typography.labelLarge)
        TypeChip(summary.account.type, instrument = summary.account.instrument)
        Text(
            stringResource(if (summary.typeOverridden) R.string.inst_type_set_by_you else R.string.inst_type_detected),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) { Text(stringResource(R.string.inst_type_change)) }
    }
}

/**
 * The top of an account screen, adapted to its type: a bank account, wallet or prepaid card shows its stated
 * balance; a loan its stated outstanding and the account EMIs are paid from; a debit card the bank account it debits
 * (with that account's balance) or says none is known; UPI explains that balances live on the named account; an
 * investment its current value as the fund / broker last stated it and the units held. Every balance is exactly as an
 * SMS stated it ("unknown since …" otherwise, see [BalanceLine]).
 */
@Composable
internal fun AccountHeader(summary: AccountSummary, linked: AccountSummary?, onOpenLinked: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (summary.account.type) {
        AccountType.BANK_ACCOUNT, AccountType.UNKNOWN -> Labelled(R.string.inst_balance_account) { BalanceLine(summary.balance, prominent = true) }
        AccountType.WALLET -> Labelled(R.string.inst_balance_wallet) { BalanceLine(summary.balance, prominent = true) }
        AccountType.PREPAID_CARD -> Labelled(R.string.inst_balance_prepaid) { BalanceLine(summary.balance, prominent = true) }
        AccountType.CREDIT_CARD -> if (summary.balance !is BalanceState.NoInfo) {
            Labelled(R.string.inst_balance_account) { BalanceLine(summary.balance, prominent = true) }
        }
        AccountType.LOAN -> {
            Labelled(R.string.inst_balance_loan) {
                if (summary.balance is BalanceState.NoInfo) {
                    Text(stringResource(R.string.inst_loan_no_outstanding), style = MaterialTheme.typography.bodyMedium, color = muted)
                } else {
                    BalanceLine(summary.balance, prominent = true)
                }
            }
            if (linked != null) {
                LinkedAccountCard(
                    header = stringResource(R.string.inst_loan_paid_from, accountTitle(linked.account)),
                    linked = linked,
                    onOpen = { onOpenLinked(linked.account.id) },
                )
            }
        }
        AccountType.DEBIT_CARD -> {
            if (linked != null) {
                LinkedAccountCard(
                    header = stringResource(R.string.inst_debit_linked_header),
                    linked = linked,
                    onOpen = { onOpenLinked(linked.account.id) },
                )
            } else {
                Text(stringResource(R.string.inst_debit_unlinked), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            if (summary.balance !is BalanceState.NoInfo) {
                Text(stringResource(R.string.inst_debit_stated_balance), style = MaterialTheme.typography.labelMedium, color = muted)
                BalanceLine(summary.balance)
            }
        }
        AccountType.UPI -> Labelled(R.string.inst_balance_upi) {
            Text(stringResource(R.string.inst_upi_note), style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        AccountType.INVESTMENT -> {
            Labelled(R.string.inst_balance_investment) {
                if (summary.balance is BalanceState.NoInfo) {
                    Text(stringResource(R.string.inst_investment_no_value), style = MaterialTheme.typography.bodyMedium, color = muted)
                } else {
                    BalanceLine(summary.balance, prominent = true)
                }
            }
            summary.unitsHeld?.let { units ->
                Text(stringResource(R.string.inst_units_held, unitsText(units)), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.inst_investment_note), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun Labelled(label: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        content()
    }
}

/** The bank account a debit card / loan moves money from, with its honest balance and a way to open it. */
@Composable
private fun LinkedAccountCard(header: String, linked: AccountSummary, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(header, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InstitutionBadge(linked.account.institution)
                Column(Modifier.weight(1f)) {
                    Text(accountTitle(linked.account), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BalanceLine(linked.balance)
                }
            }
            TextButton(onClick = onOpen) { Text(stringResource(R.string.inst_open_linked)) }
        }
    }
}

/** "What is this?": the user picks the account's type; "Use detected type" clears a manual choice. */
@Composable
internal fun TypeDialog(current: InstrumentType?, overridden: Boolean, onPick: (InstrumentType?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inst_type_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (option in TYPE_OPTIONS) {
                    val selected = option == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton, onClick = { onPick(option) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(stringResource(typeOptionRes(option)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (overridden) TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.inst_type_reset)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "debit card ••5678" for an entry posted through another account ([app.dak.finance.ledger.LedgerEntry.viaAccountId]). */
@Composable
internal fun viaLabel(viaAccountId: String): String {
    val parts = Account.partsOf(viaAccountId) ?: return viaAccountId
    val instrument = InstrumentType.entries.firstOrNull { it.name == parts.second } ?: InstrumentType.UNKNOWN
    val kind = stringResource(kindRes(AccountType.of(instrument)))
    val digits = parts.third.takeIf { d -> d.any { it != '0' } && d.all { it.isDigit() } }
    return if (digits != null) "$kind ••$digits" else kind
}
