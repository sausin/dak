package app.dak.ui.sendergroups

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.core.model.SimInfo
import app.dak.index.enrich.FoldProposal
import app.dak.index.enrich.FoldSuggestionReason
import app.dak.index.repo.FoldChannel
import app.dak.index.repo.FoldGroup
import app.dak.navigation.DakNavigator
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.SimChip
import app.dak.ui.common.rememberRelativeTimeFormatter
import app.dak.ui.common.text.BidiText

/**
 * Sender groups: every folded conversation with its member channels (header or number, other prefixes seen, last
 * message, SIMs), rename / unfold a channel / unfold all, and fold suggestions. All edits are display-only and
 * offer undo.
 */
@Composable
fun SenderGroupsScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: SenderGroupsViewModel = hiltViewModel()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val sims by viewModel.sims.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<FoldGroup?>(null) }
    var dissolving by remember { mutableStateOf<FoldGroup?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FoldEvent.Folded, is FoldEvent.Unfolded -> {
                    val receipt = if (event is FoldEvent.Folded) event.receipt else (event as FoldEvent.Unfolded).receipt
                    val text = context.getString(if (event is FoldEvent.Folded) R.string.fold_snack_folded else R.string.fold_snack_unfolded)
                    val result = snackbar.showSnackbar(text, context.getString(R.string.scr_action_undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) viewModel.undo(receipt)
                }
                FoldEvent.Failed -> snackbar.showSnackbar(context.getString(R.string.fold_snack_failed))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { DakTopAppBar(title = stringResource(R.string.fold_screen_title), onBack = { navigator.back() }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = groups
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() && suggestions.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = stringResource(R.string.fold_empty_title),
                    body = stringResource(R.string.fold_empty_body),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(R.string.fold_screen_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    if (suggestions.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.fold_suggestions_header)) }
                        items(suggestions, key = { "s:" + it.key }) { proposal ->
                            SuggestionCard(proposal, onFold = { viewModel.accept(proposal) }, onDismiss = { viewModel.dismiss(proposal) })
                        }
                    }
                    if (list.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.fold_groups_header)) }
                        items(list, key = { "g:" + it.conversationId }) { group ->
                            GroupCard(
                                group = group,
                                sims = sims,
                                onOpen = { navigator.openConversation(group.conversationId) },
                                onRename = { renaming = group },
                                onDissolve = { dissolving = group },
                                onUnfold = { viewModel.unfold(it.channel) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    renaming?.let { group ->
        RenameDialog(
            initial = group.title,
            onSave = { name -> renaming = null; viewModel.rename(group, name) },
            onDismiss = { renaming = null },
        )
    }
    dissolving?.let { group ->
        AlertDialog(
            onDismissRequest = { dissolving = null },
            text = { Text(stringResource(R.string.fold_dissolve_confirm, BidiText.displaySafe(group.title))) },
            confirmButton = { TextButton(onClick = { dissolving = null; viewModel.dissolve(group) }) { Text(stringResource(R.string.fold_action_dissolve)) } },
            dismissButton = { TextButton(onClick = { dissolving = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SuggestionCard(proposal: FoldProposal, onFold: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.fold_suggestion_title, proposal.conversationIds.size, BidiText.displaySafe(proposal.title)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    when (proposal.reason) {
                        FoldSuggestionReason.SAME_BRAND -> R.string.fold_suggestion_same_brand
                        FoldSuggestionReason.SIMILAR_HEADER -> R.string.fold_suggestion_similar
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.fold_action_not_now)) }
                TextButton(onClick = onFold) { Text(stringResource(R.string.fold_action_fold)) }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: FoldGroup,
    sims: List<SimInfo>,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    onUnfold: (FoldChannel) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(BidiText.displaySafe(group.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.fold_channels_count, group.channels.size) + " · " +
                        stringResource(if (group.manual) R.string.fold_group_manual else R.string.fold_group_brand),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.fold_action_open)) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fold_action_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fold_action_dissolve)) }, onClick = { menu = false; onDissolve() })
                }
            }
        }
        for (channel in group.channels) {
            ChannelRow(channel, sims, canUnfold = group.channels.size > 1 || group.manual, onUnfold = { onUnfold(channel) })
        }
    }
}

@Composable
private fun ChannelRow(channel: FoldChannel, sims: List<SimInfo>, canUnfold: Boolean, onUnfold: () -> Unit) {
    val formatter = rememberRelativeTimeFormatter()
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(BidiText.displaySafe(channel.addresses.firstOrNull() ?: channel.channel), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val others = channel.addresses.drop(1)
            if (others.isNotEmpty()) {
                Text(
                    stringResource(R.string.fold_channel_also, others.joinToString { BidiText.displaySafe(it) }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.fold_channel_last_seen, formatter.formatAbsolute(channel.lastSeenMillis), channel.messageCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val channelSims = sims.filter { it.subId in channel.subIds }
            if (sims.size > 1 && channelSims.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    channelSims.forEach { SimChip(sim = it, compact = true) }
                }
            }
            if (channel.userRule) {
                Text(stringResource(R.string.fold_channel_manual), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (canUnfold) TextButton(onClick = onUnfold) { Text(stringResource(R.string.fold_action_unfold)) }
    }
}

/** Rename dialog shared by the groups screen and the "Fold together" flow. */
@Composable
internal fun RenameDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.fold_rename_title),
    allowEmpty: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_NAME) },
                singleLine = true,
                label = { Text(stringResource(if (allowEmpty) R.string.fold_name_hint else R.string.fold_rename_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = allowEmpty || text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_NAME = 60
