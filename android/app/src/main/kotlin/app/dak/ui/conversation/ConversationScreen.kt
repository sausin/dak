package app.dak.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import app.dak.R
import app.dak.classify.ExtractedLink
import app.dak.core.model.MessageBox
import app.dak.core.model.SimInfo
import app.dak.index.MessageItem
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.telephony.MmsDownloadState
import app.dak.ui.common.Avatar
import app.dak.ui.theme.DakTheme
import kotlinx.coroutines.flow.Flow

private val FONT_SCALES = listOf(0.85f, 1f, 1.15f, 1.3f)

/**
 * The thread: paged bubbles (newest at the bottom), thread menu, per-message actions and the composer. Opened
 * from search it scrolls to and highlights the matching message and offers "Back to results".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: ConversationViewModel = hiltViewModel()
    val context = LocalContext.current
    val messages = viewModel.messages.collectAsLazyPagingItems()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val sims by viewModel.simList.collectAsStateWithLifecycle()
    val forwarded by viewModel.forwarded.collectAsStateWithLifecycle()
    val userLabels by viewModel.userLabels.collectAsStateWithLifecycle()
    val composerUi by viewModel.composer.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var menuOpen by remember { mutableStateOf(false) }
    var messageMenuFor by remember { mutableStateOf<MessageItem?>(null) }
    var linkWarning by remember { mutableStateOf<LinkWarning?>(null) }
    var simDialog by remember { mutableStateOf(false) }
    var styleDialog by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    var scrolledToHighlight by rememberSaveable { mutableStateOf(false) }

    val strings = remember(context) { ConversationStrings.load(context) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val threads = messages.itemSnapshotList.items.mapTo(HashSet()) { it.threadId }
        viewModel.onResumed(threads)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationEvent.Deleted -> {
                    val result = snackbar.showSnackbar(strings.deleted, actionLabel = strings.undo, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.receipt)
                }
                ConversationEvent.DeleteFailed -> snackbar.showSnackbar(strings.deleteFailed)
                is ConversationEvent.Blocked -> snackbar.showSnackbar(strings.blocked)
                ConversationEvent.BlockFailed -> snackbar.showSnackbar(strings.blockFailed)
                ConversationEvent.RetryFailed -> snackbar.showSnackbar(strings.retryFailed)
                ConversationEvent.Archived -> snackbar.showSnackbar(strings.archived)
                is ConversationEvent.Composer -> snackbar.showSnackbar(strings.composerMessage(event.event))
            }
        }
    }

    // Opened from search: keep loading until the highlighted message is in the list, then scroll to it.
    val highlight = viewModel.highlightKey
    LaunchedEffect(messages.itemCount, highlight) {
        if (highlight == null || scrolledToHighlight) return@LaunchedEffect
        val index = messages.itemSnapshotList.indexOfFirst { it?.key?.toString() == highlight }
        if (index >= 0) {
            listState.scrollToItem(index)
            scrolledToHighlight = true
        } else if (messages.itemCount in 1 until HIGHLIGHT_SEARCH_LIMIT && !messages.loadState.append.endOfPaginationReached) {
            messages[messages.itemCount - 1] // touching the last item makes Paging load the next (older) page
        }
    }

    val bubbleActions = remember(viewModel) {
        object : BubbleActions {
            override fun onLongPress(item: MessageItem) { messageMenuFor = item }
            override fun onLink(item: MessageItem, link: ExtractedLink) {
                val unknown = LinkSafety.UNKNOWN_SENDER_LINK_LABEL in item.labels
                val warning = LinkSafety.warningFor(link, unknown)
                if (warning != null) linkWarning = warning else LinkSafety.open(context, link.raw)
            }
            override fun onCopyOtp(item: MessageItem, code: String) {
                copyToClipboard(context, code, sensitive = true)
                viewModel.onOtpCopied(item.key)
            }
            override fun onDeleteOtp(item: MessageItem) = viewModel.deleteOtpNow(item.key)
            override fun onRetrySend(item: MessageItem) = viewModel.retrySend(item.key)
            override fun onRetryMms(item: MessageItem) = viewModel.retryMms(item.key)
            override fun mmsState(item: MessageItem): Flow<MmsDownloadState> = viewModel.mmsState(item.key)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(name = header.title, key = header.addresses.firstOrNull() ?: viewModel.conversationId, size = 36.dp, photoUri = header.photoUri, isBusiness = header.isBusiness)
                        Column {
                            Text(header.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            val subtitle = header.addresses.singleOrNull()?.takeIf { it != header.title }
                                ?: if (header.isGroup) stringResource(R.string.scr_conv_group_members, header.addresses.size) else null
                            if (subtitle != null) Text(subtitle, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (prefs.pinned) Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.scr_pinned), modifier = Modifier.size(16.dp))
                        if (prefs.muted) Icon(Icons.Outlined.NotificationsOff, contentDescription = stringResource(R.string.scr_muted), modifier = Modifier.size(16.dp))
                    }
                },
                actions = {
                    if (highlight != null) {
                        TextButton(onClick = { navigator.back() }) { Text(stringResource(R.string.scr_conv_back_to_results)) }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                        ThreadMenu(
                            expanded = menuOpen,
                            prefs = prefs,
                            canChooseSim = sims.count { it.isActive } > 1,
                            onDismiss = { menuOpen = false },
                            onPin = { viewModel.setPinned(!prefs.pinned) },
                            onMute = { viewModel.setMuted(!prefs.muted) },
                            onArchive = { viewModel.setArchived(!prefs.archived) },
                            onStar = { viewModel.setStarred(!prefs.starred) },
                            onReplySim = { simDialog = true },
                            onBubbleColour = { styleDialog = true },
                            onFontSize = { fontDialog = true },
                            onBlock = { viewModel.block() },
                            onReportSpam = {
                                val latest = messages.itemSnapshotList.items.firstOrNull { it.box == MessageBox.INBOX }
                                if (latest != null) navigator.navigate(Routes.compose(to = TRAI_SPAM_NUMBER, body = viewModel.spamReportBody(latest)))
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.loadState.refresh is LoadState.Loading && messages.itemCount == 0) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                    // No item keys: pages come from an offset-based source, and a row shifting between pages must never
                    // crash the list with a duplicate key. Newest-first + reverse layout keeps the bottom anchored.
                    items(count = messages.itemCount, contentType = messages.itemContentType { if (it.isOutgoing) "out" else "in" }) { index ->
                        val item = messages[index] ?: return@items
                        val sim = sims.firstOrNull { it.subId == item.subId }
                        val decor = BubbleDecor(
                            sim = sim,
                            senderName = if (header.isGroup && !item.isOutgoing) header.names[item.address] ?: item.address else null,
                            highlighted = item.key.toString() == highlight,
                            fontScale = prefs.fontScale,
                            outgoingColors = prefs.bubbleStyle?.let { DakTheme.colors.avatars.getOrNull(Math.floorMod(it, DakTheme.colors.avatars.size)) },
                            forwardedTo = forwarded[item.key.toString()],
                            labels = (item.labels - LinkSafety.UNKNOWN_SENDER_LINK_LABEL) + userLabels[item.key.toString()].orEmpty(),
                        )
                        Box {
                            MessageBubble(item = item, decor = decor, actions = bubbleActions)
                            MessageMenu(
                                item = item,
                                expanded = messageMenuFor?.key == item.key,
                                onDismiss = { messageMenuFor = null },
                                onCopy = { copyToClipboard(context, item.body, sensitive = item.otp != null) },
                                onStar = { viewModel.setMessageStarred(item.key, !item.starred) },
                                onForward = { navigator.navigate(Routes.compose(body = item.body)) },
                                onDelete = { viewModel.delete(item.key) },
                                onRetry = if (item.box == MessageBox.FAILED) ({ viewModel.retrySend(item.key) }) else null,
                                onReportSpam = if (!item.isOutgoing) ({ navigator.navigate(Routes.compose(to = TRAI_SPAM_NUMBER, body = viewModel.spamReportBody(item))) }) else null,
                            )
                        }
                    }
                }
            }
            Composer(ui = composerUi, text = viewModel.composer.draftText, actions = viewModel.composer)
        }
    }

    linkWarning?.let { warning ->
        LinkWarningDialog(
            warning = warning,
            onOpen = { linkWarning = null; LinkSafety.open(context, warning.verdict.link.raw) },
            onDismiss = { linkWarning = null },
        )
    }
    if (simDialog) {
        SimChooserDialog(
            sims = sims.filter { it.isActive },
            selected = composerUi.sim?.subId,
            onChoose = { simDialog = false; viewModel.chooseReplySim(it) },
            onDismiss = { simDialog = false },
        )
    }
    if (styleDialog) {
        BubbleStyleDialog(
            selected = prefs.bubbleStyle,
            onChoose = { styleDialog = false; viewModel.setBubbleStyle(it) },
            onDismiss = { styleDialog = false },
        )
    }
    if (fontDialog) {
        FontScaleDialog(
            selected = prefs.fontScale,
            onChoose = { fontDialog = false; viewModel.setFontScale(if (it == 1f) null else it) },
            onDismiss = { fontDialog = false },
        )
    }
}

/** TRAI's short code for reporting unsolicited commercial communication. */
private const val TRAI_SPAM_NUMBER = "1909"
private const val HIGHLIGHT_SEARCH_LIMIT = 3_000

