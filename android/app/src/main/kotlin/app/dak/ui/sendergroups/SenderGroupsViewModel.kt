package app.dak.ui.sendergroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.core.model.SimInfo
import app.dak.di.AndroidContactLookup
import app.dak.index.enrich.FoldProposal
import app.dak.index.repo.FoldGroup
import app.dak.index.repo.FoldReceipt
import app.dak.index.repo.SenderMergeRepository
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Snackbar events of fold edits, each with its undo token. */
sealed interface FoldEvent {
    data class Folded(val receipt: FoldReceipt) : FoldEvent
    data class Unfolded(val receipt: FoldReceipt) : FoldEvent
    data object Failed : FoldEvent
}

/**
 * Sender groups screen: folded conversations with their channels (header/number, last seen, SIM), fold
 * suggestions, and the edits (rename, unfold a channel, dissolve, accept/dismiss a suggestion), each undoable.
 */
@HiltViewModel
class SenderGroupsViewModel @Inject constructor(
    private val repository: SenderMergeRepository,
    contacts: AndroidContactLookup,
    sims: SimRepository,
) : ViewModel() {

    /** Null while loading. */
    val groups: StateFlow<List<FoldGroup>?> = repository.foldGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val suggestions: StateFlow<List<FoldProposal>> = repository
        .foldSuggestions { _, address -> runCatching { contacts.displayName(address) }.getOrNull() ?: address }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sims: StateFlow<List<SimInfo>> = sims.sims

    private val eventChannel = Channel<FoldEvent>(Channel.BUFFERED)
    val events: Flow<FoldEvent> = eventChannel.receiveAsFlow()

    fun rename(group: FoldGroup, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { runCatching { repository.rename(group.groupKey, name) } }
    }

    fun unfold(channel: String) = edit(unfold = true) { repository.unfoldChannel(channel) }

    fun dissolve(group: FoldGroup) = edit(unfold = true) { repository.dissolve(group.conversationId) }

    fun accept(proposal: FoldProposal) = edit(unfold = false) { repository.foldTogether(proposal.conversationIds, proposal.title) }

    fun dismiss(proposal: FoldProposal) {
        viewModelScope.launch { runCatching { repository.dismissSuggestion(proposal) } }
    }

    fun undo(receipt: FoldReceipt) {
        viewModelScope.launch { runCatching { repository.undo(receipt) } }
    }

    private fun edit(unfold: Boolean, block: suspend () -> FoldReceipt) {
        viewModelScope.launch {
            val receipt = runCatching { block() }.getOrNull()
            eventChannel.trySend(
                when {
                    receipt == null -> FoldEvent.Failed
                    unfold -> FoldEvent.Unfolded(receipt)
                    else -> FoldEvent.Folded(receipt)
                },
            )
        }
    }
}
