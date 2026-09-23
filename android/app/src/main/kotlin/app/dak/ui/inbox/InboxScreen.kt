package app.dak.ui.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import app.dak.ui.conversation.copyToClipboard
import app.dak.ui.sendergroups.RenameDialog
import app.dak.ui.ux.InboxSwipeAction
import app.dak.ui.ux.UxPrefsViewModel
import java.text.NumberFormat
import kotlinx.coroutines.delay

private val TABS = listOf(
    InboxTab.ALL, InboxTab.PERSONAL, InboxTab.TRANSACTION, InboxTab.OTP, InboxTab.PROMOTION, InboxTab.SPAM,
    InboxTab.STARRED, InboxTab.ARCHIVED,
)

/**
 * The inbox: category tabs, SIM filter, pinned saved searches, reliability banner, index progress and the
 * conversation list. Everything frequent sits in the bottom thumb zone: a bottom bar with "More places" (a sheet with
 * every secondary screen), a search pill and the new-message FAB. Rows swipe left/right with user-configurable
 * actions (default: right archives, left deletes to the bin with undo); long-press starts multi-select with a bottom
 * action bar (archive, delete, read, pin, mute, fold together). A fresh OTP shows an inline "Copy code" chip.
 */
@Composable
fun InboxScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: InboxViewModel = hiltViewModel()
    val ux: UxPrefsViewModel = hiltViewModel()
    val context = LocalContext.current
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val simFilter by viewModel.simFilter.collectAsStateWithLifecycle()
    val sims by viewModel.sims.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val pinnedSearches by viewModel.pinnedSearches.collectAsStateWithLifecycle()
    val scamFlagged by hiltViewModel<ScamFlagsViewModel>().flagged.collectAsStateWithLifecycle()
    val swipeRight by ux.swipeRight.collectAsStateWithLifecycle()
    val swipeLeft by ux.swipeLeft.collectAsStateWithLifecycle()
    val otpCopyEnabled by ux.inboxOtpCopy.collectAsStateWithLifecycle()
    val conversations = viewModel.conversationsPaged.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    var placesOpen by remember { mutableStateOf(false) }
    // Selected conversations (id -> summary as last seen), in selection order.
    var selection by remember { mutableStateOf<Map<String, ConversationSummary>>(emptyMap()) }
    var foldDialog by remember { mutableStateOf(false) }
    // Ticks so a fresh OTP's "Copy code" chip disappears once it is older than ten minutes.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    // Back (including the predictive back gesture) leaves selection mode before it leaves the screen.
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptyMap() }
    LaunchedEffect(tab, simFilter) { selection = emptyMap() }

    LaunchedEffect(Unit) {
        val undo = context.getString(R.string.scr_action_undo)
        viewModel.events.collect { event ->
            when (event) {
                is InboxEvent.Archived -> {
                    val r = snackbar.showSnackbar(context.getString(R.string.scr_snack_archived), undo, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) viewModel.unarchive(event.conversationId)
                }
                is InboxEvent.ArchivedMany -> {
                    val n = event.conversationIds.size
                    val r = snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.ux_snack_archived_many, n, n), undo, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) viewModel.unarchiveAll(event.conversationIds)
                }
                is InboxEvent.Deleted -> {
                    val r = snackbar.showSnackbar(context.getString(R.string.scr_snack_moved_to_bin), undo, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.receipt)
                }
                InboxEvent.DeleteFailed -> snackbar.showSnackbar(context.getString(R.string.scr_snack_delete_failed))
                is InboxEvent.Pinned -> {
                    val n = event.conversationIds.size
                    val text = context.resources.getQuantityString(if (event.pinned) R.plurals.ux_snack_pinned else R.plurals.ux_snack_unpinned, n, n)
                    val r = snackbar.showSnackbar(text, undo, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) viewModel.setPinnedAll(event.conversationIds, !event.pinned)
                }
                is InboxEvent.MarkedRead ->
                    snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.ux_snack_marked_read, event.count, event.count))
                is InboxEvent.Automation -> {
                    val r = snackbar.showSnackbar(event.undo.token.description, undo, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) viewModel.undoAutomation(event.undo)
                }
                is InboxEvent.Folded -> {
                    val r = snackbar.showSnackbar(context.getString(R.string.fold_snack_folded), undo, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) viewModel.undoFold(event.receipt)
                }
                InboxEvent.FoldFailed -> snackbar.showSnackbar(context.getString(R.string.fold_snack_failed))
            }
        }
    }

    val selectionActions = object : SelectionActions {
        private fun take(): List<ConversationSummary> = selection.values.toList().also { selection = emptyMap() }
        override fun archive() = viewModel.archiveAll(take())
        override fun delete() = viewModel.deleteAll(take())
        override fun markRead() = viewModel.markReadAll(take())
        override fun pin() = viewModel.pinAll(take())
        override fun mute() = viewModel.muteAll(take())
        override fun fold() { foldDialog = true }
        override fun selectAll() {
            selection = selection + conversations.itemSnapshotList.items.filterNot { it.conversationId in selection }.associateBy { it.conversationId }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.isNotEmpty()) SelectionTopBar(count = selection.size, onClose = { selection = emptyMap() })
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                SelectionBottomBar(selected = selection.values, actions = selectionActions)
            } else {
                InboxBottomBar(
                    onPlaces = { placesOpen = true },
                    onSearch = { navigator.navigate(Routes.search()) },
                    onCompose = { navigator.navigate(Routes.compose()) },
                )
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
                val selectionMode = selection.isNotEmpty()
                LazyColumn(Modifier.fillMaxSize()) {
                    // Offset-paged source: no item keys, so a row moving between pages can never duplicate a key.
                    items(count = conversations.itemCount, contentType = conversations.itemContentType { "conversation" }) { index ->
                        val c = conversations[index] ?: return@items
                        val onAction: (InboxSwipeAction) -> Unit = { action ->
                            when (action) {
                                InboxSwipeAction.ARCHIVE -> viewModel.archive(c)
                                InboxSwipeAction.DELETE -> viewModel.delete(c)
                                InboxSwipeAction.MARK_READ -> viewModel.markReadAll(listOf(c))
                                InboxSwipeAction.PIN -> viewModel.pinAll(listOf(c))
                                InboxSwipeAction.NONE -> Unit
                            }
                        }
                        SwipeableRow(
                            conversation = c,
                            right = if (selectionMode) InboxSwipeAction.NONE else swipeRight,
                            left = if (selectionMode) InboxSwipeAction.NONE else swipeLeft,
                            onAction = onAction,
                        ) {
                            ConversationRow(
                                conversation = c,
                                sims = sims,
                                showCategory = tab == InboxTab.ALL || tab == InboxTab.STARRED || tab == InboxTab.ARCHIVED,
                                onOpen = {
                                    if (selection.isEmpty()) navigator.openConversation(c.conversationId)
                                    else selection = toggled(selection, c)
                                },
                                onToggleSelect = { selection = toggled(selection, c) },
                                onAction = onAction,
                                onToggleMute = { viewModel.muteAll(listOf(c)) },
                                selected = c.conversationId in selection,
                                selectionMode = selectionMode,
                                scamFlagged = c.conversationId in scamFlagged,
                                otpCode = if (otpCopyEnabled && !selectionMode) freshOtpCode(c, now) else null,
                                onCopyOtp = { code ->
                                    copyToClipboard(context, code, sensitive = true)
                                    viewModel.onOtpCopied(c, code)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (placesOpen) {
        PlacesSheet(
            onDismiss = { placesOpen = false },
            onNavigate = { route -> placesOpen = false; navigator.navigate(route) },
        )
    }

    if (foldDialog) {
        val chosen = selection
        RenameDialog(
            initial = chosen.values.firstOrNull()?.title.orEmpty(),
            title = stringResource(R.string.fold_name_title, chosen.size),
            allowEmpty = true,
            onSave = { name ->
                foldDialog = false
                selection = emptyMap()
                viewModel.foldTogether(chosen.keys.toList(), name.ifBlank { null })
            },
            onDismiss = { foldDialog = false },
        )
    }
}

/** [selection] with [conversation] added or removed. */
private fun toggled(selection: Map<String, ConversationSummary>, conversation: ConversationSummary): Map<String, ConversationSummary> =
    if (conversation.conversationId in selection) selection - conversation.conversationId
    else selection + (conversation.conversationId to conversation)

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

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    sims: List<SimInfo>,
    showCategory: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onAction: (InboxSwipeAction) -> Unit,
    onToggleMute: () -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    scamFlagged: Boolean,
    otpCode: String?,
    onCopyOtp: (String) -> Unit,
) {
    val context = LocalContext.current
    val unread = conversation.unreadCount > 0
    // Swipes are invisible to TalkBack and switch access: offer every row action as a custom accessibility action.
    val a11yActions = listOfNotNull(
        CustomAccessibilityAction(swipeLabel(InboxSwipeAction.ARCHIVE, conversation)) { onAction(InboxSwipeAction.ARCHIVE); true },
        CustomAccessibilityAction(swipeLabel(InboxSwipeAction.DELETE, conversation)) { onAction(InboxSwipeAction.DELETE); true },
        if (unread) CustomAccessibilityAction(swipeLabel(InboxSwipeAction.MARK_READ, conversation)) { onAction(InboxSwipeAction.MARK_READ); true } else null,
        CustomAccessibilityAction(swipeLabel(InboxSwipeAction.PIN, conversation)) { onAction(InboxSwipeAction.PIN); true },
        CustomAccessibilityAction(stringResource(if (conversation.muted) R.string.scr_action_unmute else R.string.scr_action_mute)) { onToggleMute(); true },
    )
    val openLabel = stringResource(if (!selectionMode) R.string.ux_action_open else if (selected) R.string.ux_action_deselect else R.string.ux_action_select)
    val selectLabel = stringResource(if (selected) R.string.ux_action_deselect else R.string.ux_action_select)
    val selectedText = stringResource(R.string.ux_selected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .semantics {
                customActions = a11yActions
                if (selectionMode) {
                    this.selected = selected
                    if (selected) stateDescription = selectedText
                }
            }
            .combinedClickable(onClick = onOpen, onClickLabel = openLabel, onLongClick = onToggleSelect, onLongClickLabel = selectLabel)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (conversation.enriched) 1f else 0.85f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        } else {
            Avatar(
                name = conversation.title,
                key = conversation.address,
                isBusiness = conversation.isMergedSender || conversation.address.none { it.isDigit() },
            )
        }
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
            if (otpCode != null) InboxOtpChip(code = otpCode, onCopy = { onCopyOtp(otpCode) }, modifier = Modifier.padding(top = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                if (scamFlagged) ScamWarningChip()
                if (conversation.snippetRepeatCount > 1) {
                    Text(
                        stringResource(R.string.fold_inbox_repeat, conversation.snippetRepeatCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                val unreadDescription = context.resources.getQuantityString(R.plurals.ux_unread_count, conversation.unreadCount, conversation.unreadCount)
                Box(
                    Modifier
                        .sizeIn(minWidth = 22.dp, minHeight = 22.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(horizontal = 4.dp)
                        .clearAndSetSemantics { contentDescription = unreadDescription },
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
}
