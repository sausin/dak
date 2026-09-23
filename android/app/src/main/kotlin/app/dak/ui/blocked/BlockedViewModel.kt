package app.dak.ui.blocked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.telephony.BlockedNumbers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Outcome messages for the blocked-numbers screen. */
enum class BlockedEvent { BLOCKED, UNBLOCKED, FAILED }

/**
 * The shared system block list (`BlockedNumberContract`): blocks made here also stop calls, and blocks made in the
 * Phone app apply to Dak. Writable only while Dak is the default SMS app.
 */
@HiltViewModel
class BlockedViewModel @Inject constructor(private val blocked: BlockedNumbers) : ViewModel() {

    private val state = MutableStateFlow<List<String>?>(null)
    val numbers: StateFlow<List<String>?> = state.asStateFlow()

    private val eventChannel = Channel<BlockedEvent>(Channel.BUFFERED)
    val events: Flow<BlockedEvent> = eventChannel.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { state.value = runCatching { blocked.list() }.getOrDefault(emptyList()).sorted() }
    }

    fun block(address: String) {
        val clean = address.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val ok = runCatching { blocked.block(clean) }.getOrDefault(false)
            eventChannel.trySend(if (ok) BlockedEvent.BLOCKED else BlockedEvent.FAILED)
            refresh()
        }
    }

    fun unblock(address: String) {
        viewModelScope.launch {
            val ok = runCatching { blocked.unblock(address) }.getOrDefault(false)
            eventChannel.trySend(if (ok) BlockedEvent.UNBLOCKED else BlockedEvent.FAILED)
            refresh()
        }
    }
}