@Composable
private fun ThreadMenu(
    expanded: Boolean,
    prefs: ThreadPrefsUi,
    canChooseSim: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onStar: () -> Unit,
    onReplySim: () -> Unit,
    onBubbleColour: () -> Unit,
    onFontSize: () -> Unit,
    onBlock: () -> Unit,
    onReportSpam: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun entry(label: Int, action: () -> Unit) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onDismiss(); action() })
        entry(if (prefs.pinned) R.string.scr_action_unpin else R.string.scr_action_pin, onPin)
        entry(if (prefs.muted) R.string.scr_action_unmute else R.string.scr_action_mute, onMute)
        entry(if (prefs.archived) R.string.scr_action_unarchive else R.string.scr_action_archive, onArchive)
        entry(if (prefs.starred) R.string.scr_action_unstar else R.string.scr_action_star, onStar)
        if (canChooseSim) entry(R.string.scr_action_reply_sim, onReplySim)
        entry(R.string.scr_action_bubble_colour, onBubbleColour)
        entry(R.string.scr_action_text_size, onFontSize)
        HorizontalDivider()
        entry(R.string.scr_action_block, onBlock)
        entry(R.string.scr_action_report_spam, onReportSpam)
    }
}

@Composable
private fun MessageMenu(
    item: MessageItem,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onStar: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onRetry: (() -> Unit)?,
    onReportSpam: (() -> Unit)?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun entry(label: Int, action: () -> Unit) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onDismiss(); action() })
        if (item.body.isNotEmpty()) entry(R.string.scr_action_copy_text, onCopy)
        entry(if (item.starred) R.string.scr_action_unstar else R.string.scr_action_star, onStar)
        if (item.body.isNotEmpty()) entry(R.string.scr_action_forward, onForward)
        onRetry?.let { entry(R.string.scr_action_retry, it) }
        onReportSpam?.let { entry(R.string.scr_action_report_spam, it) }
        entry(R.string.scr_action_delete_to_bin, onDelete)
    }
}

