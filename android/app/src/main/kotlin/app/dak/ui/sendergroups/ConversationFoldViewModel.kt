package app.dak.ui.sendergroups

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.dak.core.model.MessageKey
import app.dak.di.AndroidContactLookup
import app.dak.index.MessageItem
import app.dak.index.repo.ConversationRepository
import app.dak.index.repo.FoldChannel
import app.dak.index.repo.FoldReceipt
import app.dak.index.repo.FoldTarget
import app.dak.index.repo.SenderMergeRepository
import app.dak.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Results of fold edits made from a conversation. */
sealed interface ConversationFoldEvent {
    /** This conversation was folded into another one: open [conversationId] instead. */
    data class Moved(val conversationId: String, val receipt: FoldReceipt) : ConversationFoldEvent
    data class Unfolded(val receipt: FoldReceipt) : ConversationFoldEvent
    data object Failed : ConversationFoldEvent
}

/**
 * Fold affordances of the thread view, kept apart from `ConversationViewModel`: the conversation's sender
 * channels (for per-bubble channel chips and the channel filter), "Fold into…", "Unfold a sender", and the copies
 * behind a collapsed repeated message. Shares the thread's navigation entry, so it reads the same
 * `conversationId` argument (resolving ids whose messages moved after a fold).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationFoldViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val conversations: ConversationRepository,
    private val folds: SenderMergeRepository,
    private val contacts: AndroidContactLookup,
) : ViewModel() {

    private val requestedId: String = Uri.decode(savedState.get<String>(Routes.ARG_CONVERSATION_ID).orEmpty())

    private val resolvedId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { resolvedId.value = runCatching { conversations.resolveConversationId(requestedId) }.getOrDefault(requestedId) }
    }

    /** Sender channels of this conversation, most recent first (more than one only for a folded conversation). */
    val channels: StateFlow<List<FoldChannel>> = resolvedId.filterNotNull()
        .flatMapLatest { folds.channelsOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filter = MutableStateFlow<String?>(null)

    /** Channel the thread is filtered to, or null for all. */
    val channelFilter: StateFlow<String?> = filter

    /** Messages of the selected channel only (collect only while [channelFilter] is set). */
    val filteredMessages: Flow<PagingData<MessageItem>> =
        combine(resolvedId.filterNotNull(), filter) { id, channel -> id to channel }
            .flatMapLatest { (id, channel) -> if (channel == null) emptyFlow() else conversations.messages(id, channel = channel) }
            .cachedIn(viewModelScope)

    /** Conversations this one can be folded into (itself excluded), contact names resolved. */
    val foldTargets: StateFlow<List<FoldTarget>> =
        combine(folds.foldTargets(), resolvedId) { targets, self ->
            targets.filter { it.conversationId != self && it.conversationId != requestedId }
                .map { t -> if (t.title == t.address) t.copy(title = runCatching { contacts.displayName(t.address) }.getOrNull() ?: t.title) else t }
        }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val eventChannel = Channel<ConversationFoldEvent>(Channel.BUFFERED)
    val events: Flow<ConversationFoldEvent> = eventChannel.receiveAsFlow()

    fun selectChannel(channel: String?) {
        filter.value = channel
    }

    fun foldInto(target: FoldTarget) {
        viewModelScope.launch {
            val receipt = runCatching { folds.foldInto(currentId(), target.conversationId) }.getOrNull()
            eventChannel.trySend(if (receipt == null) ConversationFoldEvent.Failed else ConversationFoldEvent.Moved(receipt.conversationId, receipt))
        }
    }

    fun unfold(channel: String) {
        viewModelScope.launch {
            val receipt = runCatching { folds.unfoldChannel(channel) }.getOrNull()
            if (filter.value == channel) filter.value = null
            eventChannel.trySend(if (receipt == null) ConversationFoldEvent.Failed else ConversationFoldEvent.Unfolded(receipt))
        }
    }

    fun undo(receipt: FoldReceipt) {
        viewModelScope.launch { runCatching { folds.undo(receipt) } }
    }

    /** Every copy of a collapsed repeated message, newest first (empty when it is not repeated). */
    suspend fun repeatsOf(key: MessageKey): List<MessageItem> =
        runCatching { conversations.repeatsOf(key) }.getOrDefault(emptyList())

    private suspend fun currentId(): String = resolvedId.value ?: conversations.resolveConversationId(requestedId)
}
