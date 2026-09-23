package app.dak.ui.sendergroups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.index.repo.FoldChannel
import app.dak.index.repo.FoldTarget
import app.dak.ui.common.text.BidiText

/** "All senders / VM-HDFCBK / HDFCBN …" chips above a folded thread; shown only with two or more channels. */
@Composable
fun ChannelFilterRow(channels: List<FoldChannel>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    if (channels.size < 2) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.fold_filter_all)) })
        for (channel in channels) {
            FilterChip(
                selected = selected == channel.channel,
                onClick = { onSelect(if (selected == channel.channel) null else channel.channel) },
                label = { Text(BidiText.displaySafe(channel.addresses.firstOrNull() ?: channel.channel), maxLines = 1) },
            )
        }
    }
}

/** Picker for "Fold into…". */
@Composable
fun FoldIntoDialog(targets: List<FoldTarget>, onPick: (FoldTarget) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fold_into_title)) },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(R.string.fold_into_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(targets, key = { it.conversationId }) { target ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(target) }.padding(vertical = 10.dp)) {
                            Text(BidiText.displaySafe(target.title), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (target.title != target.address) {
                                Text(
                                    BidiText.displaySafe(target.address),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Picker for "Unfold a sender…" in a folded conversation. */
@Composable
fun UnfoldChannelDialog(channels: List<FoldChannel>, onPick: (FoldChannel) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fold_unfold_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(channels, key = { it.channel }) { channel ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(channel) }.padding(vertical = 10.dp)) {
                        Text(BidiText.displaySafe(channel.addresses.firstOrNull() ?: channel.channel), style = MaterialTheme.typography.bodyLarge)
                        val others = channel.addresses.drop(1)
                        if (others.isNotEmpty()) {
                            Text(
                                stringResource(R.string.fold_channel_also, others.joinToString { BidiText.displaySafe(it) }),
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
