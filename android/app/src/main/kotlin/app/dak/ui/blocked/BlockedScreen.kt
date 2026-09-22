package app.dak.ui.blocked

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.navigation.DakNavigator
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState

/** Blocked numbers: the shared system block list (also applies to calls). */
@Composable
fun BlockedScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: BlockedViewModel = hiltViewModel()
    val context = LocalContext.current
    val numbers by viewModel.numbers.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmUnblock by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbar.showSnackbar(
                context.getString(
                    when (event) {
                        BlockedEvent.BLOCKED -> R.string.scr_blocked_added
                        BlockedEvent.UNBLOCKED -> R.string.scr_blocked_removed
                        BlockedEvent.FAILED -> R.string.scr_blocked_failed
                    },
                ),
            )
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { DakTopAppBar(title = stringResource(R.string.scr_blocked_title), onBack = { navigator.back() }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.scr_blocked_add)) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = numbers
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Block,
                    title = stringResource(R.string.scr_blocked_empty_title),
                    body = stringResource(R.string.scr_blocked_empty_body),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(R.string.scr_blocked_explainer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(list, key = { it }) { number ->
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.Block, contentDescription = null) },
                            headlineContent = { Text(number) },
                            trailingContent = { TextButton(onClick = { confirmUnblock = number }) { Text(stringResource(R.string.scr_blocked_unblock)) } },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (adding) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.scr_blocked_add)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.scr_blocked_number_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            },
            confirmButton = {
                TextButton(onClick = { adding = false; viewModel.block(text) }, enabled = text.isNotBlank()) {
                    Text(stringResource(R.string.scr_action_block_short))
                }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmUnblock?.let { number ->
        AlertDialog(
            onDismissRequest = { confirmUnblock = null },
            title = { Text(stringResource(R.string.scr_blocked_unblock_title, number)) },
            text = { Text(stringResource(R.string.scr_blocked_unblock_body)) },
            confirmButton = { TextButton(onClick = { confirmUnblock = null; viewModel.unblock(number) }) { Text(stringResource(R.string.scr_blocked_unblock)) } },
            dismissButton = { TextButton(onClick = { confirmUnblock = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
