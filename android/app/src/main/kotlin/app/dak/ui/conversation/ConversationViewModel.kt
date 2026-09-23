package app.dak.ui.conversation

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.dak.automation.IndexAuditSink
import app.dak.automation.ScheduledSendScheduler
import app.dak.automation.UserLabels
import app.dak.core.model.MessageKey
import app.dak.core.model.NO_SUB_ID
import app.dak.core.model.SimInfo
import app.dak.di.AndroidContactLookup
import app.dak.index.MessageItem
import app.dak.index.bin.BinReceipt
import app.dak.index.bin.DeletedBy
import app.dak.index.bin.RecycleBin
import app.dak.index.enrich.ConversationIds
import app.dak.index.otp.OtpLifecycle
import app.dak.index.repo.AuditLogRepository
import app.dak.index.repo.ConversationRepository
import app.dak.index.repo.SenderMergeRepository
import app.dak.index.sync.IndexSync
import app.dak.navigation.Routes
import app.dak.notifications.MessageNotifier
import app.dak.telephony.BlockedNumbers
import app.dak.telephony.MessageSender
import app.dak.telephony.MmsDownloadState
import app.dak.telephony.MmsDownloads
import app.dak.telephony.SendResult
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Title and recipients of the open conversation. */
data class ConversationHeader(
    val title: String,
    val addresses: List<String>,
    val isGroup: Boolean,
    val isBusiness: Boolean,
    val photoUri: String?,
    /** Display name per raw address (contact name or the address), for group sender attribution. */
    val names: Map<String, String> = emptyMap(),
)

/** Per-thread preferences as the screen needs them. */
data class ThreadPrefsUi(
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val starred: Boolean = false,
    /** Index into the avatar palette used as the outgoing bubble colour; null = theme default. */
    val bubbleStyle: Int? = null,
    val fontScale: Float = 1f,
)

/** One-off messages for the snackbar host. */
sealed interface ConversationEvent {
    data class Composer(val event: ComposerEvent) : ConversationEvent
    data class Deleted(val receipt: BinReceipt) : ConversationEvent
    data object DeleteFailed : ConversationEvent
    data class Blocked(val count: Int) : ConversationEvent
    data object BlockFailed : ConversationEvent
    data object RetryFailed : ConversationEvent
    data object Archived : ConversationEvent
}

