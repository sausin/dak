package app.dak.ui.broadcast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.automations.broadcast.BroadcastList
import app.dak.automations.broadcast.Member
import app.dak.broadcast.BroadcastDetails
import app.dak.broadcast.BroadcastPreview
import app.dak.broadcast.BroadcastService
import app.dak.broadcast.BroadcastStatusReader
import app.dak.broadcast.BroadcastStore
import app.dak.core.model.SimInfo
import app.dak.navigation.Routes
import app.dak.telephony.ProviderChanges
import app.dak.telephony.SimRepository
import app.dak.ui.conversation.ContactSearch
import app.dak.ui.conversation.ContactSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-off outcomes the screen shows or acts on. */
sealed interface BroadcastEvent {
    data class Queued(val count: Int, val scheduled: Boolean) : BroadcastEvent
    data object Failed : BroadcastEvent
    data class OpenConversation(val conversationId: String) : BroadcastEvent
}

/**
 * One broadcast list (`broadcast/{id}`): its sent broadcasts with live per-recipient ticks and replies (refreshed on
 * provider changes, only while the screen is visible), the composer (text with placeholders, SIM, send now or once
 * at a chosen time), the confirmation preview, and list editing.
 */
@HiltViewModel
class BroadcastListViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val store: BroadcastStore,
    private val service: BroadcastService,
    private val reader: BroadcastStatusReader,
    private val contacts: ContactSearch,
    sims: SimRepository,
    providerChanges: ProviderChanges,
) : ViewModel() {

    val listId: String = savedState.get<String>(Routes.ARG_BROADCAST_ID).orEmpty()

    val list: StateFlow<BroadcastList?> = store.lists
        .map { lists -> lists.firstOrNull { it.id == listId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.list(listId))

    private val changes: Flow<Unit> = providerChanges.changes.onStart { emit(Unit) }

    /**
     * Sent broadcasts of this list, oldest first (a thread); null while the first read runs. The newest [LIVE_COUNT]
     * carry live ticks and replies (re-read on provider changes); older ones show their stored state.
     */
    val history: StateFlow<List<BroadcastDetails>?> = combine(
        store.records.map { all -> all.filter { it.listId == listId }.take(MAX_SHOWN) },
        changes,
    ) { records, _ -> records }
        .conflate()
        .map { records ->
            records.mapIndexed { i, r -> if (i < LIVE_COUNT) reader.details(r) else reader.quick(r) }
                .sortedBy { it.record.createdAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val selectedId = MutableStateFlow<String?>(null)

    /** The broadcast whose detail sheet is open, with live ticks and replies. */
    val selected: StateFlow<BroadcastDetails?> = combine(selectedId, store.records, changes) { id, records, _ ->
        id?.let { wanted -> records.firstOrNull { it.id == wanted } }
    }
        .conflate()
        .map { record -> record?.let { reader.details(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openDetails(recordId: String?) {
        selectedId.value = recordId
    }

    val simList: StateFlow<List<SimInfo>> = sims.sims

    /** Composer text (with placeholders). */
    var text by mutableStateOf("")
        private set

    private val mutableSubId = MutableStateFlow<Int?>(null)
    val subId: StateFlow<Int?> = mutableSubId.asStateFlow()

    private val mutableScheduledAt = MutableStateFlow<Long?>(null)
    val scheduledAt: StateFlow<Long?> = mutableScheduledAt.asStateFlow()

    private val mutablePreview = MutableStateFlow<BroadcastPreview?>(null)
    val preview: StateFlow<BroadcastPreview?> = mutablePreview.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableNeedsConsent = MutableStateFlow(store.needsConsent())
    val needsConsent: StateFlow<Boolean> = mutableNeedsConsent.asStateFlow()

    private val eventChannel = Channel<BroadcastEvent>(Channel.BUFFERED)
    val events: Flow<BroadcastEvent> = eventChannel.receiveAsFlow()

    val canReadContacts: Boolean get() = contacts.hasPermission()

    fun onTextChange(value: String) {
        text = value.take(MAX_TEXT)
    }

    /** Inserts [placeholder] at the end of the text (with a separating space). */
    fun insertPlaceholder(placeholder: String) {
        val t = text
        text = if (t.isEmpty() || t.endsWith(' ') || t.endsWith('\n')) t + placeholder else "$t $placeholder"
    }

    fun selectSim(subId: Int?) {
        mutableSubId.value = subId
    }

    fun schedule(atMillis: Long?) {
        mutableScheduledAt.value = atMillis
    }

    fun acceptTerms() {
        store.acceptTerms()
        mutableNeedsConsent.value = false
    }

    /** Builds the confirmation preview (plan, cost, ETA, spam risk). */
    fun requestSend() {
        val current = list.value ?: store.list(listId) ?: return
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                val built = runCatching { service.preview(current, text, mutableSubId.value, mutableScheduledAt.value) }.getOrNull()
                if (built == null) eventChannel.trySend(BroadcastEvent.Failed) else mutablePreview.value = built
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun dismissPreview() {
        mutablePreview.value = null
    }

    /** Sends the confirmed preview. */
    fun confirmSend() {
        val p = mutablePreview.value ?: return
        mutablePreview.value = null
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                val record = runCatching { service.send(p) }.getOrNull()
                if (record != null) {
                    text = ""
                    mutableScheduledAt.value = null
                    eventChannel.trySend(BroadcastEvent.Queued(record.recipients.size, p.scheduledAtMillis != null))
                } else {
                    eventChannel.trySend(BroadcastEvent.Failed)
                }
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun retry(recordId: String, index: Int) {
        viewModelScope.launch { runCatching { service.retry(recordId, index) } }
    }

    fun cancelUnsent(recordId: String) {
        viewModelScope.launch { runCatching { service.cancelPending(recordId) } }
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            runCatching { service.cancelPending(recordId) }
            if (selectedId.value == recordId) selectedId.value = null
            store.deleteRecord(recordId)
        }
    }

    fun openThread(threadId: Long) {
        viewModelScope.launch { eventChannel.trySend(BroadcastEvent.OpenConversation(reader.conversationIdFor(threadId))) }
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> = contacts.search(query)

    fun saveList(name: String, members: List<Member>) {
        val current = list.value ?: store.list(listId) ?: return
        store.putList(current.copy(name = name, members = members))
    }

    fun deleteList() {
        store.deleteList(listId)
    }

    private companion object {
        const val MAX_SHOWN = 30
        const val LIVE_COUNT = 5
        const val MAX_TEXT = 1_600
    }
}
