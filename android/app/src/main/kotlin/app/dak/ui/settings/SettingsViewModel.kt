package app.dak.ui.settings

import android.content.Context
import android.content.res.Resources
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.di.IndexControl
import app.dak.i18n.AppLocales
import app.dak.navigation.Routes
import app.dak.premium.Entitlements
import app.dak.premium.consent.ConsentLedger
import app.dak.premium.consent.DataFlow
import app.dak.settings.AppSettingsStore
import app.dak.settings.AppearanceSettings
import app.dak.settings.DakSettings
import app.dak.settings.DeviceContext
import app.dak.settings.DeviceContextProvider
import app.dak.settings.SettingDef
import app.dak.settings.SettingsGroup
import app.dak.settings.SettingsSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives both the Settings root (search + section overview) and a single section screen. Everything is derived
 * from the registry records, the stored values and [Entitlements], so a purchase unlocks rows live.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: AppSettingsStore,
    private val entitlements: Entitlements,
    private val deviceContext: DeviceContextProvider,
    private val indexControl: IndexControl,
    private val consents: ConsentLedger,
    @ApplicationContext private val appContext: Context,
) : ViewModel(), SettingsCommands {

    /**
     * A data flow whose prominent disclosure must be shown before a row can be turned on (Play User Data policy).
     * Null when nothing is pending; the UI shows [app.dak.ui.privacy.DisclosureDialog] while set.
     */
    private val pendingDisclosureState = MutableStateFlow<DataFlow?>(null)
    val pendingDisclosure: StateFlow<DataFlow?> = pendingDisclosureState.asStateFlow()

    init {
        // A restored or imported settings file can carry "Jev on", but never the consent: keep the row honest.
        if (store.get(DakSettings.jevOptIn) && !consents.isGranted(DataFlow.CLOUD_CLASSIFICATION)) {
            store.set(DakSettings.jevOptIn, false)
        }
    }

    /** Section shown by SettingsGroupScreen (null on the root screen). */
    val sectionId: String? = savedStateHandle.get<String>(Routes.ARG_GROUP)

    /** Deep-linked setting key to scroll to and highlight. */
    val focusKey: String? = savedStateHandle.get<String>(Routes.ARG_FOCUS)

    private val query = MutableStateFlow("")

    /**
     * Labels in the app language. Starts from the application's resources; the screens hand over their own
     * (activity) resources whenever the configuration changes, so a language switch relabels a retained ViewModel.
     */
    private val labels = MutableStateFlow<Pair<String, SettingsLabels>>(labelsFor(appContext.resources))

    val state: StateFlow<SettingsUiState> = combine(
        store.snapshot,
        entitlements.granted,
        deviceContext.updates,
        query,
        labels,
    ) { raw, _, device, q, (_, text) -> build(raw, device, q, text) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Called by the screens with their current resources (see [labels]); a no-op unless the locale changed. */
    fun useResources(resources: Resources) {
        val next = labelsFor(resources)
        if (next.first != labels.value.first) labels.value = next
    }

    private fun labelsFor(resources: Resources): Pair<String, SettingsLabels> =
        resources.configuration.locales.toLanguageTags() to ResourceSettingsLabels(resources)

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun setRaw(def: SettingDef<*>, raw: String) {
        if (def.isLocked(entitlements)) return
        val flow = consentFlowFor(def)
        if (flow != null) {
            val on = raw.toBooleanStrictOrNull() ?: return
            if (on && !consents.isGranted(flow)) {
                // Turning it on waits for an explicit "Allow" on the disclosure; see acceptDisclosure.
                pendingDisclosureState.value = flow
                return
            }
            if (!on) consents.withdraw(flow, SOURCE)
        }
        store.setRaw(def, raw)
    }

    /** The user tapped "Allow" on the disclosure: record the consent, then turn the row on. */
    fun acceptDisclosure(flow: DataFlow) {
        pendingDisclosureState.value = null
        consents.grant(flow, SOURCE)
        rowFor(flow)?.let { store.setRaw(it, "true") }
    }

    /** "Not now": the row stays off; the decline is kept as an audit record. */
    fun declineDisclosure(flow: DataFlow) {
        pendingDisclosureState.value = null
        consents.decline(flow, SOURCE)
    }

    fun toggle(row: RowState) {
        setRaw(row.def, (!(row.raw.toBooleanStrictOrNull() ?: false)).toString())
    }

    /** Resets one section to defaults. */
    fun reset(sectionId: String) {
        if (sectionId == APPEARANCE_SECTION) {
            store.resetAppearance()
        } else {
            SettingsGroup.entries.firstOrNull { it.name == sectionId }?.let { group ->
                store.resetGroup(group)
                // Defaults are off: a reset of a group holding a consent-gated row is also a withdrawal.
                DakSettings.byGroup(group).forEach { def -> consentFlowFor(def)?.let { consents.withdraw(it, SOURCE) } }
            }
        }
    }

    override fun rebuildIndex(): Boolean = indexControl.rebuildIndex()

    /** Rows that switch an off-device data flow on (each needs its own consent). */
    private fun consentFlowFor(def: SettingDef<*>): DataFlow? = when (def.key) {
        DakSettings.jevOptIn.key -> DataFlow.CLOUD_CLASSIFICATION
        else -> null
    }

    private fun rowFor(flow: DataFlow): SettingDef<*>? = when (flow) {
        DataFlow.CLOUD_CLASSIFICATION -> DakSettings.jevOptIn
        else -> null
    }

    /** Section id for a setting key (deep links from anywhere in the app). */
    fun sectionIdFor(key: String): String? = when {
        AppearanceSettings.byKey(key) != null -> APPEARANCE_SECTION
        else -> DakSettings.byKey(key)?.group?.name
    }

    private companion object {
        const val SOURCE = "settings"
    }

    private fun build(raw: Map<String, String>, device: DeviceContext, q: String, labels: SettingsLabels): SettingsUiState {
        val appearanceRows = AppearanceSettings.all.filter { it.visible(device) }.map { def ->
            val row = rowState(def, raw, def.isLocked(entitlements), labels)
            // The language row shows the current app language (owned by the system, not by the settings store).
            if (def.key == AppearanceSettings.language.key) row.copy(valueLabel = AppLocales.currentLabel(appContext)) else row
        }
        val sections = buildList {
            add(SectionState(APPEARANCE_SECTION, labels.appearance, appearanceRows, emptyList()))
            for (group in SettingsGroup.entries) {
                val rows = DakSettings.byGroup(group)
                    .filter { it.visible(device) }
                    .map { rowState(it, raw, it.isLocked(entitlements), labels) }
                add(SectionState(group.name, labels.group(group), rows.filter { !it.def.advanced }, rows.filter { it.def.advanced }))
            }
        }
        val results = if (q.isBlank()) emptyList() else search(q, raw, device, sections, labels)
        return SettingsUiState(sections = sections, query = q, results = results)
    }

    private fun search(
        q: String,
        raw: Map<String, String>,
        device: DeviceContext,
        sections: List<SectionState>,
        labels: SettingsLabels,
    ): List<SearchHit> {
        val changed = raw.filter { (key, value) ->
            DakSettings.byKey(key)?.let { value != AppSettingsStore.serializedDefault(it) } ?: false
        }.keys
        val needle = q.trim().lowercase()
        val appearance = sections.first { it.id == APPEARANCE_SECTION }
        // Shown (localized) text, plus the English title, summary and keywords: English words work in any language.
        val appearanceHits = appearance.rows.filter { row ->
            listOf(row.title, row.summary, row.def.title, row.def.summary).any { it.lowercase().contains(needle) } ||
                row.def.keywords.any { it.lowercase().contains(needle) }
        }.map { SearchHit(it, APPEARANCE_SECTION, appearance.title) }
        val registryHits = SettingsSearch.search(q, device, entitlements, changedKeys = changed, text = labels).mapNotNull { hit ->
            val section = sections.firstOrNull { it.id == hit.group.name } ?: return@mapNotNull null
            val row = (section.rows + section.advanced).firstOrNull { it.key == hit.key } ?: return@mapNotNull null
            SearchHit(row, section.id, section.title)
        }
        return appearanceHits + registryHits
    }
}
