package app.dak.ui.passbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.core.model.MessageKey
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AliasReason
import app.dak.index.MessageItem
import app.dak.index.repo.AccountAliasSuggestion
import app.dak.index.repo.AccountSummary
import app.dak.ui.common.rememberRelativeTimeFormatter
import app.dak.ui.common.text.BidiText
import kotlinx.coroutines.flow.Flow

/** "A/c ••440065": every digit the bank shows. */
@Composable
internal fun accountNumberLabel(account: Account): String =
    stringResource(R.string.fold_alias_account_number, account.visibleDigits ?: "—")

/**
 * "Is A/c ••40065 the same as ••440065 (HDFC Bank)?" with [Same account] / [Different] and the newest SMS of each
 * one tap away. Nothing is merged until the user answers.
 */
@Composable
internal fun AccountAliasCard(
    suggestion: AccountAliasSuggestion,
    sample: (MessageKey?) -> Flow<MessageItem?>,
    onSame: () -> Unit,
    onDifferent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMessages by rememberSaveable(suggestion.a.account.id, suggestion.b.account.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    R.string.fold_alias_question,
                    accountNumberLabel(suggestion.a.account),
                    "••" + (suggestion.b.account.visibleDigits ?: "—"),
                    BidiText.displaySafe(suggestion.a.account.institution),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(if (suggestion.reason == AliasReason.SAME_DIGITS) R.string.fold_alias_explain_same_digits else R.string.fold_alias_explain),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { showMessages = !showMessages }) {
                Text(stringResource(if (showMessages) R.string.fold_alias_hide_messages else R.string.fold_alias_show_messages))
            }
            if (showMessages) {
                SampleMessage(accountNumberLabel(suggestion.a.account), remember(suggestion.sampleA) { sample(suggestion.sampleA) })
                SampleMessage(accountNumberLabel(suggestion.b.account), remember(suggestion.sampleB) { sample(suggestion.sampleB) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onDifferent) { Text(stringResource(R.string.fold_alias_different)) }
                Button(onClick = onSame) { Text(stringResource(R.string.fold_alias_same)) }
            }
        }
    }
}

@Composable
private fun SampleMessage(label: String, flow: Flow<MessageItem?>) {
    val message by flow.collectAsStateWithLifecycle(initialValue = null)
    val formatter = rememberRelativeTimeFormatter()
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            val m = message
            if (m == null) {
                Text(stringResource(R.string.fold_alias_sample_unavailable), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(BidiText.isolate(m.body), style = MaterialTheme.typography.bodySmall)
                Text(
                    BidiText.displaySafe(m.address) + " · " + formatter.formatAbsolute(m.dateMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Picker for "Merge with…" on the account screen. */
@Composable
internal fun MergeWithDialog(candidates: List<AccountSummary>, onPick: (AccountSummary) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fold_alias_merge_title)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.fold_alias_merge_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.account.id }) { summary ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(summary) }.padding(vertical = 10.dp)) {
                            Text(accountTitle(summary.account), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.scr_passbook_entries, summary.entryCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
