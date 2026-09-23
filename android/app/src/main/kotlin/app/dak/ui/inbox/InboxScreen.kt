package app.dak.ui.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import app.dak.R
import app.dak.core.model.Category
import app.dak.core.model.SimInfo
import app.dak.index.BackfillProgress
import app.dak.index.BackfillStage
import app.dak.index.ConversationSummary
import app.dak.index.InboxTab
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.common.Avatar
import app.dak.ui.common.CategoryChip
import app.dak.ui.common.EmptyState
import app.dak.ui.common.ReliabilityBanner
import app.dak.ui.common.SimChip
import app.dak.ui.common.relativeTime
import app.dak.ui.common.text.BidiText
import app.dak.ui.theme.DakTheme
import java.text.NumberFormat

private val TABS = listOf(
    InboxTab.ALL, InboxTab.PERSONAL, InboxTab.TRANSACTION, InboxTab.OTP, InboxTab.PROMOTION, InboxTab.SPAM,
    InboxTab.STARRED, InboxTab.ARCHIVED,
)

/**
 * The inbox: category tabs, SIM filter, pinned saved searches, reliability banner, index progress, conversation
 * list with swipe to archive (start→end) and delete to the bin (end→start) with undo, a search entry in the top bar
 * and an overflow to the bin, passbook, automations, backup, blocked numbers and settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: InboxViewModel = hiltViewModel()
    val context = LocalContext.current
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val simFilter by viewModel.simFilter.collectAsStateWithLifecycle()
    val sims by viewModel.sims.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val pinnedSearches by viewModel.pinnedSearches.collectAsStateWithLifecycle()
    val conversations = viewModel.conversationsPaged.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    var overflow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is InboxEvent.Archived -> {
                    val r = snackbar.showSnackbar(context.getString(R.string.scr_snack_archived), context.getString(R.string.scr_action_undo), duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) viewModel.unarchive(event.conversationId)
                }
                is InboxEvent.Deleted -> {
                    val r = snackbar.showSnackbar(context.getString(R.string.scr_snack_moved_to_bin), context.getString(R.string.scr_action_undo), duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.receipt)
                }
                InboxEvent.DeleteFailed -> snackbar.showSnackbar(context.getString(R.string.scr_snack_delete_failed))
                is InboxEvent.Automation -> {
                    val r = snackbar.showSnackbar(event.undo.token.description, context.getString(R.string.scr_action_undo), duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) viewModel.undoAutomation(event.undo)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { SearchEntry(onClick = { navigator.navigate(Routes.search()) }) },
                actions = {
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            listOf(
                                R.string.scr_bin_title to Routes.BIN,
                                R.string.scr_passbook_title to Routes.PASSBOOK,
                                R.string.scr_automations_title to Routes.AUTOMATIONS,
                                R.string.scr_backup_title to Routes.BACKUP,
                                R.string.scr_blocked_title to Routes.BLOCKED,
                                R.string.settings_title to Routes.settings(),
                            ).forEach { (label, route) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { overflow = false; navigator.navigate(route) })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navigator.navigate(Routes.compose()) }) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.scr_inbox_new_message))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ReliabilityBanner(navigator)
            ScrollableTabRow(selectedTabIndex = TABS.indexOf(tab).coerceAtLeast(0), edgePadding = 8.dp) {
                TABS.forEach { t ->
                    Tab(selected = t == tab, onClick = { viewModel.selectTab(t) }, text = { Text(tabLabel(t)) })
                }
            }
            val activeSims = sims.filter { it.isActive }
            if (activeSims.size > 1 || pinnedSearches.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (activeSims.size > 1) {
                        FilterChip(selected = simFilter == null, onClick = { viewModel.selectSim(null) }, label = { Text(stringResource(R.string.scr_inbox_all_sims)) })
                        activeSims.forEach { sim ->
                            FilterChip(
                                selected = simFilter == sim.subId,
                                onClick = { viewModel.selectSim(if (simFilter == sim.subId) null else sim.subId) },
                                label = { Text(sim.displayName.ifBlank { stringResource(R.string.sim_n, (sim.slotIndex + 1).toString()) }) },
                            )
                        }
                    }
                    pinnedSearches.forEach { saved ->
                        FilterChip(
                            selected = false,
                            onClick = { navigator.navigate(Routes.search(saved.query)) },
                            label = { Text(saved.name) },
                            leadingIcon = { Icon(Icons.Outlined.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            progress?.let { IndexProgressRow(it) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val refreshing = conversations.loadState.refresh is LoadState.Loading
                if (!refreshing && conversations.itemCount == 0) {
                    EmptyState(
                        icon = Icons.Outlined.Inbox,
                        title = stringResource(R.string.scr_inbox_empty_title),
                        body = stringResource(R.string.scr_inbox_empty_body),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    // Offset-paged source: no item keys, so a row moving between pages can never duplicate a key.
                    items(count = conversations.itemCount, contentType = conversations.itemContentType { "conversation" }) { index ->
                        val c = conversations[index] ?: return@items
                        SwipeableRow(
                            conversation = c,
                            onArchive = { viewModel.archive(c) },
                            onDelete = { viewModel.delete(c) },
                        ) {
                            ConversationRow(
                                conversation = c,
                                sims = sims,
                                showCategory = tab == InboxTab.ALL || tab == InboxTab.STARRED || tab == InboxTab.ARCHIVED,
                                onOpen = { navigator.openConversation(c.conversationId) },
                                onTogglePin = { viewModel.togglePinned(c) },
                                onToggleMute = { viewModel.toggleMuted(c) },
                                onMarkRead = { viewModel.markRead(c) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchEntry(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.scr_search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun tabLabel(tab: InboxTab): String = stringResource(
    when (tab) {
        InboxTab.ALL -> R.string.scr_tab_all
        InboxTab.PERSONAL -> R.string.category_personal
        InboxTab.TRANSACTION -> R.string.category_transactions
        InboxTab.OTP -> R.string.category_otp
        InboxTab.PROMOTION -> R.string.category_promotions
        InboxTab.SPAM -> R.string.category_spam
        InboxTab.ARCHIVED -> R.string.scr_tab_archived
        InboxTab.STARRED -> R.string.scr_tab_starred
    },
)

@Composable
private fun IndexProgressRow(progress: BackfillProgress) {
    if (progress.stage != BackfillStage.STAGE1 && progress.stage != BackfillStage.STAGE2) return
    val format = remember { NumberFormat.getIntegerInstance() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val text = when {
            progress.waiting -> stringResource(R.string.scr_inbox_index_waiting, format.format(progress.remaining))
            else -> stringResource(R.string.scr_inbox_index_progress, format.format(progress.done), format.format(progress.total))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(
    conversation: ConversationSummary,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onArchive()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // Always snap back: the list itself updates (the row leaves this tab) and a snackbar offers undo.
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val direction = state.dismissDirection
            val archive = direction == SwipeToDismissBoxValue.StartToEnd
            val tones = if (archive) DakTheme.colors.success else DakTheme.colors.spam
            Box(
                Modifier.fillMaxSize().background(if (direction == SwipeToDismissBoxValue.Settled) MaterialTheme.colorScheme.surface else tones.container).padding(horizontal = 24.dp),
                contentAlignment = if (archive) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        if (archive) Icons.Outlined.Archive else Icons.Outlined.Delete,
                        contentDescription = stringResource(if (archive) (if (conversation.archived) R.string.scr_action_unarchive else R.string.scr_action_archive) else R.string.scr_action_delete_to_bin),
                        tint = tones.content,
                    )
                }
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    sims: List<SimInfo>,
    showCategory: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onMarkRead: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val unread = conversation.unreadCount > 0
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .alpha(if (conversation.enriched) 1f else 0.85f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(
                name = conversation.title,
                key = conversation.address,
                isBusiness = conversation.isMergedSender || conversation.address.none { it.isDigit() },
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        BidiText.displaySafe(conversation.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.pinned) Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.scr_pinned), modifier = Modifier.size(14.dp))
                    if (conversation.muted) Icon(Icons.Outlined.NotificationsOff, contentDescription = stringResource(R.string.scr_muted), modifier = Modifier.size(14.dp))
                }
                Text(
                    BidiText.isolate(conversation.snippet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (showCategory && conversation.enriched && conversation.category != Category.UNKNOWN) CategoryChip(conversation.category)
                    val convSims = sims.filter { it.subId in conversation.subIds }
                    if (sims.size > 1) convSims.forEach { SimChip(sim = it, compact = true) }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    relativeTime(conversation.dateMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (unread) {
                    Box(
                        Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (conversation.pinned) R.string.scr_action_unpin else R.string.scr_action_pin)) },
                onClick = { menu = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (conversation.muted) R.string.scr_action_unmute else R.string.scr_action_mute)) },
                onClick = { menu = false; onToggleMute() },
            )
            if (unread) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_mark_read)) }, onClick = { menu = false; onMarkRead() })
            }
        }
    }
}
