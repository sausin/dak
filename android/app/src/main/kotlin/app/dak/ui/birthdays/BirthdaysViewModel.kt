package app.dak.ui.birthdays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.automation.OutboundAutomationGuard
import app.dak.automations.birthdays.BirthdayDates
import app.dak.automations.birthdays.OccasionKind
import app.dak.birthdays.BirthdayScheduler
import app.dak.birthdays.BirthdaySettings
import app.dak.birthdays.BirthdayState
import app.dak.birthdays.BirthdayStore
import app.dak.birthdays.ContactOccasion
import app.dak.birthdays.ContactOccasionReader
import app.dak.birthdays.OccasionConfig
import app.dak.birthdays.WishMode
import app.dak.core.model.SimInfo
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One upcoming birthday / anniversary / other-date row. */
data class UpcomingOccasion(
    val occasion: ContactOccasion,
    val config: OccasionConfig?,
    val daysUntil: Int,
    val date: LocalDate,
    /** Age reached (birthdays with a known year only). */
    val age: Int?,
    /** The number the wish goes to: the chosen one, else the contact's primary/first. */
    val number: String?,
    val preview: String,
) {
    val enabled: Boolean get() = config?.enabled == true
}

/** Screen state: null [upcoming] while the first contacts read runs. */
data class BirthdaysUiState(
    val hasPermission: Boolean = false,
    val settings: BirthdaySettings = BirthdaySettings(),
    val upcoming: List<UpcomingOccasion>? = null,
)

/**
 * Birthday wishes: re-scans contacts when the screen opens (no contacts observer), shows the next 60 days, and
 * turns per-contact "auto-send wish" on/off. Every change reconciles the scheduled sends ([BirthdayScheduler]).
 */
@HiltViewModel
class BirthdaysViewModel @Inject constructor(
    private val store: BirthdayStore,
    private val reader: ContactOccasionReader,
    private val scheduler: BirthdayScheduler,
    private val outboundGuard: OutboundAutomationGuard,
    sims: SimRepository,
) : ViewModel() {

    private val occasions = MutableStateFlow<List<ContactOccasion>?>(null)
    private val permission = MutableStateFlow(reader.hasPermission())

    val simList: StateFlow<List<SimInfo>> = sims.sims

    val state: StateFlow<BirthdaysUiState> = combine(occasions, store.state, permission) { list, st, granted ->
        BirthdaysUiState(hasPermission = granted, settings = st.settings, upcoming = list?.let { upcoming(it, st) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BirthdaysUiState(hasPermission = reader.hasPermission()))

    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

    init {
        refresh()
    }

    /** Re-checks the permission and re-reads contacts (also reconciling scheduled wishes). */
    fun refresh() {
        permission.value = reader.hasPermission()
        viewModelScope.launch {
            mutableRefreshing.value = true
            try {
                occasions.value = if (permission.value) scheduler.syncFromContacts() else emptyList()
            } finally {
                mutableRefreshing.value = false
            }
        }
    }

    fun setEnabled(enabled: Boolean) = updateSettings { it.copy(enabled = enabled) }
    /**
     * Sets "Ask me first" or "Send automatically"; false (nothing changed) when choosing automatic sending while no
     * app lock is set up: automatic wishes are unattended sends, like auto-forwarding ([OutboundAutomationGuard]).
     */
    fun setMode(mode: WishMode): Boolean {
        if (mode == WishMode.AUTO && !outboundGuard.securityReady()) return false
        updateSettings { it.copy(mode = mode.name) }
        return true
    }
    fun setTime(hour: Int, minute: Int) = updateSettings { it.copy(hour = hour, minute = minute) }
    fun setSim(subId: Int?) = updateSettings { it.copy(subId = subId) }
    fun setIncludeAnniversaries(include: Boolean) = updateSettings { it.copy(includeAnniversaries = include) }
    fun setIncludeOtherDates(include: Boolean) = updateSettings { it.copy(includeOtherDates = include) }

    fun setDefaultTemplate(kind: OccasionKind, text: String) = updateSettings {
        when (kind) {
            OccasionKind.BIRTHDAY -> it.copy(birthdayTemplate = text)
            OccasionKind.ANNIVERSARY -> it.copy(anniversaryTemplate = text)
            OccasionKind.OTHER -> it.copy(otherTemplate = text)
        }
    }

    /** Turns "auto-send wish" on/off for one contact occasion. */
    fun setContactEnabled(item: UpcomingOccasion, enabled: Boolean) = updateConfig(item) { it.copy(enabled = enabled) }

    fun setContactNumber(item: UpcomingOccasion, number: String) = updateConfig(item) { it.copy(number = number) }

    /** Per-contact template; blank or null returns to the default. */
    fun setContactTemplate(item: UpcomingOccasion, text: String?) = updateConfig(item) { it.copy(template = text?.takeIf { t -> t.isNotBlank() }) }

    private fun updateSettings(change: (BirthdaySettings) -> BirthdaySettings) {
        store.updateSettings(change)
        reconcile()
    }

    private fun updateConfig(item: UpcomingOccasion, change: (OccasionConfig) -> OccasionConfig) {
        val o = item.occasion
        store.updateConfig(
            contactId = o.contactId,
            kind = o.kind,
            initial = {
                OccasionConfig(
                    contactId = o.contactId,
                    kind = o.kind.name,
                    number = item.number,
                    name = o.name,
                    firstName = o.firstName,
                    month = o.date.month,
                    day = o.date.day,
                    year = o.date.year,
                    label = o.label,
                )
            },
            change = change,
        )
        reconcile()
    }

    private fun reconcile() {
        viewModelScope.launch { scheduler.reconcile(occasions.value) }
    }

    private fun upcoming(list: List<ContactOccasion>, st: BirthdayState): List<UpcomingOccasion> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return list.asSequence()
            .filter { st.settings.includes(it.kind) }
            .map { o ->
                val config = st.configs[OccasionConfig.keyOf(o.contactId, o.kind)]
                val days = BirthdayDates.daysUntil(o.date, today)
                val date = today.plusDays(days.toLong())
                val age = if (o.kind == OccasionKind.BIRTHDAY) o.date.ageIn(date.year) else null
                val number = config?.number ?: o.numbers.firstOrNull()?.number
                val template = config?.template ?: st.settings.templateFor(o.kind)
                UpcomingOccasion(o, config, days, date, age, number, store.renderWish(template, o.name, o.firstName, age, occasion = o.label))
            }
            .filter { it.daysUntil <= WINDOW_DAYS || it.enabled }
            .sortedWith(compareBy({ it.daysUntil }, { it.occasion.name }))
            .toList()
    }

    private companion object {
        const val WINDOW_DAYS = 60
    }
}
