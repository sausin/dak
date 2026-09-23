package app.dak.ui.bin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.core.model.Category
import app.dak.core.model.SimInfo
import app.dak.index.BinItem
import app.dak.index.bin.DeletedBy
import app.dak.navigation.DakNavigator
import app.dak.ui.common.CategoryChip
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.SimChip
import app.dak.ui.common.rememberRelativeTimeFormatter

/** The recycle bin: restore, delete forever, empty; shows who deleted each message and when it will be purged. */
@Composable
fun BinScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: BinViewModel = hiltViewModel()
    val context = LocalContext.current
    val unlocked by viewModel.unlocked.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val sims by viewModel.sims.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<BinItem?>(null) }
    val auth = rememberAuthGate()
    val authTitle = stringResource(R.string.scr_bin_unlock_title)

    fun unlock() {
        auth.authenticate(authTitle) { result ->
            // No screen lock at all: nothing to authenticate against, so the lock cannot apply.
            if (result == AuthResult.SUCCESS || result == AuthResult.UNAVAILABLE) viewModel.onUnlocked()
        }
    }

    LaunchedEffect(Unit) { if (!unlocked) unlock() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val text = when (event) {
                BinEvent.Restored -> context.getString(R.string.scr_bin_restored)
                BinEvent.RestoreFailed -> context.getString(R.string.scr_bin_restore_failed)
                is BinEvent.Emptied -> context.getString(R.string.scr_bin_emptied, event.count)
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            DakTopAppBar(title = stringResource(R.string.scr_bin_title), onBack = { navigator.back() }) {
                if (unlocked && !items.isNullOrEmpty()) {
                    TextButton(onClick = { confirmEmpty = true }) { Text(stringResource(R.string.scr_bin_empty_action)) }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = items
            when {
                !unlocked -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.scr_bin_locked_title),
                    body = stringResource(R.string.scr_bin_locked_body),
                    actionLabel = stringResource(R.string.scr_bin_unlock),
                    onAction = { unlock() },
                )
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.scr_bin_empty_title),
                    body = stringResource(R.string.scr_bin_empty_body),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(R.string.scr_bin_explainer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(list, key = { it.id }) { item ->
                        BinRow(
                            item = item,
                            sim = sims.firstOrNull { it.subId == item.subId },
                            onRestore = { viewModel.restore(item) },
                            onDelete = { confirmDelete = item },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(stringResource(R.string.scr_bin_empty_confirm_title)) },
            text = { Text(stringResource(R.string.scr_bin_empty_confirm_body)) },
            confirmButton = { TextButton(onClick = { confirmEmpty = false; viewModel.empty() }) { Text(stringResource(R.string.scr_bin_empty_action)) } },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.scr_bin_delete_forever_title)) },
            text = { Text(stringResource(R.string.scr_bin_delete_forever_body)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; viewModel.deleteForever(item) }) { Text(stringResource(R.string.scr_bin_delete_forever)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun BinRow(item: BinItem, sim: SimInfo?, onRestore: () -> Unit, onDelete: () -> Unit) {
    val formatter = rememberRelativeTimeFormatter()
    ListItem(
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.address, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.category != Category.UNKNOWN) CategoryChip(item.category)
                sim?.let { SimChip(sim = it, compact = true) }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.body, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    deletedByText(item) + " · " + formatter.format(item.deletedAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (item.purgeAtMillis == Long.MAX_VALUE) stringResource(R.string.scr_bin_kept_until_emptied)
                    else stringResource(R.string.scr_bin_purged_on, formatter.formatAbsolute(item.purgeAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) { Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.scr_bin_restore)) }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.scr_bin_delete_forever)) }
            }
        },
    )
}

@Composable
private fun deletedByText(item: BinItem): String = when (val who = DeletedBy.decode(item.deletedBy)) {
    DeletedBy.Manual -> stringResource(R.string.scr_bin_deleted_by_you)
    DeletedBy.AutoOtp -> stringResource(R.string.scr_bin_deleted_by_otp)
    is DeletedBy.AutoRule -> stringResource(R.string.scr_bin_deleted_by_rule, who.ruleName)
    is DeletedBy.AutoConsumed -> stringResource(R.string.scr_bin_deleted_by_consumed, who.packageName)
}
