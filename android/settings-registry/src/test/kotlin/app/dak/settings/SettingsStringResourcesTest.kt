package app.dak.settings

import app.dak.premium.Feature
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every label the registry names ([SettingsStringKeys], [Feature.stringKey]) exists in the app's
 * `values/strings_settings.xml` with exactly the registry's English text, so the app renders the same English it did
 * before labels became resources, and a translator's file covers every row. Reads the resource file from the source
 * tree (this module has no Android resources of its own). The app-side test (`SettingsTextTest`) checks the
 * name → `R.string` map; this one runs without the Android SDK.
 *
 * On failure the message lists the XML lines to add or fix.
 */
class SettingsStringResourcesTest {

    private val file = File("../app/src/main/res/values/strings_settings.xml")

    private fun expected(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(name: String, english: String) {
            val previous = out.put(name, english)
            // A shared option label (labelKey) must read the same wherever it is used.
            assertTrue(previous == null || previous == english, "$name has two English texts: '$previous' / '$english'")
        }
        SettingsGroup.entries.forEach { put(SettingsStringKeys.group(it), it.displayName) }
        DakSettings.all.forEach { def ->
            put(SettingsStringKeys.title(def), def.title)
            put(SettingsStringKeys.summary(def), def.summary)
            (def.control as? ControlType.SingleChoice)?.options?.forEach { put(SettingsStringKeys.option(def, it), it.label) }
        }
        Feature.entries.forEach { put(it.stringKey, it.summary) }
        return out
    }

    @Test
    fun `every registry label has a resource with the same english text`() {
        assertTrue(file.exists(), "missing ${file.absolutePath}")
        val actual = readStrings(file)
        val expected = expected()
        val problems = expected.filter { (name, english) -> actual[name] != english }
        assertTrue(
            problems.isEmpty(),
            "strings_settings.xml is out of sync with the registry (${problems.size}). Expected lines:\n" +
                problems.entries.joinToString("\n") { (name, english) -> "    <string name=\"$name\">${escape(english)}</string>" },
        )
    }

    @Test
    fun `resource names are valid and match the registry keys`() {
        val names = expected().keys
        names.forEach { assertTrue(Regex("[a-z][A-Za-z0-9_]*").matches(it), "invalid resource name $it") }
        assertEquals(names.size, SettingsStringKeys.all().size + Feature.entries.size)
    }

    @Test
    fun `escaping round trips`() {
        val tricky = "Show a \"Copy code\" button, it's 100% & <fine> @home ?maybe"
        val xml = File.createTempFile("strings", ".xml").apply { deleteOnExit() }
        xml.writeText("<resources><string name=\"x\">${escape(tricky)}</string></resources>")
        assertEquals(tricky, readStrings(xml)["x"])
    }

    companion object {
        /** `<string name>` → text as Android returns it (resource escapes undone), for one values file. */
        fun readStrings(file: File): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { i ->
                val e = nodes.item(i) as Element
                e.getAttribute("name") to unescape(e.textContent)
            }
        }

        /** Android string-resource escaping for plain text (quotes, apostrophes, leading @/?, XML specials). */
        fun escape(text: String): String {
            val body = text.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return if (body.startsWith("@") || body.startsWith("?")) "\\" + body else body
        }

        fun unescape(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    when (val n = text[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> out.append(n)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
