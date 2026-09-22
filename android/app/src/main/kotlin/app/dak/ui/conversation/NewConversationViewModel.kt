package app.dak.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.automation.ScheduledSendScheduler
import app.dak.di.AndroidContactLookup
import app.dak.index.enrich.ConversationIds
import app.dak.navigation.PendingShare
import app.dak.navigation.Routes
import app.dak.telephony.ProviderWriter
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** A chosen recipient (raw address as typed or picked, plus a display name when known). */
data class Recipient(val address: String, val name: String?)

/**
 * New message: recipient picker (contacts search plus raw numbers), then the same composer as a thread. Prefilled
 * from the COMPOSE route (`to` = comma-separated addresses, `body`) and from media shared into the app. After the
 * first send it opens the conversation the message landed in.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class NewConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedState: SavedStateHandle,
    private val contactSearch: ContactSearch,
    private val contacts: AndroidContactLookup,
    private val writer: ProviderWriter,
    private val sims: SimRepository,
    private val controller: MessageSendController,
    pendingShare: PendingShare,
    scheduler: ScheduledSendScheduler,
    hints: ComposerHints,
) : ViewModel() {

    private val recipientsState = MutableStateFlow(initialRecipients())
    val recipients: StateFlow<List<Recipient>> = recipientsState.asStateFlow()

    private val queryState = MutableStateFlow(savedState.get<String>(KEY_QUERY).orEmpty())
    val query: StateFlow<String> = queryState.asStateFlow()

    /** The "To" field as Compose snapshot state, updated synchronously so typing never drops characters. */
    var queryText: String by mutableStateOf(queryState.value)
        private set

    val suggestions: StateFlow<List<ContactSuggestion>> = queryState
        .debounce(150)
        .mapLatest { q -> if (q.isBlank()) emptyList() else contactSearch.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val canReadContacts: Boolean get() = contactSearch.hasPermission()

    private val openChannel = Channel<String>(Channel.BUFFERED)
    /** Routes to open once a message was sent (the conversation it landed in). */
    val openRoute: Flow<String> = openChannel.receiveAsFlow()

    private val events = Channel<ComposerEvent>(Channel.BUFFERED)
    val composerEvents: Flow<ComposerEvent> = events.receiveAsFlow()

    val composer = ComposerDelegate(
        scope = viewModelScope,
        controller = controller,
        scheduler = scheduler,
        sims = sims,
        hints = hints,
        savedState = savedState,
        recipients = recipientsState.map { list -> list.map { it.address } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, recipientsState.value.map { it.address }),
        subId = MutableStateFlow(sims.defaultSmsSubId()),
        threadId = { null },
        conversationId = { null },
        onSubIdChosen = { },
        onEvent = { events.trySend(it) },
        onSent = ::openThreadFor,
    )

    init {
        if (savedState.get<Boolean>(KEY_PREFILLED) != true) {
            savedState[KEY_PREFILLED] = true
            savedState.get<String>(Routes.ARG_BODY)?.let { composer.setText(Uri.decode(it)) }
        }
        val shared = pendingShare.consume()
        if (shared.isNotEmpty()) {
            viewModelScope.launch {
                val items = withContext(Dispatchers.IO) {
                    shared.map { uri ->
                        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
                        ComposerAttachment(UUID.randomUUID().toString(), mime, uri.lastPathSegment, uri = uri)
                    }
                }
                composer.addShared(items)
            }
        }
    }

    fun onQueryChange(value: String) {
        queryText = value
        queryState.value = value
        savedState[KEY_QUERY] = value
    }

    fun add(address: String, name: String? = null) {
        val clean = address.trim()
        if (clean.isEmpty() || recipientsState.value.any { it.address == clean }) return
        setRecipients(recipientsState.value + Recipient(clean, name))
        onQueryChange("")
    }

    /** Adds whatever is typed as a raw number (IME action / "Add" row). */
    fun addTyped() {
        val typed = queryState.value.trim()
        if (looksLikeAddress(typed)) add(typed, null)
    }

    fun remove(recipient: Recipient) = setRecipients(recipientsState.value - recipient)

    /** True when [text] can be sent to as-is (a phone number or short code). */
    fun looksLikeAddress(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        return digits >= 3 && text.all { it.isDigit() || it in "+-() ." }
    }

    private fun setRecipients(list: List<Recipient>) {
        recipientsState.value = list
        savedState[KEY_RECIPIENTS] = ArrayList(list.map { it.address })
    }

    private fun initialRecipients(): List<Recipient> {
        savedState.get<ArrayList<String>>(KEY_RECIPIENTS)?.let { saved -> return saved.map { Recipient(it, null) } }
        val to = savedState.get<String>(Routes.ARG_TO)?.let(Uri::decode).orEmpty()
        return to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { Recipient(it, null) }
    }

    private fun openThreadFor(addresses: List<String>) {
        viewModelScope.launch {
            val sub = composer.subId.value
            val normalized = addresses.map { controller.normalized(it, sub) }.toSet()
            val threadId = runCatching { writer.threadIdFor(normalized) }.getOrNull() ?: return@launch
            openChannel.trySend(Routes.conversation(ConversationIds.forThread(threadId)))
        }
    }

    /** Resolves display names of prefilled recipients once contacts are readable. */
    fun resolveNames() {
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                recipientsState.value.map { r -> if (r.name != null) r else r.copy(name = contacts.displayName(r.address)) }
            }
            recipientsState.value = resolved
        }
    }

    private companion object {
        const val KEY_QUERY = "new.query"
        const val KEY_RECIPIENTS = "new.recipients"
        const val KEY_PREFILLED = "new.prefilled"
    }
}
