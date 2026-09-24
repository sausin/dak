package app.dak.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.navigation.DakNavigator
import app.dak.ui.common.DakTopAppBar

/**
 * One Settings section: its rows with current values, a collapsed "Advanced" block at the bottom, and
 * "Reset to defaults" in the overflow menu. A `focus` key scrolls to that row (opening Advanced if needed) and
 * flashes it.
 */
@Composable
fun SettingsGroupScreen(navigator: DakNavigator, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FollowAppLanguage(viewModel)
    val sectionId = viewModel.sectionId.orEmpty()
    val section = state.section(sectionId)
    val focus = viewModel.focusKey
    val snackbar = remember { SnackbarHostState() }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var focusHandled by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Deep-link focus: open Advanced when the row lives there, then scroll to it once the list has content.
    LaunchedEffect(section != null, focus) {
        if (section == null || focus == null || focusHandled) return@LaunchedEffect
        val mainIndex = section.rows.indexOfFirst { it.key == focus }
        val advancedIndex = section.advanced.indexOfFirst { it.key == focus }
        val target = when {
            mainIndex >= 0 -> mainIndex
            advancedIndex >= 0 -> {
                advancedOpen = true
                section.rows.size + 1 + advancedIndex
            }
            else -> -1
        }
        if (advancedIndex >= 0 && mainIndex < 0) withFrameNanos { } // let the Advanced rows compose first
        if (target >= 0) listState.animateScrollToItem(target)
        focusHandled = true
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            DakTopAppBar(
                title = section?.title ?: stringResource(R.string.settings_title),
                onBack = { navigator.back() },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_reset_group)) },
                            onClick = {
                                menuOpen = false
                                confirmReset = true
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (section == null) return@Scaffold
        SettingsInteractionHost(viewModel, navigator, snackbar) { onRowClick ->
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
                items(section.rows, key = { it.key }) { row ->
                    SettingRow(
                        row = row,
                        highlighted = row.key == focus,
                        onClick = { onRowClick(row) },
                        onToggle = { viewModel.toggle(row) },
                    )
                }
                if (section.advanced.isNotEmpty()) {
                    item(key = "advanced-header") {
                        Column {
                            HorizontalDivider()
                            val expansion = stringResource(if (advancedOpen) R.string.ux_expanded else R.string.ux_collapsed)
                            ListItem(
                                modifier = Modifier
                                    .clickable { advancedOpen = !advancedOpen }
                                    .semantics { heading(); stateDescription = expansion },
                                headlineContent = {
                                    Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.titleSmall)
                                },
                                supportingContent = {
                                    if (!advancedOpen) Text(section.advanced.joinToString(", ") { it.title })
                                },
                                trailingContent = {
                                    Icon(
                                        if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                    }
                    if (advancedOpen) {
                        items(section.advanced, key = { it.key }) { row ->
                            SettingRow(
                                row = row,
                                highlighted = row.key == focus,
                                onClick = { onRowClick(row) },
                                onToggle = { viewModel.toggle(row) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmReset && section != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_title, section.title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.reset(section.id)
                    confirmReset = false
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
