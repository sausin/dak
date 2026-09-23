package app.dak.ui.notifications

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RestartAlt
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.navigation.DakNavigator
import app.dak.notifications.ChannelState
import app.dak.notifications.ConversationChannels
import app.dak.notifications.NotificationChannels
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.WarningBanner

/**
 * Notification channels: every Dak channel grouped as the system groups them (Incoming messages, one group per SIM on
 * multi-SIM devices, App), its current importance read from the system, a switch that opens the system page for
 * the channel (apps cannot change channel settings themselves), the per-conversation custom channels with remove,
 * warnings for blocked critical channels, and "Reset channels".
 */
@Composable
fun NotificationChannelsScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: NotificationChannelsViewModel = hiltViewModel()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<ConversationChannels.Custom?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        viewModel.resetResults.collect { result ->
            val text = if (result.keptCustomised.isEmpty()) {
                context.getString(R.string.ch_reset_done)
            } else {
                context.resources.getQuantityString(R.plurals.ch_reset_done_kept, result.keptCustomised.size, result.keptCustomised.size)
            }
            snackbar.showSnackbar(text)
        }
    }

    val openChannel: (String, String?) -> Unit = { channelId, conversationId ->
        val shortcut = conversationId?.let { ConversationChannels.shortcutIdFor(it) }
        if (!start(context, NotificationChannels.settingsIntent(context, channelId, shortcut))) openAppSettings(context)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            DakTopAppBar(
                title = stringResource(R.string.ch_title),
                onBack = { navigator.back() },
                actions = {
                    IconButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.ch_reset))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else LazyColumn(Modifier.fillMaxSize()) {
                if (ui.appBlocked) {
                    item(key = "app-blocked") {
                        WarningBanner(
                            title = stringResource(R.string.ch_app_blocked_title),
                            body = stringResource(R.string.ch_app_blocked_body),
                            actionLabel = stringResource(R.string.ch_open_settings),
                            onAction = { openAppSettings(context) },
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(ui.blockedCritical, key = { "warn-" + it.id }) { channel ->
                    WarningBanner(
                        title = stringResource(R.string.ch_critical_blocked_title, channelLabel(channel)),
                        body = stringResource(R.string.ch_critical_blocked_body),
                        actionLabel = stringResource(R.string.ch_open_settings),
                        onAction = { openChannel(channel.id, null) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item(key = "intro") {
                    Text(
                        stringResource(R.string.ch_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                ui.sections.forEach { section ->
                    item(key = "header-" + section.groupId) {
                        SectionHeader(section.title ?: stringResource(R.string.ch_group_other))
                    }
                    items(section.channels, key = { it.id }) { channel ->
                        ChannelRow(channel = channel, onOpen = { openChannel(channel.id, null) })
                    }
                }
                item(key = "header-conversations") { SectionHeader(stringResource(R.string.ch_conversations_title)) }
                if (ui.conversations.isEmpty()) {
                    item(key = "conversations-empty") {
                        Text(
                            stringResource(R.string.ch_conversations_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(ui.conversations, key = { "conv-" + it.channelId }) { custom ->
                    ListItem(
                        modifier = Modifier.clickable { openChannel(custom.channelId, custom.conversationId) },
                        headlineContent = { Text(custom.title) },
                        supportingContent = {
                            Text(stringResource(if (custom.blocked) R.string.ch_importance_off else R.string.ch_conversation_custom))
                        },
                        trailingContent = {
                            IconButton(onClick = { confirmRemove = custom }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.ch_remove))
                            }
                        },
                    )
                    HorizontalDivider()
                }
                item(key = "reset") {
                    ListItem(
                        modifier = Modifier.clickable { confirmReset = true },
                        leadingContent = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.ch_reset)) },
                        supportingContent = { Text(stringResource(R.string.ch_reset_summary)) },
                    )
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.ch_reset_confirm_title)) },
            text = { Text(stringResource(R.string.ch_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; viewModel.resetAll() }) { Text(stringResource(R.string.ch_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmRemove?.let { custom ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.ch_remove_confirm_title, custom.title)) },
            text = { Text(stringResource(R.string.ch_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = null; viewModel.removeConversation(custom.conversationId) }) {
                    Text(stringResource(R.string.ch_remove))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChannelRow(channel: ChannelState, onOpen: () -> Unit) {
    val state = stringResource(importanceLabel(channel.importance, channel.blocked))
    val customised = stringResource(R.string.ch_customised)
    val supporting = buildList {
        add(state)
        if (channel.customised) add(customised)
        channel.description?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(channel.name) },
        supportingContent = {
            Text(
                supporting,
                color = if (channel.blocked && channel.critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // Apps cannot switch channels on or off; the switch mirrors the state and opens the system page.
        trailingContent = { Switch(checked = !channel.blocked, onCheckedChange = { onOpen() }) },
    )
}

private fun channelLabel(channel: ChannelState): String =
    channel.groupName?.takeIf { channel.simSlot != null }?.let { "${channel.name} · $it" } ?: channel.name

private fun importanceLabel(importance: Int, blocked: Boolean): Int = when {
    blocked || importance == NotificationManagerCompat.IMPORTANCE_NONE -> R.string.ch_importance_off
    importance >= NotificationManagerCompat.IMPORTANCE_HIGH -> R.string.ch_importance_high
    importance == NotificationManagerCompat.IMPORTANCE_DEFAULT -> R.string.ch_importance_default
    importance == NotificationManagerCompat.IMPORTANCE_LOW -> R.string.ch_importance_low
    else -> R.string.ch_importance_min
}

private fun openAppSettings(context: Context) {
    start(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun start(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
}
