package app.dak.ui.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.notifications.ChannelCatalog
import app.dak.notifications.ChannelState
import app.dak.notifications.ConversationChannels
import app.dak.notifications.NotificationChannels
import app.dak.notifications.ResetResult
import app.dak.notifications.SimChannelIds
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A titled block of channels (one system channel group). */
data class ChannelSection(val groupId: String, val title: String?, val channels: List<ChannelState>)

data class ChannelsUi(
    val loading: Boolean = true,
    val appBlocked: Boolean = false,
    val sections: List<ChannelSection> = emptyList(),
    val conversations: List<ConversationChannels.Custom> = emptyList(),
    /** Critical channels (Messages / OTP codes / Alerts, any SIM) that are blocked. */
    val blockedCritical: List<ChannelState> = emptyList(),
)

/** Reads the channel state from the system on every resume (the user may have changed it in system settings). */
@HiltViewModel
class NotificationChannelsViewModel @Inject constructor(
    private val channels: NotificationChannels,
    private val conversationChannels: ConversationChannels,
    private val sims: SimRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val state = MutableStateFlow(ChannelsUi())
    val ui: StateFlow<ChannelsUi> = state

    private val resets = Channel<ResetResult>(Channel.BUFFERED)
    val resetResults: Flow<ResetResult> = resets.receiveAsFlow()

    fun refresh() {
        viewModelScope.launch {
            state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    fun removeConversation(conversationId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { conversationChannels.remove(conversationId) }
            refresh()
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { channels.resetAll(sims.sims.value) }
            resets.trySend(result)
            refresh()
        }
    }

    private fun catalogIndex(channelId: String): Int {
        val spec = ChannelCatalog.specOf(channelId) ?: return Int.MAX_VALUE
        return ChannelCatalog.all.indexOf(spec)
    }

    private fun load(): ChannelsUi {
        channels.ensureSimChannels(sims.sims.value)
        val all = channels.states()
        val categories = all.filter { it.conversationId == null }
        val order = listOf(NotificationChannels.GROUP_MESSAGES) +
            categories.mapNotNull { it.simSlot }.distinct().sorted().map { SimChannelIds.groupId(it) } +
            NotificationChannels.GROUP_APP
        val byGroup = categories.groupBy { it.groupId.orEmpty() }
        val sections = (order + (byGroup.keys - order.toSet()).sorted()).mapNotNull { groupId ->
            val list = byGroup[groupId]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ChannelSection(groupId, list.first().groupName, list.sortedBy { catalogIndex(it.id) })
        }
        return ChannelsUi(
            loading = false,
            appBlocked = !NotificationManagerCompat.from(context).areNotificationsEnabled(),
            sections = sections,
            conversations = conversationChannels.list(),
            blockedCritical = categories.filter { it.critical && it.blocked },
        )
    }
}
