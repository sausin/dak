package app.dak.ui.settings

import app.dak.R
import app.dak.premium.Feature
import app.dak.settings.AppearanceSettings
import app.dak.settings.ControlType
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStringKeys
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The name → `R.string` table ([SettingsStringIds]) covers every label the registry, the app's Appearance rows and
 * the premium features name, and each entry points at the resource of that name. The registry's English text is
 * checked against strings_settings.xml by SettingsStringResourcesTest (settings-registry); this checks the
 * app-declared rows' English. docs/i18n.md.
 */
class SettingsTextTest {

    private val appearanceNames = AppearanceSettings.all.flatMap { def ->
        listOf(SettingsStringKeys.title(def), SettingsStringKeys.summary(def)) +
            (def.control as? ControlType.SingleChoice)?.options.orEmpty().map { SettingsStringKeys.option(def, it) }
    }

    @Test
    fun `every label name has an id`() {
        val needed = SettingsStringKeys.all() + Feature.entries.map { it.stringKey } + appearanceNames
        val missing = needed.filter { it !in SettingsStringIds.byName }
        assertTrue(missing.isEmpty(), "add to SettingsStringIds (and strings_settings.xml): $missing")
    }

    @Test
    fun `every id is the resource of its name`() {
        SettingsStringIds.byName.forEach { (name, id) ->
            val field = checkNotNull(runCatching { R.string::class.java.getField(name) }.getOrNull()) { "no R.string.$name" }
            assertEquals(field.getInt(null), id, "SettingsStringIds[$name] points at another resource")
        }
    }

    @Test
    fun `app-declared rows keep their english text in the resources`() {
        val xml = readStrings(File("src/main/res/values/strings_settings.xml"))
        AppearanceSettings.all.forEach { def ->
            assertEquals(def.title, xml[SettingsStringKeys.title(def)], def.key)
            assertEquals(def.summary, xml[SettingsStringKeys.summary(def)], def.key)
            (def.control as? ControlType.SingleChoice)?.options?.forEach {
                assertEquals(it.label, xml[SettingsStringKeys.option(def, it)], "${def.key}=${it.value}")
            }
        }
        assertEquals(SettingsLabels.English.onOff(true), xml["settings_value_on"])
        assertEquals(SettingsLabels.English.onOff(false), xml["settings_value_off"])
        assertEquals(SettingsLabels.English.appearance, xml["settings_section_appearance"])
    }

    @Test
    fun `rows carry their labels and english stays the default`() {
        val row = rowState(DakSettings.otpAutoDelete, emptyMap(), locked = false)
        assertEquals(DakSettings.otpAutoDelete.title, row.title)
        assertEquals(listOf("1 hour", "24 hours", "Off"), row.options.map { it.second })
        val locked = rowState(DakSettings.otpBinRetention, emptyMap(), locked = true)
        assertEquals(Feature.ADJUSTABLE_OTP_BIN_RETENTION.summary, locked.lockReason)
        // A different language only changes the text, never the values.
        val upper = object : SettingsLabels by SettingsLabels.English {
            override fun option(def: app.dak.settings.SettingDef<*>, option: app.dak.settings.ChoiceOption) = option.label.uppercase()
            override fun onOff(on: Boolean) = if (on) "AN" else "AUS"
        }
        assertEquals("24 HOURS", valueLabel(DakSettings.otpAutoDelete, "24h", upper))
        assertEquals("AN", valueLabel(DakSettings.quickActions, "true", upper))
        assertEquals(listOf("1h", "24h", "off"), rowState(DakSettings.otpAutoDelete, emptyMap(), false, upper).options.map { it.first.value })
    }

    private fun readStrings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }
}
