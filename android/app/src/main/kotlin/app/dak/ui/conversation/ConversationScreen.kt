package app.dak.ui.conversation

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import app.dak.R
import app.dak.classify.ExtractedLink
import app.dak.classify.scam.ScamLabels
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.SimInfo
import app.dak.index.MessageItem
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.telephony.MmsDownloadState
import app.dak.ui.common.Avatar
import app.dak.ui.common.text.BidiText
import app.dak.ui.notifications.CustomNotifications
import app.dak.ui.sendergroups.ChannelFilterRow
import app.dak.ui.sendergroups.ConversationFoldEvent
import app.dak.ui.sendergroups.ConversationFoldViewModel
import app.dak.ui.sendergroups.FoldIntoDialog
import app.dak.ui.sendergroups.UnfoldChannelDialog
import app.dak.ui.theme.DakTheme
import app.dak.ui.ux.UxPrefsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private val FONT_SCALES = listOf(0.85f, 1f, 1.15f, 1.3f)

/**
 * The thread: paged bubbles (newest at the bottom), thread menu, per-message actions and the composer. Opened
 * from search it scrolls to and highlights the matching message and offers "Back to results". Long-press starts
 * multi-select (tap toggles, back exits) with a contextual top bar: copy, forward, delete, and the per-message
 * sheet while one message is selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: ConversationViewModel = hiltViewModel()
    val foldVm: ConversationFoldViewModel = hiltViewModel()
    val context = LocalContext.current
    val channelFilter by foldVm.channelFilter.collectAsStateWithLifecycle()
    val foldChannels by foldVm.channels.collectAsStateWithLifecycle()
    val messages = (if (channelFilter == null) viewModel.messages else foldVm.filteredMessages).collectAsLazyPagingItems()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val sims by viewModel.simList.collectAsStateWithLifecycle()
    val forwarded by viewModel.forwarded.collectAsStateWithLifecycle()
    val userLabels by viewModel.userLabels.collectAsStateWithLifecycle()
    val composerUi by viewModel.composer.ui.collectAsStateWithLifecycle()
    val vanishing by viewModel.vanishing.collectAsStateWithLifecycle()
    val scheduled by viewModel.scheduled.collectAsStateWithLifecycle()
    val failedSends by viewModel.failedSends.collectAsStateWithLifecycle()
    val enterToSend by hiltViewModel<UxPrefsViewModel>().enterToSend.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val composerFocus = remember { FocusRequester() }
    var infoFor by remember { mutableStateOf<MessageItem?>(null) }

    var menuOpen by remember { mutableStateOf(false) }
    var messageMenuFor by remember { mutableStateOf<MessageItem?>(null) }
    var linkWarning by remember { mutableStateOf<LinkWarning?>(null) }
    var simDialog by remember { mutableStateOf(false) }
    var styleDialog by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    var foldIntoDialog by remember { mutableStateOf(false) }
    var unfoldDialog by remember { mutableStateOf(false) }
    var contactSheet by rememberSaveable { mutableStateOf(false) }
    var incognitoDialog by rememberSaveable { mutableStateOf(false) }
    var scrolledToHighlight by rememberSaveable { mutableStateOf(false) }
    // Multi-select: keys (MessageKey.toString()) of the selected messages; saved so rotation keeps the selection.
    var selection by rememberSaveable(stateSaver = SELECTION_SAVER) { mutableStateOf(emptySet<String>()) }
    var confirmDeleteSelected by rememberSaveable { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    // Ticks so a fresh OTP's copy affordances go away once it is older than OTP_COPY_WINDOW_MILLIS.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    val strings = remember(context) { ConversationStrings.load(context) }

    // Back (including the predictive back gesture) leaves selection mode before it leaves the thread.
    BackHandler(enabled = selecting) { selection = emptySet() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val threads = messages.itemSnapshotList.items.mapTo(HashSet()) { it.threadId }
        viewModel.onResumed(threads)
    }
    // Leaving the thread (or the app): read incognito messages vanish now.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPaused() }

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

    LaunchedEffect(foldVm) {
        foldVm.events.collect { event ->
            when (event) {
                is ConversationFoldEvent.Moved -> {
                    // This conversation now lives inside another one: show that one instead.
                    navigator.back()
                    navigator.openConversation(event.conversationId)
                }
                is ConversationFoldEvent.Unfolded -> {
                    val result = snackbar.showSnackbar(context.getString(R.string.fold_snack_unfolded), actionLabel = strings.undo, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) foldVm.undo(event.receipt)
                }
                ConversationFoldEvent.Failed -> snackbar.showSnackbar(context.getString(R.string.fold_snack_failed))
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

    // Copies a code or amount and confirms with a snackbar (a polite live region, so TalkBack reads it out).
    fun copyQuick(item: MessageItem, quick: QuickCopy) {
        copyToClipboard(context, quick.text, sensitive = quick is QuickCopy.Code)
        if (quick is QuickCopy.Code) viewModel.onOtpCopied(item.key)
        val message = context.getString(if (quick is QuickCopy.Code) R.string.ux_snack_code_copied else R.string.ux_snack_amount_copied)
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) }
    }

    val bubbleActions = remember(viewModel, foldVm) {
        object : BubbleActions {
            override fun onLongPress(item: MessageItem) { selection = MessageSelection.toggled(selection, item.key.toString()) }
            override fun onToggleSelect(item: MessageItem) { selection = MessageSelection.toggled(selection, item.key.toString()) }
            override fun onShowActions(item: MessageItem) { messageMenuFor = item }
            override fun onLink(item: MessageItem, link: ExtractedLink) {
                val unknown = LinkSafety.UNKNOWN_SENDER_LINK_LABEL in item.labels
                // A link in a message flagged as a fake credit alert is never one tap away, whatever its domain.
                val warning = LinkSafety.warningFor(link, unknown, scamFlagged = ScamLabels.fromLabels(item.labels) != null)
                if (warning != null) linkWarning = warning.copy(messageKey = item.key.toString()) else LinkSafety.open(context, link.url)
            }
            override fun onCopyOtp(item: MessageItem, code: String) {
                copyToClipboard(context, code, sensitive = true)
                viewModel.onOtpCopied(item.key)
            }
            override fun onDeleteOtp(item: MessageItem) = viewModel.deleteOtpNow(item.key)
            override fun onRetrySend(item: MessageItem) = viewModel.onBubbleRetry(item)
            override fun onRetryMms(item: MessageItem) = viewModel.retryMms(item.key)
            override fun mmsState(item: MessageItem): Flow<MmsDownloadState> = viewModel.mmsState(item.key)
            override suspend fun repeatsOf(item: MessageItem): List<MessageItem> = foldVm.repeatsOf(item.key)
            override fun onNavigate(route: String) = navigator.navigate(route)
            override fun onReply(item: MessageItem) {
                viewModel.composer.onTextChange(withQuote(viewModel.composer.draftText, item))
                runCatching { composerFocus.requestFocus() }
            }
            override fun onDoubleTap(item: MessageItem) {
                val quick = QuickCopy.of(item, System.currentTimeMillis()) ?: return
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                copyQuick(item, quick)
            }
        }
    }

    // The selected messages that are loaded, oldest first; copy and forward read them in thread order.
    val selectedItems = if (selecting) MessageSelection.resolve(selection, messages.itemSnapshotList.items) else emptyList()
    val selectedText = MessageSelection.copyText(selectedItems)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                MessageSelectionTopBar(
                    count = selection.size,
                    actions = MessageSelectionActions(
                        onClose = { selection = emptySet() },
                        onCopy = if (selectedText.isEmpty()) null else ({
                            copyToClipboard(context, selectedText, sensitive = selectedItems.any { it.otp != null })
                            val copied = selectedItems.count { it.body.isNotBlank() }
                            val message = context.resources.getQuantityString(R.plurals.ux_snack_messages_copied, copied, copied)
                            selection = emptySet()
                            scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) }
                        }),
                        onForward = if (selectedText.isEmpty()) null else ({
                            selection = emptySet()
                            navigator.navigate(Routes.compose(body = selectedText))
                        }),
                        onDelete = { confirmDeleteSelected = true },
                        onSelectAll = {
                            selection = MessageSelection.withAll(selection, messages.itemSnapshotList.items.map { it.key.toString() })
                        },
                        onMessageActions = selectedItems.singleOrNull()?.takeIf { selection.size == 1 }?.let { item ->
                            { selection = emptySet(); messageMenuFor = item }
                        },
                    ),
                )
            } else TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    // Tapping who the thread is with shows their details (and "Save contact" for an unsaved number).
                    Row(
                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.cd_open_details)) { contactSheet = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Avatar(name = header.title, key = header.addresses.firstOrNull() ?: viewModel.conversationId, size = 36.dp, photoUri = header.photoUri, isBusiness = header.isBusiness)
                        Column {
                            Text(BidiText.displaySafe(header.title), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            val subtitle = header.addresses.singleOrNull()?.takeIf { it != header.title }
                                ?: if (header.isGroup) stringResource(R.string.scr_conv_group_members, header.addresses.size) else null
                            if (subtitle != null) Text(BidiText.isolate(subtitle), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (prefs.pinned) Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.scr_pinned), modifier = Modifier.size(16.dp))
                        if (prefs.muted) Icon(Icons.Outlined.NotificationsOff, contentDescription = stringResource(R.string.scr_muted), modifier = Modifier.size(16.dp))
                        if (prefs.incognito) Icon(Icons.Outlined.VisibilityOff, contentDescription = stringResource(R.string.inc_label), modifier = Modifier.size(16.dp))
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
                            onIncognito = {
                                when {
                                    prefs.incognito -> viewModel.setIncognito(false)
                                    // The first time, explain it properly: only this phone's copy can vanish.
                                    viewModel.needsIncognitoIntro() -> incognitoDialog = true
                                    else -> {
                                        viewModel.setIncognito(true)
                                        scope.launch { snackbar.showSnackbar(context.getString(R.string.inc_snack_on)) }
                                    }
                                }
                            },
                            onReplySim = { simDialog = true },
                            onBubbleColour = { styleDialog = true },
                            onFontSize = { fontDialog = true },
                            onCustomNotifications = { CustomNotifications.open(context, viewModel.conversationId, header.title, header.addresses) },
                            onBlock = { viewModel.block() },
                            onFoldInto = { foldIntoDialog = true },
                            onUnfold = if (foldChannels.size > 1) ({ unfoldDialog = true }) else null,
                            // TRAI's 1909 complaint exists only in India; elsewhere "Report fraud" covers block / copy.
                            onReportSpam = messages.itemSnapshotList.items.firstOrNull { it.box == MessageBox.INBOX }
                                ?.takeIf { viewModel.reportsSpamToTrai(it.subId) }
                                ?.let { latest ->
                                    val report: () -> Unit = {
                                        navigator.navigate(Routes.compose(to = TRAI_SPAM_NUMBER, body = viewModel.spamReportBody(latest), subId = latest.subId))
                                    }
                                    report
                                },
                            onReportFraud = {
                                val latest = messages.itemSnapshotList.items.firstOrNull { it.box == MessageBox.INBOX }
                                navigator.navigate(Routes.fraudHelp(latest?.key?.toString()))
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // A different channel shows different messages: start the selection over.
            ChannelFilterRow(channels = foldChannels, selected = channelFilter, onSelect = { selection = emptySet(); foldVm.selectChannel(it) })
            if (prefs.incognito) IncognitoBanner(onTurnOff = { viewModel.setIncognito(false) })
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
                            labels = (item.labels - LinkSafety.UNKNOWN_SENDER_LINK_LABEL).filterNotTo(HashSet<String>(), ScamLabels::isScamLabel) +
                                userLabels[item.key.toString()].orEmpty(),
                            channelLabel = if (foldChannels.size > 1 && !item.isOutgoing) item.address else null,
                            sims = if (item.repeatCount > 1) sims else emptyList(),
                            canReply = composerUi.enabled && !header.isBusiness,
                            otpCopyable = isOtpCopyable(item, now),
                            selectionMode = selecting,
                            selected = item.key.toString() in selection,
                        )
                        VanishingBubble(vanishing = item.key in vanishing, seed = item.key.hashCode()) {
                            Column {
                                ScamWarningBanner(item = item, onReport = { key -> navigator.navigate(Routes.fraudHelp(key)) })
                                MessageBubble(item = item, decor = decor, actions = bubbleActions)
                                // Incognito: a received message is readable for a short window, then dissolves.
                                if (prefs.incognito && viewModel.vanishesOnRead(item) && item.key !in vanishing) {
                                    VanishCountdown(
                                        key = item.key,
                                        onViewed = { viewModel.onIncomingViewed(item) },
                                        onElapsed = { viewModel.vanishNow(item) },
                                    )
                                }
                                // Incognito send that failed: the user decides (Retry / Delete, then Keep), with a timeout.
                                if (item.key !in vanishing) {
                                    viewModel.failedPromptFor(item, failedSends)?.let { step ->
                                        FailedSendPrompt(
                                            key = item.key,
                                            step = step,
                                            onRetry = { viewModel.retryIncognito(item) },
                                            onKeep = { viewModel.keepFailed(item) },
                                            onDelete = { viewModel.discardFailed(item) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                JumpToLatest(listState = listState, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
            ScheduledStrip(
                items = scheduled,
                onOpen = { id -> navigator.navigate(Routes.scheduledSends(scheduledId = id)) },
                onCancel = viewModel::cancelScheduled,
            )
            Composer(
                ui = composerUi,
                text = viewModel.composer.draftText,
                actions = viewModel.composer,
                enterToSend = enterToSend,
                focusRequester = composerFocus,
            )
        }
    }

    if (contactSheet) {
        ContactDetailsSheet(
            header = header,
            conversationKey = viewModel.conversationId,
            onBlock = { viewModel.block() },
            onContactsChanged = viewModel::refreshContacts,
            onDismiss = { contactSheet = false },
        )
    }
    if (incognitoDialog) {
        IncognitoConfirmDialog(
            onConfirm = { incognitoDialog = false; viewModel.setIncognito(true) },
            onDismiss = { incognitoDialog = false },
        )
    }
    if (confirmDeleteSelected && selecting) {
        DeleteSelectedDialog(
            count = selection.size,
            onConfirm = {
                confirmDeleteSelected = false
                viewModel.deleteAll(selection.mapNotNull(MessageKey::parse))
                selection = emptySet()
            },
            onDismiss = { confirmDeleteSelected = false },
        )
    }
    messageMenuFor?.let { item ->
        val quick = QuickCopy.of(item, System.currentTimeMillis())
        MessageActionsSheet(
            item = item,
            onDismiss = { messageMenuFor = null },
            actions = MessageSheetActions(
                onCopyText = {
                    copyToClipboard(context, item.body, sensitive = item.otp != null)
                },
                onCopyCode = (quick as? QuickCopy.Code)?.let { code -> { copyQuick(item, code) } },
                onCopyAmount = (quick as? QuickCopy.Amount)?.let { amount -> { copyQuick(item, amount) } },
                onReply = if (composerUi.enabled && !header.isBusiness && item.body.isNotEmpty()) ({ bubbleActions.onReply(item) }) else null,
                onForward = if (item.body.isNotEmpty()) ({ navigator.navigate(Routes.compose(body = item.body)) }) else null,
                onStar = { viewModel.setMessageStarred(item.key, !item.starred) },
                onRetry = if (item.tickState == TickState.FAILED) ({ viewModel.retrySend(item.key) }) else null,
                onDelete = { viewModel.delete(item.key) },
                onReportFraud = if (!item.isOutgoing) ({ navigator.navigate(Routes.fraudHelp(item.key.toString())) }) else null,
                onReportSpam = if (!item.isOutgoing && viewModel.reportsSpamToTrai(item.subId)) ({ navigator.navigate(Routes.compose(to = TRAI_SPAM_NUMBER, body = viewModel.spamReportBody(item), subId = item.subId)) }) else null,
                onInfo = { infoFor = item },
            ),
        )
    }
    infoFor?.let { item ->
        MessageInfoDialog(item = item, sim = sims.firstOrNull { it.subId == item.subId }, onDismiss = { infoFor = null })
    }
    linkWarning?.let { warning ->
        LinkWarningDialog(
            warning = warning,
            onOpen = { linkWarning = null; LinkSafety.open(context, warning.verdict.link.url) },
            onDismiss = { linkWarning = null },
            onReport = warning.messageKey?.let { key -> { linkWarning = null; navigator.navigate(Routes.fraudHelp(key)) } },
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
    if (foldIntoDialog) {
        val targets by foldVm.foldTargets.collectAsStateWithLifecycle()
        FoldIntoDialog(targets = targets, onPick = { foldIntoDialog = false; foldVm.foldInto(it) }, onDismiss = { foldIntoDialog = false })
    }
    if (unfoldDialog) {
        UnfoldChannelDialog(channels = foldChannels, onPick = { unfoldDialog = false; foldVm.unfold(it.channel) }, onDismiss = { unfoldDialog = false })
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

/** Saves the selected message keys as a list (a plain Set is not guaranteed to be Bundle-saveable). */
private val SELECTION_SAVER = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

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
    onIncognito: () -> Unit,
    onReplySim: () -> Unit,
    onBubbleColour: () -> Unit,
    onFontSize: () -> Unit,
    onCustomNotifications: () -> Unit,
    onBlock: () -> Unit,
    onFoldInto: (() -> Unit)? = null,
    onUnfold: (() -> Unit)? = null,
    onReportSpam: (() -> Unit)?,
    onReportFraud: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun entry(label: Int, action: () -> Unit) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onDismiss(); action() })
        entry(if (prefs.pinned) R.string.scr_action_unpin else R.string.scr_action_pin, onPin)
        entry(if (prefs.muted) R.string.scr_action_unmute else R.string.scr_action_mute, onMute)
        entry(if (prefs.archived) R.string.scr_action_unarchive else R.string.scr_action_archive, onArchive)
        entry(if (prefs.starred) R.string.scr_action_unstar else R.string.scr_action_star, onStar)
        entry(if (prefs.incognito) R.string.inc_action_off else R.string.inc_action_on, onIncognito)
        if (canChooseSim) entry(R.string.scr_action_reply_sim, onReplySim)
        entry(R.string.scr_action_bubble_colour, onBubbleColour)
        entry(R.string.scr_action_text_size, onFontSize)
        entry(R.string.ch_action_custom_notifications, onCustomNotifications)
        onFoldInto?.let { entry(R.string.fold_action_fold_into, it) }
        onUnfold?.let { entry(R.string.fold_action_unfold_channel, it) }
        HorizontalDivider()
        entry(R.string.scr_action_block, onBlock)
        onReportSpam?.let { entry(R.string.scr_action_report_spam, it) }
        entry(R.string.safe_action_report_fraud, onReportFraud)
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
    // 48dp touch target around a 32dp swatch; "selected" is exposed to TalkBack.
    Box(
        modifier = Modifier.size(48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = color, contentColor = content, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
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
