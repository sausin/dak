package app.dak.ui.broadcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.automations.broadcast.BroadcastList
import app.dak.automations.broadcast.Member
import app.dak.broadcast.BroadcastStore
import app.dak.ui.conversation.ContactSearch
import app.dak.ui.conversation.ContactSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import javax.inject.Inject

/** One row of the lists screen. */
data class BroadcastListRow(val list: BroadcastList, val lastSentMillis: Long?)

/**
 * The broadcast lists screen: lists with their last send, the list editor, and the one-time terms acceptance
 * ([needsConsent] until the current terms version is accepted).
 */
@HiltViewModel
class BroadcastsViewModel @Inject constructor(
    private val store: BroadcastStore,
    private val contacts: ContactSearch,
) : ViewModel() {

    val rows: StateFlow<List<BroadcastListRow>?> = combine(store.lists, store.records) { lists, records ->
        val last = records.groupBy { it.listId }.mapValues { (_, r) -> r.maxOf { it.createdAt } }
        lists.map { BroadcastListRow(it, last[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val mutableNeedsConsent = MutableStateFlow(store.needsConsent())
    val needsConsent: StateFlow<Boolean> = mutableNeedsConsent.asStateFlow()

    val canReadContacts: Boolean get() = contacts.hasPermission()

    fun acceptTerms() {
        store.acceptTerms()
        mutableNeedsConsent.value = false
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> = contacts.search(query)

    /** Saves a new list (or [existing] edited); returns its id. */
    fun save(existing: BroadcastList?, name: String, members: List<Member>): String {
        val list = existing?.copy(name = name, members = members)
            ?: BroadcastList(UUID.randomUUID().toString(), name, members, System.currentTimeMillis())
        store.putList(list)
        return list.id
    }

    fun delete(id: String) = store.deleteList(id)
}