@Composable
private fun SimChooserDialog(sims: List<SimInfo>, selected: Int?, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scr_action_reply_sim)) },
        text = {
            Column {
                for (sim in sims) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(sim.subId) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        app.dak.ui.common.SimChip(sim = sim)
                        Text(sim.carrierName ?: sim.displayName, modifier = Modifier.weight(1f))
                        if (sim.subId == selected) Icon(Icons.Outlined.Check, contentDescription = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun BubbleStyleDialog(selected: Int?, onChoose: (Int?) -> Unit, onDismiss: () -> Unit) {
    val palette = DakTheme.colors.avatars
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scr_action_bubble_colour)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scr_bubble_colour_note), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(DakTheme.colors.bubbleOutgoing, DakTheme.colors.onBubbleOutgoing, selected == null) { onChoose(null) }
                    palette.forEachIndexed { index, tones ->
                        Swatch(tones.container, tones.content, selected == index) { onChoose(index) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Swatch(color: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, selected: Boolean, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = color, contentColor = content, modifier = Modifier.size(32.dp).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FontScaleDialog(selected: Float, onChoose: (Float) -> Unit, onDismiss: () -> Unit) {
    val labels = listOf(R.string.scr_font_small, R.string.scr_font_default, R.string.scr_font_large, R.string.scr_font_larger)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scr_action_text_size)) },
        text = {
            Column {
                FONT_SCALES.forEachIndexed { index, scale ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(scale) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(labels[index]),
                            style = MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = it.fontSize * scale) },
                            modifier = Modifier.weight(1f),
                        )
                        if (scale == selected) Icon(Icons.Outlined.Check, contentDescription = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Snackbar texts, resolved once per composition (snackbars are shown from coroutines). */
private class ConversationStrings(
    val deleted: String,
    val undo: String,
    val deleteFailed: String,
    val blocked: String,
    val blockFailed: String,
    val retryFailed: String,
    val archived: String,
    private val composer: ComposerStrings,
) {
    fun composerMessage(event: ComposerEvent): String = composer.message(event)

    companion object {
        fun load(context: android.content.Context) = ConversationStrings(
            deleted = context.getString(R.string.scr_snack_moved_to_bin),
            undo = context.getString(R.string.scr_action_undo),
            deleteFailed = context.getString(R.string.scr_snack_delete_failed),
            blocked = context.getString(R.string.scr_snack_blocked),
            blockFailed = context.getString(R.string.scr_snack_block_failed),
            retryFailed = context.getString(R.string.scr_snack_retry_failed),
            archived = context.getString(R.string.scr_snack_archived),
            composer = ComposerStrings.load(context),
        )
    }
}
