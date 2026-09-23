package app.dak.ui.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.dak.automation.AutomationUndo
import app.dak.automation.AutomationUndoCenter
import app.dak.core.model.MessageKey
import app.dak.core.model.SimInfo
import app.dak.index.BackfillProgress
import app.dak.index.ConversationSummary
import app.dak.index.InboxTab
import app.dak.index.bin.BinReceipt
import app.dak.index.bin.DeletedBy
import app.dak.index.bin.RecycleBin
import app.dak.index.repo.ConversationRepository
import app.dak.index.repo.FoldReceipt
import app.dak.index.repo.SavedSearchItem
import app.dak.index.repo.SavedSearchRepository
import app.dak.index.repo.SenderMergeRepository
import app.dak.index.sync.IndexMaintenance
import app.dak.telephony.ProviderReader
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Snackbar-worthy results of inbox actions. */
sealed interface InboxEvent {
    data class Archived(val conversationId: String) : InboxEvent
    data class Deleted(val receipt: BinReceipt) : InboxEvent
    data object DeleteFailed : InboxEvent
    /** An automation archived or deleted something moments ago; offer undo. */
    data class Automation(val undo: AutomationUndo) : InboxEvent
    /** Conversations were folded together; offer undo. */
    data class Folded(val receipt: FoldReceipt) : InboxEvent
    data object FoldFailed : InboxEvent
}

/**
 * Inbox: tab and SIM filter (kept in [SavedStateHandle]), the paged conversation list, index progress, pinned
 * saved searches as virtual folders, and swipe actions (archive; delete → recycle bin) with undo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val conversations: ConversationRepository,
    private val reader: ProviderReader,
    private val bin: RecycleBin,
    private val undoCenter: AutomationUndoCenter,
    private val folds: SenderMergeRepository,
    sims: SimRepository,
    maintenance: IndexMaintenance,
    savedSearches: SavedSearchRepository,
) : ViewModel() {

    val tab: StateFlow<InboxTab> = savedState.getStateFlow(KEY_TAB, InboxTab.ALL.name)
        .map { name -> InboxTab.entries.firstOrNull { it.name == name } ?: InboxTab.ALL }
        .stateIn(viewModelScope, SharingStarted.Eagerly, InboxTab.ALL)

    /** Selected SIM (sub id), or null for all SIMs. */
    val simFilter: StateFlow<Int?> = savedState.getStateFlow(KEY_SIM, NO_FILTER)
        .map { if (it == NO_FILTER) null else it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversationsPaged: Flow<PagingData<ConversationSummary>> =
        combine(tab, simFilter) { t, s -> t to s }
            .flatMapLatest { (t, s) -> conversations.conversations(t, s) }
            .cachedIn(viewModelScope)

    val sims: StateFlow<List<SimInfo>> = sims.sims

    val progress: StateFlow<BackfillProgress?> = maintenance.progress
        .map<BackfillProgress, BackfillProgress?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pinnedSearches: StateFlow<List<SavedSearchItem>> = savedSearches.pinned()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val eventChannel = Channel<InboxEvent>(Channel.BUFFERED)
    val events: Flow<InboxEvent> = merge(eventChannel.receiveAsFlow(), undoCenter.events.map { InboxEvent.Automation(it) })

    fun selectTab(tab: InboxTab) {
        savedState[KEY_TAB] = tab.name
    }

    fun selectSim(subId: Int?) {
        savedState[KEY_SIM] = subId ?: NO_FILTER
    }

    fun archive(conversation: ConversationSummary) {
        viewModelScope.launch {
            conversations.setArchived(conversation.conversationId, !conversation.archived)
            if (!conversation.archived) eventChannel.trySend(InboxEvent.Archived(conversation.conversationId))
        }
    }

    fun unarchive(conversationId: String) {
        viewModelScope.launch { conversations.setArchived(conversationId, false) }
    }

    /** Moves every message of the conversation to the recycle bin (copy first, then provider delete). */
    fun delete(conversation: ConversationSummary) {
        viewModelScope.launch {
            val keys = keysOf(conversation)
            if (keys.isEmpty()) {
                eventChannel.trySend(InboxEvent.DeleteFailed)
                return@launch
            }
            val receipt = bin.moveToBin(keys, DeletedBy.Manual)
            eventChannel.trySend(if (receipt.binIds.isEmpty()) InboxEvent.DeleteFailed else InboxEvent.Deleted(receipt))
        }
    }

    fun undoDelete(receipt: BinReceipt) {
        viewModelScope.launch { bin.undo(receipt) }
    }

    fun undoAutomation(undo: AutomationUndo) {
        viewModelScope.launch { undoCenter.undo(undo) }
    }

    fun togglePinned(conversation: ConversationSummary) {
        viewModelScope.launch { conversations.setPinned(conversation.conversationId, !conversation.pinned) }
    }

    fun toggleMuted(conversation: ConversationSummary) {
        viewModelScope.launch { conversations.setMuted(conversation.conversationId, !conversation.muted) }
    }

    fun markRead(conversation: ConversationSummary) {
        viewModelScope.launch { conversations.markRead(conversation.conversationId) }
    }

    /** Folds the selected conversations into one (display only; the provider keeps its threads). */
    fun foldTogether(conversationIds: List<String>, name: String?) {
        if (conversationIds.size < 2) return
        viewModelScope.launch {
            val receipt = runCatching { folds.foldTogether(conversationIds, name) }.getOrNull()
            eventChannel.trySend(if (receipt == null) InboxEvent.FoldFailed else InboxEvent.Folded(receipt))
        }
    }

    fun undoFold(receipt: FoldReceipt) {
        viewModelScope.launch { runCatching { folds.undo(receipt) } }
    }

    private suspend fun keysOf(conversation: ConversationSummary): List<MessageKey> =
        conversation.threadIds.flatMap { threadId ->
            runCatching { reader.messagesInThread(threadId) }.getOrDefault(emptyList()).map { it.key }
        }

    private companion object {
        const val KEY_TAB = "inbox.tab"
        const val KEY_SIM = "inbox.sim"
        const val NO_FILTER = Int.MIN_VALUE
    }
}