/**
 * Thread view: paged bubbles, header, thread menu state, per-message actions and the composer. Reads
 * `conversationId` and the optional `highlight` (message key from search) from [SavedStateHandle].
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val conversations: ConversationRepository,
    private val merges: SenderMergeRepository,
    private val contacts: AndroidContactLookup,
    private val sims: SimRepository,
    private val bin: RecycleBin,
    private val otpLifecycle: OtpLifecycle,
    private val sender: MessageSender,
    private val mms: MmsDownloads,
    private val blocked: BlockedNumbers,
    private val notifier: MessageNotifier,
    private val indexSync: IndexSync,
    private val labels: UserLabels,
    audit: AuditLogRepository,
    controller: MessageSendController,
    scheduler: ScheduledSendScheduler,
    hints: ComposerHints,
) : ViewModel() {

    val conversationId: String = Uri.decode(savedState.get<String>(Routes.ARG_CONVERSATION_ID).orEmpty())

    /** Message key string to scroll to and flash (opened from search), or null. */
    val highlightKey: String? = savedState.get<String>(Routes.ARG_HIGHLIGHT)?.let(Uri::decode)?.takeIf { it.isNotBlank() }

    val messages: Flow<PagingData<MessageItem>> = conversations.messages(conversationId).cachedIn(viewModelScope)

    private val headerState = MutableStateFlow(ConversationHeader(title = "", addresses = emptyList(), isGroup = false, isBusiness = false, photoUri = null))
    val header: StateFlow<ConversationHeader> = headerState

    val prefs: StateFlow<ThreadPrefsUi> = conversations.prefs(conversationId).map { p ->
        ThreadPrefsUi(
            pinned = p?.pinned ?: false,
            muted = p?.muted ?: false,
            archived = p?.archived ?: false,
            starred = p?.starred ?: false,
            bubbleStyle = p?.bubbleColor,
            fontScale = p?.fontScale ?: 1f,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadPrefsUi())

    val simList: StateFlow<List<SimInfo>> = sims.sims

    /** Message key → recipient it was forwarded to by an automation (shown as "Forwarded to …"). */
    val forwarded: StateFlow<Map<String, String>> = audit.recent(FORWARD_LOOKBACK).map { rows ->
        rows.asSequence()
            .filter { it.actionName.startsWith(IndexAuditSink.ACTION_PREFIX) && it.target != null }
            .mapNotNull { row -> IndexAuditSink.forwardedRecipient(row.detail)?.let { row.target!! to it } }
            .toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** User/automation labels by message key. */
    val userLabels: StateFlow<Map<String, Set<String>>> = labels.all

    private val eventChannel = Channel<ConversationEvent>(Channel.BUFFERED)
    val events: Flow<ConversationEvent> = eventChannel.receiveAsFlow()

    private val recipients = MutableStateFlow<List<String>>(emptyList())

    val composer = ComposerDelegate(
        scope = viewModelScope,
        controller = controller,
        scheduler = scheduler,
        sims = sims,
        hints = hints,
        savedState = savedState,
        recipients = recipients,
        subId = MutableStateFlow(NO_SUB_ID),
        threadId = { ConversationIds.threadIdOf(conversationId) },
        conversationId = { conversationId },
        onSubIdChosen = { conversations.setReplySim(conversationId, it) },
        onEvent = { eventChannel.trySend(ConversationEvent.Composer(it)) },
    )

    init {
        viewModelScope.launch {
            val addresses = runCatching { conversations.addressesFor(conversationId) }.getOrDefault(emptyList())
            recipients.value = addresses
            headerState.value = buildHeader(addresses)
            composer.subId.value = runCatching { conversations.replySimFor(conversationId) }.getOrDefault(sims.defaultSmsSubId())
        }
    }

    /** Marks the conversation read and clears its notifications; called whenever the screen resumes. */
    fun onResumed(visibleThreadIds: Set<Long>) {
        viewModelScope.launch {
            runCatching { conversations.markRead(conversationId) }
            val threads = visibleThreadIds + listOfNotNull(ConversationIds.threadIdOf(conversationId))
            threads.forEach { runCatching { notifier.cancelForThread(it) } }
        }
        indexSync.requestReconcile()
    }

    fun mmsState(key: MessageKey): Flow<MmsDownloadState> =
        runCatching { mms.state(key) }.getOrDefault(flowOf(MmsDownloadState.Done))

    fun retryMms(key: MessageKey) {
        viewModelScope.launch { runCatching { mms.retry(key) } }
    }

    fun retrySend(key: MessageKey) {
        viewModelScope.launch {
            val result = runCatching { sender.retry(key) }.getOrNull()
            if (result !is SendResult.Queued) eventChannel.trySend(ConversationEvent.RetryFailed)
        }
    }

    fun setMessageStarred(key: MessageKey, starred: Boolean) {
        viewModelScope.launch {
            conversations.setMessageStarred(key, starred)
            if (starred) otpLifecycle.cancel(key) // a starred OTP is never auto-deleted
        }
    }

    /** Copying an OTP keeps it: cancel its pending auto-delete. */
    fun onOtpCopied(key: MessageKey) {
        runCatching { otpLifecycle.cancel(key) }
    }

    fun delete(key: MessageKey) {
        viewModelScope.launch {
            val receipt = bin.moveToBin(listOf(key), DeletedBy.Manual)
            eventChannel.trySend(if (receipt.binIds.isEmpty()) ConversationEvent.DeleteFailed else ConversationEvent.Deleted(receipt))
        }
    }

    fun deleteOtpNow(key: MessageKey) {
        viewModelScope.launch {
            if (!otpLifecycle.deleteNow(key)) eventChannel.trySend(ConversationEvent.DeleteFailed)
        }
    }

    fun undoDelete(receipt: BinReceipt) {
        viewModelScope.launch { bin.undo(receipt) }
    }

    fun removeLabel(key: MessageKey, label: String) {
        viewModelScope.launch { labels.remove(key.toString(), label) }
    }

    fun setPinned(value: Boolean) = launchPrefs { conversations.setPinned(conversationId, value) }
    fun setMuted(value: Boolean) = launchPrefs { conversations.setMuted(conversationId, value) }
    fun setStarred(value: Boolean) = launchPrefs { conversations.setStarred(conversationId, value) }
    fun setBubbleStyle(index: Int?) = launchPrefs { conversations.setBubbleColor(conversationId, index) }
    fun setFontScale(scale: Float?) = launchPrefs { conversations.setFontScale(conversationId, scale) }

    fun setArchived(value: Boolean) = launchPrefs {
        conversations.setArchived(conversationId, value)
        if (value) eventChannel.trySend(ConversationEvent.Archived)
    }

    /** Sets the reply SIM explicitly (thread menu). */
    fun chooseReplySim(subId: Int) {
        composer.subId.value = subId
        launchPrefs { conversations.setReplySim(conversationId, subId) }
    }

    /** Adds every sender of this conversation to the shared system block list. */
    fun block() {
        viewModelScope.launch {
            val addresses = headerState.value.addresses
            val done = addresses.count { runCatching { blocked.block(it) }.getOrDefault(false) }
            eventChannel.trySend(if (done > 0) ConversationEvent.Blocked(done) else ConversationEvent.BlockFailed)
        }
    }

    /**
     * Body for a TRAI 1909 spam report of [message] ("<message>,<sender>,<dd/mm/yy>"), to send from the composer so
     * the user sees exactly what goes out.
     */
    fun spamReportBody(message: MessageItem): String {
        val date = SimpleDateFormat("dd/MM/yy", Locale.US).format(Date(message.dateMillis))
        val text = message.body.replace('\n', ' ').take(REPORT_BODY_CHARS)
        return "$text,${message.address},$date"
    }

    private fun launchPrefs(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

    private suspend fun buildHeader(addresses: List<String>): ConversationHeader = withContext(Dispatchers.IO) {
        // An id from an old notification/search link may have been folded elsewhere since; name the thread it opens.
        val mergeKey = ConversationIds.mergeKeyOf(runCatching { conversations.resolveConversationId(conversationId) }.getOrDefault(conversationId))
        val mergeName = mergeKey?.let { runCatching { merges.group(it).first()?.displayName }.getOrNull() }
        val matches = addresses.map { it to contacts.find(it) }
        val names = matches.map { (address, match) -> match?.displayName ?: address }
        val title = mergeName ?: names.joinToString(", ").ifBlank { mergeKey ?: conversationId }
        val first = addresses.firstOrNull().orEmpty()
        ConversationHeader(
            title = title,
            addresses = addresses,
            isGroup = addresses.size > 1,
            isBusiness = mergeKey != null || (first.isNotEmpty() && first.none { it.isDigit() }),
            photoUri = matches.singleOrNull()?.second?.photoUri,
            names = matches.associate { (address, match) -> address to (match?.displayName ?: address) },
        )
    }

    private companion object {
        const val FORWARD_LOOKBACK = 500
        const val REPORT_BODY_CHARS = 400
    }
}
