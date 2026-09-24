package app.dak.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.common.DakTopAppBar

/**
 * Settings root: a search field first, then the sections with their current values inline.
 * A `focus` argument (deep link / long-press on a control elsewhere) jumps straight to that row's section.
 */
@Composable
fun SettingsScreen(navigator: DakNavigator, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FollowAppLanguage(viewModel)
    val snackbar = remember { SnackbarHostState() }
    var handledFocus by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel.focusKey) {
        val focus = viewModel.focusKey
        if (!handledFocus && focus != null) {
            handledFocus = true
            viewModel.sectionIdFor(focus)?.let { navigator.navigate(Routes.settingsGroup(it, focus)) }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { DakTopAppBar(title = stringResource(R.string.settings_title), onBack = { navigator.back() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        SettingsInteractionHost(viewModel, navigator, snackbar) { onRowClick ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item(key = "search") {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                                }
                            }
                        },
                        singleLine = true,
                    )
                }
                if (state.query.isBlank()) {
                    // Privacy is one tap from here and the policy one more (Play: policy reachable in-app).
                    item(key = "privacy") {
                        val context = LocalContext.current
                        ListItem(
                            modifier = Modifier.clickable { SettingsActions.openPrivacy(context) },
                            leadingContent = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.privacy_title)) },
                            supportingContent = {
                                Text(stringResource(R.string.privacy_settings_entry_summary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                    items(state.sections, key = { it.id }) { section ->
                        ListItem(
                            modifier = Modifier.clickable { navigator.navigate(Routes.settingsGroup(section.id)) },
                            leadingContent = { Icon(sectionIcon(section.id), contentDescription = null) },
                            headlineContent = { Text(section.title) },
                            supportingContent = {
                                val summary = section.inlineSummary
                                if (summary.isNotEmpty()) {
                                    Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            },
                        )
                    }
                } else if (state.results.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.settings_no_results, state.query),
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.results, key = { it.row.key }) { hit ->
                        SettingRow(
                            row = hit.row,
                            sectionLabel = hit.sectionTitle,
                            onToggle = { viewModel.toggle(hit.row) },
                            onClick = {
                                if (hit.row.locked) onRowClick(hit.row)
                                else navigator.navigate(Routes.settingsGroup(hit.sectionId, hit.row.key))
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Icon for a section id. */
internal fun sectionIcon(id: String): ImageVector = when (id) {
    APPEARANCE_SECTION -> Icons.Outlined.Palette
    "NOTIFICATIONS" -> Icons.Outlined.Notifications
    "CATEGORIES_SPAM" -> Icons.Outlined.Category
    "FINANCE" -> Icons.Outlined.AccountBalanceWallet
    "SIMS_SENDING" -> Icons.Outlined.SimCard
    "BACKUP_DATA" -> Icons.Outlined.Backup
    "AUTOMATIONS" -> Icons.Outlined.Schedule
    "TRANSLATION" -> Icons.Outlined.Translate
    else -> Icons.Outlined.Category
}
