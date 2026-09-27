package app.dak.ui.settings

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.dak.R
import app.dak.automation.AppLocaleText
import app.dak.finance.money.Money
import app.dak.premium.Feature
import app.dak.settings.ChoiceOption
import app.dak.settings.SettingDef
import app.dak.settings.SettingsGroup
import app.dak.settings.SettingsStringKeys
import app.dak.settings.SettingsText
import app.dak.ui.common.text.MoneyDisplay
import java.text.NumberFormat
import java.util.Locale

/**
 * Every piece of text the Settings screens take from the registry, in the app language. [English] reads the
 * registry's English literals (JVM tests); [ResourceSettingsLabels] reads string resources. Search uses the same
 * object as [SettingsText], so what matches is what is shown (plus English, see [app.dak.settings.SettingsSearch]).
 */
interface SettingsLabels : SettingsText {
    fun option(def: SettingDef<*>, option: ChoiceOption): String
    fun group(group: SettingsGroup): String
    fun feature(feature: Feature): String
    fun onOff(on: Boolean): String
    val appearance: String

    /** A slider setting's value with its unit ("10 minutes", "70%", "₹5.00"); see [SliderUnits]. */
    fun sliderValue(def: SettingDef<*>, value: Int): String

    /** What a text setting shows while blank (the default SIM names), or null to show nothing. */
    fun blankText(def: SettingDef<*>): String?

    /** "Title: value" in a section's overview line. */
    fun inlineValue(title: String, value: String): String

    /** [items] joined as a list ("A, B and C"). */
    fun list(items: List<String>): String

    object English : SettingsLabels {
        override fun title(def: SettingDef<*>): String = def.title
        override fun summary(def: SettingDef<*>): String = def.summary
        override fun option(def: SettingDef<*>, option: ChoiceOption): String = option.label
        override fun group(group: SettingsGroup): String = group.displayName
        override fun feature(feature: Feature): String = feature.summary
        override fun onOff(on: Boolean): String = if (on) "On" else "Off"
        override val appearance: String = "Appearance"
        override fun sliderValue(def: SettingDef<*>, value: Int): String = value.toString()
        override fun blankText(def: SettingDef<*>): String? = SliderUnits.simSlotOf(def)?.let { "SIM $it" }
        override fun inlineValue(title: String, value: String): String = "$title: $value"
        override fun list(items: List<String>): String = items.joinToString(", ")
    }
}

/**
 * [SettingsLabels] from string resources. A name missing from [SettingsStringIds] (a row added without its
 * resources; SettingsTextTest fails on that) falls back to the English literal rather than crashing.
 */
class ResourceSettingsLabels(private val resources: Resources) : SettingsLabels {
    private fun text(name: String, english: String): String =
        SettingsStringIds.byName[name]?.let { resources.getString(it) } ?: english

    override fun title(def: SettingDef<*>): String = text(SettingsStringKeys.title(def), def.title)
    override fun summary(def: SettingDef<*>): String = text(SettingsStringKeys.summary(def), def.summary)
    override fun option(def: SettingDef<*>, option: ChoiceOption): String = text(SettingsStringKeys.option(def, option), option.label)
    override fun group(group: SettingsGroup): String = text(SettingsStringKeys.group(group), group.displayName)
    override fun feature(feature: Feature): String = text(feature.stringKey, feature.summary)
    override fun onOff(on: Boolean): String = resources.getString(if (on) R.string.settings_value_on else R.string.settings_value_off)
    override val appearance: String get() = resources.getString(R.string.settings_section_appearance)

    private val locale: Locale get() = resources.configuration.locales[0] ?: Locale.getDefault()

    override fun sliderValue(def: SettingDef<*>, value: Int): String = when (SliderUnits.unitOf(def)) {
        SliderUnits.Kind.MINUTES -> resources.getQuantityString(R.plurals.setting_value_minutes, value, value)
        SliderUnits.Kind.DAYS -> resources.getQuantityString(R.plurals.setting_value_days, value, value)
        SliderUnits.Kind.DAYS_OR_NEVER ->
            if (value == 0) resources.getString(R.string.setting_value_never) else resources.getQuantityString(R.plurals.setting_value_days, value, value)
        SliderUnits.Kind.PERCENT -> NumberFormat.getPercentInstance(locale).format(value / 100.0)
        SliderUnits.Kind.MESSAGES -> resources.getQuantityString(R.plurals.setting_value_messages, value, value)
        SliderUnits.Kind.PAISE -> MoneyDisplay.format(Money(value.toLong(), "INR"), locale, homeCurrency = "INR")
        SliderUnits.Kind.NUMBER -> NumberFormat.getIntegerInstance(locale).format(value)
    }

    override fun blankText(def: SettingDef<*>): String? = SliderUnits.simSlotOf(def)?.let { resources.getString(R.string.sim_n, it) }

    override fun inlineValue(title: String, value: String): String = resources.getString(R.string.settings_inline_value, title, value)

    override fun list(items: List<String>): String = AppLocaleText.list(locale, items)
}

/** Labels for the current composition's configuration (re-created when the app language changes). */
@Composable
fun rememberSettingsLabels(): SettingsLabels {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { ResourceSettingsLabels(context.resources) }
}

/** Keeps [viewModel]'s labels in the language of this screen's configuration (see [SettingsViewModel.useResources]). */
@Composable
internal fun FollowAppLanguage(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) { viewModel.useResources(context.resources) }
}
