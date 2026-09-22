package app.dak.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.di.IndexControl
import app.dak.navigation.Routes
import app.dak.premium.Entitlements
import app.dak.settings.AppSettingsStore
import app.dak.settings.AppearanceSettings
import app.dak.settings.DakSettings
import app.dak.settings.DeviceContext
import app.dak.settings.DeviceContextProvider
import app.dak.settings.SettingDef
import app.dak.settings.SettingsGroup
import app.dak.settings.SettingsSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel(), SettingsCommands {

    /** Section shown by SettingsGroupScreen (null on the root screen). */
    val sectionId: String? = savedStateHandle.get<String>(Routes.ARG_GROUP)

    /** Deep-linked setting key to scroll to and highlight. */
    val focusKey: String? = savedStateHandle.get<String>(Routes.ARG_FOCUS)

    private val query = MutableStateFlow("")

    val state: StateFlow<SettingsUiState> = combine(
        store.snapshot,
        entitlements.granted,
        deviceContext.updates,
        query,
    ) { raw, _, device, q -> build(raw, device, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun setRaw(def: SettingDef<*>, raw: String) {
        if (def.isLocked(entitlements)) return
        store.setRaw(def, raw)
    }

    fun toggle(row: RowState) {
        setRaw(row.def, (!(row.raw.toBooleanStrictOrNull() ?: false)).toString())
    }

    /** Resets one section to defaults. */
    fun reset(sectionId: String) {
        if (sectionId == APPEARANCE_SECTION) {
            store.resetAppearance()
        } else {
            SettingsGroup.entries.firstOrNull { it.name == sectionId }?.let { store.resetGroup(it) }
        }
    }

    override fun rebuildIndex(): Boolean = indexControl.rebuildIndex()

    /** Section id for a setting key (deep links from anywhere in the app). */
    fun sectionIdFor(key: String): String? = when {
        AppearanceSettings.byKey(key) != null -> APPEARANCE_SECTION
        else -> DakSettings.byKey(key)?.group?.name
    }

    private fun build(raw: Map<String, String>, device: DeviceContext, q: String): SettingsUiState {
        val appearanceRows = AppearanceSettings.all.filter { it.visible(device) }.map { rowState(it, raw, it.isLocked(entitlements)) }
        val sections = buildList {
            add(SectionState(APPEARANCE_SECTION, "Appearance", appearanceRows, emptyList()))
            for (group in SettingsGroup.entries) {
                val rows = DakSettings.byGroup(group)
                    .filter { it.visible(device) }
                    .map { rowState(it, raw, it.isLocked(entitlements)) }
                add(SectionState(group.name, group.displayName, rows.filter { !it.def.advanced }, rows.filter { it.def.advanced }))
            }
        }
        val results = if (q.isBlank()) emptyList() else search(q, raw, device, sections)
        return SettingsUiState(sections = sections, query = q, results = results)
    }

    private fun search(q: String, raw: Map<String, String>, device: DeviceContext, sections: List<SectionState>): List<SearchHit> {
        val changed = raw.filter { (key, value) ->
            DakSettings.byKey(key)?.let { value != AppSettingsStore.serializedDefault(it) } ?: false
        }.keys
        val needle = q.trim().lowercase()
        val appearance = sections.first { it.id == APPEARANCE_SECTION }
        val appearanceHits = appearance.rows.filter { row ->
            row.def.title.lowercase().contains(needle) || row.def.keywords.any { it.lowercase().contains(needle) } ||
                row.def.summary.lowercase().contains(needle)
        }.map { SearchHit(it, APPEARANCE_SECTION, appearance.title) }
        val registryHits = SettingsSearch.search(q, device, entitlements, changedKeys = changed).mapNotNull { hit ->
            val section = sections.firstOrNull { it.id == hit.group.name } ?: return@mapNotNull null
            val row = (section.rows + section.advanced).firstOrNull { it.key == hit.key } ?: return@mapNotNull null
            SearchHit(row, section.id, section.title)
        }
        return appearanceHits + registryHits
    }
}
