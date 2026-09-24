package app.dak.settings

/**
 * Stable Android string-resource names for every user-visible label in the registry.
 *
 * This module is pure Kotlin and cannot see the app's `R` class, so each label is identified by a resource *name*
 * derived from data that is already stable (the setting key, the option value, the group name). The registry keeps
 * the English text next to each record ([SettingDef.title], [SettingDef.summary], [ChoiceOption.label],
 * [SettingsGroup.displayName]): it is the fallback, what the JVM tests read, and an extra search vocabulary so
 * English words find a row in every app language.
 *
 * The app maps each name to its `R.string` id (`app.dak.ui.settings.SettingsText`), and
 * `SettingsStringResourcesTest` checks that `app/src/main/res/values/strings_settings.xml` defines every name with
 * exactly the registry's English text, so the two cannot drift. See docs/i18n.md.
 */
object SettingsStringKeys {

    fun title(def: SettingDef<*>): String = "setting_${slug(def.key)}_title"

    fun summary(def: SettingDef<*>): String = "setting_${slug(def.key)}_summary"

    fun option(def: SettingDef<*>, option: ChoiceOption): String =
        option.labelKey ?: "setting_${slug(def.key)}_opt_${slug(option.value)}"

    fun group(group: SettingsGroup): String = "settings_group_${group.name.lowercase()}"

    /** Every resource name [settings] and the groups need, each once, in registry order. */
    fun all(settings: List<SettingDef<*>> = DakSettings.all): List<String> = buildList {
        SettingsGroup.entries.forEach { add(group(it)) }
        settings.forEach { def ->
            add(title(def))
            add(summary(def))
            (def.control as? ControlType.SingleChoice)?.options?.forEach { add(option(def, it)) }
        }
    }.distinct()

    /** Resource names allow letters, digits and `_` only; keys are dotted and option values may start with a digit. */
    private fun slug(s: String): String = s.map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }.joinToString("")
}

/**
 * The text a Settings row is shown with. [English] reads the registry; the app supplies one backed by string
 * resources in the current app language. Search matches both (see [SettingsSearch]).
 */
interface SettingsText {
    fun title(def: SettingDef<*>): String
    fun summary(def: SettingDef<*>): String

    object English : SettingsText {
        override fun title(def: SettingDef<*>): String = def.title
        override fun summary(def: SettingDef<*>): String = def.summary
    }
}
