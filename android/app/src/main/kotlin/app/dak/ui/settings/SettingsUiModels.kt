package app.dak.ui.settings

import app.dak.settings.AppSettingsStore
import app.dak.settings.ChoiceOption
import app.dak.settings.ControlType
import app.dak.settings.SettingDef
import app.dak.settings.SettingTier
import app.dak.settings.SettingsGroup

/** Id of the app-declared Appearance section (registry groups use [SettingsGroup.name]). */
const val APPEARANCE_SECTION = "appearance"

/** One rendered row. [raw] is the stored string form; [valueLabel] is what the row shows inline. */
data class RowState(
    val def: SettingDef<*>,
    val raw: String,
    val valueLabel: String?,
    val locked: Boolean,
    /** One-line reason shown on locked rows. */
    val lockReason: String?,
    val changed: Boolean,
) {
    val key: String get() = def.key
}

/** A Settings section: a registry group or Appearance. */
data class SectionState(
    val id: String,
    val title: String,
    val rows: List<RowState>,
    val advanced: List<RowState>,
) {
    /** Up to three current values for the section's overview line ("24 hours · Sender only"). */
    val inlineSummary: String
        get() = rows.asSequence()
            .filter { !it.locked && it.def.control !is ControlType.Action && it.valueLabel != null }
            .take(3)
            .joinToString(" · ") { "${it.def.title}: ${it.valueLabel}" }
}

data class SearchHit(val row: RowState, val sectionId: String, val sectionTitle: String)

data class SettingsUiState(
    val sections: List<SectionState> = emptyList(),
    val query: String = "",
    val results: List<SearchHit> = emptyList(),
) {
    fun section(id: String): SectionState? = sections.firstOrNull { it.id == id }
}

/** Section id a setting key belongs to. */
internal fun sectionIdOf(def: SettingDef<*>, isAppearance: Boolean): String =
    if (isAppearance) APPEARANCE_SECTION else def.group.name

/** Inline label for a stored value. */
internal fun valueLabel(def: SettingDef<*>, raw: String): String? = when (val control = def.control) {
    is ControlType.Toggle -> if (raw.toBooleanStrictOrNull() == true) "On" else "Off"
    is ControlType.SingleChoice -> control.options.firstOrNull { it.value == raw }?.label ?: raw
    is ControlType.Slider -> raw
    is ControlType.Text -> raw.ifBlank { null }
    is ControlType.Action -> null
}

internal fun rowState(def: SettingDef<*>, raw: Map<String, String>, locked: Boolean): RowState {
    val default = AppSettingsStore.serializedDefault(def)
    val value = raw[def.key] ?: default
    val tier = def.tier
    return RowState(
        def = def,
        raw = value,
        valueLabel = valueLabel(def, value),
        locked = locked,
        lockReason = if (locked && tier is SettingTier.Premium) tier.feature.summary else null,
        changed = raw[def.key] != null && raw[def.key] != default,
    )
}

/** Options of a choice row, or empty. */
internal fun SettingDef<*>.choiceOptions(): List<ChoiceOption> = (control as? ControlType.SingleChoice)?.options.orEmpty()
