package app.dak.ui.forwarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.compose.collectAsLazyPagingItems
import app.dak.R
import app.dak.index.ConversationSummary
import app.dak.ui.common.Avatar
import app.dak.ui.common.categoryLabel

/**
 * Full-screen picker of source channels: every conversation, including folded sender groups ("HDFC Bank" is one
 * row however many headers it uses), searchable by name or address. [onDone] gets the checked conversation ids and
 * the summaries of those that were loaded (a previously chosen source that never scrolled into view has no summary;
 * the caller keeps its existing entry).
 */
@Composable
internal fun SourcePickerDialog(
    viewModel: ForwardingViewModel,
    selectedIds: Set<String>,
    onDone: (checkedIds: Set<String>, loaded: Map<String, ConversationSummary>) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = viewModel.sourceCandidates.collectAsLazyPagingItems()
    var query by remember { mutableStateOf("") }
    // Checked conversations by id; ones already chosen start checked (their summary arrives when they scroll in).
    val checked = remember { mutableStateMapOf<String, ConversationSummary?>().apply { selectedIds.forEach { put(it, null) } } }
    DisposableEffect(Unit) { onDispose { viewModel.setSourceQuery("") } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(R.string.fw_pick_channels),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                )
                Text(
                    stringResource(R.string.fw_pick_channels_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; viewModel.setSourceQuery(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.fw_search_channels)) },
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(count = candidates.itemCount) { index ->
                        val summary = candidates[index] ?: return@items
                        val isChecked = checked.containsKey(summary.conversationId)
                        ListItem(
                            modifier = Modifier.clickable {
                                if (isChecked) checked.remove(summary.conversationId) else checked[summary.conversationId] = summary
                            },
                            leadingContent = {
                                Avatar(name = summary.title, key = summary.conversationId, isBusiness = summary.isMergedSender)
                            },
                            headlineContent = { Text(summary.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    categoryLabel(summary.category) + " · " + summary.snippet,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Checkbox(checked = isChecked, onCheckedChange = { on ->
                                    if (on) checked[summary.conversationId] = summary else checked.remove(summary.conversationId)
                                })
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.fw_channels_selected, checked.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = {
                        val loaded = checked.entries.mapNotNull { (id, summary) -> summary?.let { id to it } }.toMap()
                        onDone(checked.keys.toSet(), loaded)
                    }) { Text(stringResource(R.string.fw_done)) }
                }
            }
        }
    }
}
