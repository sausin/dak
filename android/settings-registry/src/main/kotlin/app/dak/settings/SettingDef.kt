package app.dak.settings

import app.dak.premium.Entitlements
import app.dak.premium.Feature

/**
 * The seven top-level Settings groups, in the order the build plan's "Proposed groups" table
 * lists them (ordered by how often people touch them).
 */
enum class SettingsGroup(val displayName: String) {
    NOTIFICATIONS("Notifications"),
    CATEGORIES_SPAM("Categories and spam"),
    FINANCE("Finance"),
    SIMS_SENDING("SIMs and sending"),
    BACKUP_DATA("Backup, data and privacy"),
    AUTOMATIONS("Automations"),
    TRANSLATION("Translation"),
}

/** Which tier a setting belongs to. [Premium] carries the [Feature] gating it. */
sealed class SettingTier {
    data object Free : SettingTier()
    data class Premium(val feature: Feature) : SettingTier()
}

/** One selectable value for [ControlType.SingleChoice]. */
data class ChoiceOption(val value: String, val label: String)

/** The kind of control a row renders as. */
sealed class ControlType {
    data object Toggle : ControlType()
    data class SingleChoice(val options: List<ChoiceOption>) : ControlType()
    data class Slider(val range: IntRange, val step: Int) : ControlType()
    data object Text : ControlType()

    /** A navigation/action row with no stored value of its own (e.g. "Rebuild index"). */
    data object Action : ControlType()
}

/**
 * Device facts a [SettingDef.visible] predicate reads to hide rows that cannot apply, e.g. a
 * second-SIM row on a single-SIM phone or an MMS row with no data plan.
 */
data class DeviceContext(
    val simCount: Int,
    val hasMmsData: Boolean,
    val apiLevel: Int,
    val hasBiometric: Boolean = true,
)

/**
 * A declarative record for one Settings row. The registry ([DakSettings]) is the single source of
 * truth the UI, the search index, deep links, per-group reset and settings export are all driven
 * from — adding a setting is adding a record, never a screen.
 *
 * @param T the value type this row stores (Boolean, String, Int, ...).
 * @param key stable, storage/deep-link key, dotted by group e.g. `"notifications.otpAutoDelete"`.
 * @param summary one plain-language line; mentions battery/data/roaming cost where relevant.
 * @param keywords synonyms the search box should also match (e.g. "one time password", "code").
 * @param advanced true if this row lives in the group's collapsed "Advanced" block.
 * @param visible hides the row entirely when it cannot apply on this device.
 * @param serialize / deserialize round-trip [T] to the string form [SettingsStore] persists and
 *   [SettingsStore.export] emits as JSON; deserialize returns null for a value it cannot parse.
 */
data class SettingDef<T>(
    val key: String,
    val group: SettingsGroup,
    val title: String,
    val summary: String,
    val control: ControlType,
    val default: T,
    val keywords: List<String> = emptyList(),
    val tier: SettingTier = SettingTier.Free,
    val advanced: Boolean = false,
    val visible: (DeviceContext) -> Boolean = { true },
    val serialize: (T) -> String,
    val deserialize: (String) -> T?,
) {
    /** True when this row is premium-gated and the caller's [Entitlements] does not grant it. */
    fun isLocked(entitlements: Entitlements): Boolean {
        val tier = this.tier
        return tier is SettingTier.Premium && !entitlements.has(tier.feature)
    }
}
