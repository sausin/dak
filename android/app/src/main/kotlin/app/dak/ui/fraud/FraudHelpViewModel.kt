package app.dak.ui.fraud

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.navigation.Routes
import app.dak.safety.FraudReport
import app.dak.safety.helplines.Helpline
import app.dak.safety.helplines.HelplineAction
import app.dak.safety.helplines.HelplineCategory
import app.dak.safety.helplines.HelplineRepository
import app.dak.safety.helplines.RegionalHelplines
import app.dak.safety.helplines.UserHelpline
import app.dak.safety.helplines.UserHelplines
import app.dak.telephony.BlockedNumbers
import app.dak.telephony.ProviderReader
import app.dak.telephony.SimRepository
import app.dak.telephony.region.EmergencyNumbers
import app.dak.telephony.region.RegionProfile
import app.dak.telephony.region.RegionProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The message being reported (from the `message` route argument). */
data class ReportedMessage(
    val key: MessageKey,
    val sender: String,
    val body: String,
    val dateMillis: Long,
    val subId: Int,
    val isIncoming: Boolean,
)

/** Everything the Report fraud screen shows. */
data class FraudHelpUi(
    val loading: Boolean = true,
    /** Official helplines from the bundle for [countryIso], in bundle order (India's today; none elsewhere). */
    val helplines: List<Helpline> = emptyList(),
    /** ISO country of the region profile (the reported message's SIM, else the default SMS SIM); null if unknown. */
    val countryIso: String? = null,
    /**
     * General emergency numbers (from libphonenumber's data) when the bundle has none for this country. Shown as
     * emergency numbers only, never as fraud lines.
     */
    val emergencyNumbers: List<String> = emptyList(),
    /** Numbers the user added (e.g. their bank's card-block line); always unverified. */
    val userHelplines: List<UserHelpline> = emptyList(),
    val message: ReportedMessage? = null,
    /** True when a `message` argument was given but the message no longer exists. */
    val messageMissing: Boolean = false,
    val senderBlocked: Boolean = false,
) {
    /** The "call now if you lost money" helpline (1930). */
    val urgent: Helpline? get() = helplines.firstOrNull { it.category == HelplineCategory.CYBERCRIME && it.action == HelplineAction.CALL }
    val traiSms: Helpline? get() = helplines.firstOrNull { it.category == HelplineCategory.SPAM && it.action == HelplineAction.SMS }
    val chakshu: Helpline? get() = helplines.firstOrNull { it.category == HelplineCategory.FRAUD_COMMUNICATION && it.action == HelplineAction.URL }
    val cyberPortal: Helpline? get() = helplines.firstOrNull { it.category == HelplineCategory.CYBERCRIME && it.action == HelplineAction.URL }

    /** TRAI's 1909 complaint applies: India only. */
    val reportsToTrai: Boolean get() = countryIso == RegionProfile.INDIA
}

/** One-off outcomes shown as snackbars. */
enum class FraudHelpEvent { BLOCKED, BLOCK_FAILED, HELPLINE_ADDED, HELPLINE_INVALID }

/**
 * Report fraud: official helplines (bundled, signature-verifiable for OTA refresh), user-added bank numbers, and —
 * when opened with a `message` key — the report flows for that message (block sender, copy details, and in India the
 * TRAI 1909 complaint and Chakshu / cybercrime.gov.in). Helplines follow the region profile; countries without
 * verified entries get their general emergency numbers and the user's own bank number only.
 */
@HiltViewModel
class FraudHelpViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: HelplineRepository,
    private val userHelplines: UserHelplines,
    private val reader: ProviderReader,
    private val blocked: BlockedNumbers,
    private val sims: SimRepository,
    private val regions: RegionProvider,
) : ViewModel() {

    private val messageKey: MessageKey? = savedState.get<String>(Routes.ARG_MESSAGE)
        ?.let { raw -> MessageKey.parse(raw) ?: MessageKey.parse(Uri.decode(raw)) }

    private val base = MutableStateFlow(FraudHelpUi())

    val ui: StateFlow<FraudHelpUi> = combine(base, userHelplines.all) { state, mine -> state.copy(userHelplines = mine) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FraudHelpUi())

    private val eventChannel = Channel<FraudHelpEvent>(Channel.BUFFERED)
    val events: Flow<FraudHelpEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val bundle = runCatching { repository.current() }.getOrNull()
            val message = messageKey?.let { key -> runCatching { reader.message(key) }.getOrNull() }
            val reported = message?.let {
                ReportedMessage(it.key, it.address, it.body, it.dateMillis, it.subId, isIncoming = it.box == MessageBox.INBOX)
            }
            val isBlocked = reported?.let { r -> runCatching { blocked.isBlocked(r.sender) }.getOrDefault(false) } ?: false
            // Helplines follow the region of the reported message's SIM (else the default SMS SIM's), never a fixed one.
            val country = runCatching { (reported?.let { regions.forSubId(it.subId) } ?: regions.current()).countryIso }.getOrNull()
            val regional = bundle?.let { RegionalHelplines.forCountry(it, country) }.orEmpty()
            val emergency = if (RegionalHelplines.hasEmergency(regional)) {
                emptyList()
            } else {
                // libphonenumber loads its short-number metadata on first use: keep that off the main thread.
                withContext(Dispatchers.Default) { EmergencyNumbers.forRegion(country) }
            }
            base.value = base.value.copy(
                loading = false,
                helplines = regional,
                countryIso = country,
                emergencyNumbers = emergency,
                message = reported,
                messageMissing = messageKey != null && reported == null,
                senderBlocked = isBlocked,
            )
        }
    }

    /** Compose route for the TRAI 1909 complaint about the reported message (the user reviews it before sending). */
    fun traiComplaintRoute(): String? {
        val state = base.value
        if (!state.reportsToTrai) return null
        val message = state.message ?: return null
        val number = state.traiSms?.target ?: TRAI_FALLBACK_NUMBER
        val body = FraudReport.traiComplaintBody(message.body, message.sender, message.dateMillis, state.traiSms?.smsFormat)
        return Routes.compose(to = number, body = body)
    }

    /** Plain-text details of the reported message, for pasting into Chakshu / cybercrime.gov.in. */
    fun details(labels: FraudReport.DetailLabels, simLabel: (Int) -> String?): String? {
        val message = base.value.message ?: return null
        return FraudReport.detailsText(message.body, message.sender, message.dateMillis, simLabel(message.subId), labels)
    }

    /** SIM slot (0-based) the reported message arrived on, or -1. */
    fun slotOf(subId: Int): Int = sims.sim(subId)?.slotIndex ?: -1

    /** Adds the reported message's sender to the shared system block list. */
    fun blockSender() {
        val sender = base.value.message?.sender ?: return
        viewModelScope.launch {
            val ok = sender.split(' ').filter { it.isNotBlank() }.map { runCatching { blocked.block(it) }.getOrDefault(false) }.any { it }
            if (ok) base.value = base.value.copy(senderBlocked = true)
            eventChannel.trySend(if (ok) FraudHelpEvent.BLOCKED else FraudHelpEvent.BLOCK_FAILED)
        }
    }

    fun addUserHelpline(label: String, number: String) {
        eventChannel.trySend(if (userHelplines.add(label, number)) FraudHelpEvent.HELPLINE_ADDED else FraudHelpEvent.HELPLINE_INVALID)
    }

    fun removeUserHelpline(id: String) = userHelplines.remove(id)

    private companion object {
        /** Used only if the bundle is unreadable; TRAI's spam-complaint short code. */
        const val TRAI_FALLBACK_NUMBER = "1909"
    }
}
