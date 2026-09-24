package app.dak.ui.broadcast

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.automations.broadcast.BroadcastLimits
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.relativeTime

/**
 * Broadcast lists: each list with its size and last send; tap opens the list's thread and composer. The "Use
 * broadcasts with care" terms must be accepted first (declining leaves the screen).
 */
@Composable
fun BroadcastsScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: BroadcastsViewModel = hiltViewModel()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val needsConsent by viewModel.needsConsent.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var showAup by rememberSaveable { mutableStateOf(false) }
    var contactsTick by remember { mutableIntStateOf(0) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsTick++ }

    Scaffold(
        modifier = modifier,
        topBar = {
            DakTopAppBar(
                title = stringResource(R.string.bc_title),
                onBack = { navigator.back() },
                actions = {
                    IconButton(onClick = { showAup = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.bc_aup_title))
                    }
                },
            )
        },
        floatingActionButton = {
            if (!needsConsent) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.bc_new_list)) },
                )
            }
        },
    ) { padding ->
        val list = rows
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (list != null && list.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Campaign,
                    title = stringResource(R.string.bc_empty_title),
                    body = stringResource(R.string.bc_empty_body),
                    actionLabel = if (needsConsent) null else stringResource(R.string.bc_new_list),
                    onAction = if (needsConsent) null else ({ creating = true }),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "limits") {
                        Text(
                            stringResource(
                                R.string.bc_limits_note,
                                BroadcastLimits.HARD_MAX_RECIPIENTS,
                                BroadcastLimits.HARD_MAX_MESSAGES_PER_DAY,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(list.orEmpty(), key = { it.list.id }) { row ->
                        val count = row.list.members.size
                        ListItem(
                            modifier = Modifier.clickable { navigator.navigate(Routes.broadcast(row.list.id)) },
                            leadingContent = {
                                Icon(Icons.Outlined.Campaign, contentDescription = null, modifier = Modifier.size(32.dp))
                            },
                            headlineContent = { Text(row.list.name) },
                            supportingContent = {
                                val members = quantityString(R.plurals.bc_members_count, count)
                                val last = row.lastSentMillis?.let { stringResource(R.string.bc_last_sent, relativeTime(it)) }
                                Text(listOfNotNull(members, last).joinToString(" · "))
                            },
                        )
                    }
                }
            }
        }
    }

    if (needsConsent) {
        BroadcastTermsSheet(onAccept = viewModel::acceptTerms, onDecline = { navigator.back() })
    }
    if (showAup) AcceptableUseDialog(onDismiss = { showAup = false })
    if (creating) {
        // Re-read the permission after the system dialog returns.
        val canRead = remember(contactsTick) { viewModel.canReadContacts }
        BroadcastListEditor(
            initial = null,
            canReadContacts = canRead,
            onRequestContacts = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
            search = viewModel::searchContacts,
            onSave = { name, members ->
                val id = viewModel.save(null, name, members)
                creating = false
                navigator.navigate(Routes.broadcast(id))
            },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
}
