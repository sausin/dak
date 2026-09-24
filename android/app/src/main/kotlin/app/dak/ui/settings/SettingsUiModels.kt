package app.dak.ui.settings

import app.dak.settings.AppSettingsStore
import app.dak.settings.ChoiceOption
import app.dak.settings.ControlType
import app.dak.settings.SettingDef
import app.dak.settings.SettingTier
import app.dak.settings.SettingsGroup

/** Id of the app-declared Appearance section (registry groups use [SettingsGroup.name]). */
const val APPEARANCE_SECTION = "appearance"

/**
 * One rendered row. [raw] is the stored string form; [valueLabel] is what the row shows inline. [title], [summary]
 * and [options] are in the app language ([SettingsLabels]); [def] keeps the English literals and the behaviour.
 */
data class RowState(
    val def: SettingDef<*>,
    val raw: String,
    val valueLabel: String?,
    val locked: Boolean,
    /** One-line reason shown on locked rows. */
    val lockReason: String?,
    val changed: Boolean,
    val title: String = def.title,
    val summary: String = def.summary,
    /** Choice options with their display labels, in dialog order (empty for other controls). */
    val options: List<Pair<ChoiceOption, String>> = def.choiceOptions().map { it to it.label },
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
            .joinToString(" · ") { "${it.title}: ${it.valueLabel}" }
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

/** Inline label for a stored value, in the language of [labels]. */
internal fun valueLabel(def: SettingDef<*>, raw: String, labels: SettingsLabels = SettingsLabels.English): String? = when (val control = def.control) {
    is ControlType.Toggle -> labels.onOff(raw.toBooleanStrictOrNull() == true)
    is ControlType.SingleChoice -> control.options.firstOrNull { it.value == raw }?.let { labels.option(def, it) } ?: raw
    is ControlType.Slider -> raw
    is ControlType.Text -> raw.ifBlank { null }
    is ControlType.Action -> null
}

internal fun rowState(
    def: SettingDef<*>,
    raw: Map<String, String>,
    locked: Boolean,
    labels: SettingsLabels = SettingsLabels.English,
): RowState {
    val default = AppSettingsStore.serializedDefault(def)
    val value = raw[def.key] ?: default
    val tier = def.tier
    return RowState(
        def = def,
        raw = value,
        valueLabel = valueLabel(def, value, labels),
        locked = locked,
        lockReason = if (locked && tier is SettingTier.Premium) labels.feature(tier.feature) else null,
        changed = raw[def.key] != null && raw[def.key] != default,
        title = labels.title(def),
        summary = labels.summary(def),
        options = def.choiceOptions().map { it to labels.option(def, it) },
    )
}

/** Options of a choice row, or empty. */
internal fun SettingDef<*>.choiceOptions(): List<ChoiceOption> = (control as? ControlType.SingleChoice)?.options.orEmpty()
