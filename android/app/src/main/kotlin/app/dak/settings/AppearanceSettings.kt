package app.dak.settings

import app.dak.ui.theme.AppearancePrefs
import app.dak.ui.theme.ContrastMode
import app.dak.ui.theme.ThemeMode

/**
 * Appearance rows. The shared registry ([DakSettings]) has no Appearance group yet, so the app declares these
 * records itself with the registry's own [SettingDef] type; [AppSettingsStore] includes them in export/import and
 * per-section reset. [SettingDef.group] is unused for these rows (the UI renders them as their own section).
 */
object AppearanceSettings {
    private val placeholderGroup = SettingsGroup.NOTIFICATIONS

    val themeMode = SettingDef(
        key = "appearance.themeMode",
        group = placeholderGroup,
        title = "Theme",
        summary = "Follow the system, or always light or dark.",
        control = ControlType.SingleChoice(
            listOf(ChoiceOption("system", "System default"), ChoiceOption("light", "Light"), ChoiceOption("dark", "Dark")),
        ),
        default = "system",
        keywords = listOf("dark mode", "night", "light", "theme"),
        serialize = { it },
        deserialize = { v -> v.takeIf { it in setOf("system", "light", "dark") } },
    )

    val amoled = SettingDef(
        key = "appearance.amoled",
        group = placeholderGroup,
        title = "True black",
        summary = "Pure black backgrounds in dark theme. Saves battery on OLED screens.",
        control = ControlType.Toggle,
        default = false,
        keywords = listOf("amoled", "oled", "black", "battery", "dark"),
        serialize = { it.toString() },
        deserialize = { it.toBooleanStrictOrNull() },
    )

    val contrast = SettingDef(
        key = "appearance.contrast",
        group = placeholderGroup,
        title = "Contrast",
        summary = "Stronger text and outline colours. System follows your accessibility setting.",
        control = ControlType.SingleChoice(
            listOf(ChoiceOption("system", "System default"), ChoiceOption("standard", "Standard"), ChoiceOption("high", "High")),
        ),
        default = "system",
        keywords = listOf("high contrast", "accessibility", "readability"),
        serialize = { it },
        deserialize = { v -> v.takeIf { it in setOf("system", "standard", "high") } },
    )

    val dynamicColor = SettingDef(
        key = "appearance.dynamicColor",
        group = placeholderGroup,
        title = "Colours from wallpaper",
        summary = "Match the app's colours to your wallpaper.",
        control = ControlType.Toggle,
        default = true,
        keywords = listOf("dynamic colour", "dynamic color", "material you", "wallpaper"),
        visible = { it.apiLevel >= 31 },
        serialize = { it.toString() },
        deserialize = { it.toBooleanStrictOrNull() },
    )

    /**
     * App language (per-app language, Android 13+ system setting, or Dak's own on older versions: see
     * [app.dak.i18n.AppLocales]). An action row: the choice lives with the system, not in this store, so it is never
     * exported or reset; tapping it opens the language picker.
     */
    val language = SettingDef(
        key = "appearance.language",
        group = placeholderGroup,
        title = "Language",
        summary = "The language Dak uses. System default follows your phone's language.",
        control = ControlType.Action,
        default = "",
        keywords = listOf("language", "app language", "locale", "translation", "hindi", "english", "भाषा"),
        serialize = { it },
        // Never stored: an imported settings file cannot set it (the system owns the app language).
        deserialize = { _: String -> null },
    )

    /** Rows in display order. */
    val all: List<SettingDef<*>> = listOf(language, themeMode, amoled, contrast, dynamicColor)

    fun byKey(key: String): SettingDef<*>? = all.firstOrNull { it.key == key }

    /** Maps stored values to the theme's [AppearancePrefs]. */
    fun prefsFrom(store: SettingsStore): AppearancePrefs = AppearancePrefs(
        mode = when (store.get(themeMode)) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        },
        amoled = store.get(amoled),
        contrast = when (store.get(contrast)) {
            "standard" -> ContrastMode.STANDARD
            "high" -> ContrastMode.HIGH
            else -> ContrastMode.SYSTEM
        },
        dynamicColor = store.get(dynamicColor),
        otpScale = if (store.get(DakSettings.otpDisplaySize) == "large") 1.25f else 1f,
    )
}
