package app.dak.ui.fraud

import app.dak.safety.helplines.HelplineBundle
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Helpline translations are keyed by bundle id and used only while their English text equals the bundle's
 * ([HelplineTexts]); this keeps the strings and the bundle in step so the translations are actually shown.
 */
class HelplineTextTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }

    @Test
    fun `every bundled helpline has strings whose English is the bundle text, in every language`() {
        val payload = assertNotNull(HelplineBundle.parseBundled(File(assets, HelplineBundle.ASSET_NAME).readText()))
        val all = payload.helplines + payload.bankCardBlock
        assertEquals(all.map { it.id }.toSet(), HelplineTexts.STRINGS.keys, "HelplineTexts.STRINGS must list every bundled helpline")
        val english = strings(File(res, "values"))
        for (h in all) {
            val key = "helpline_" + h.id.replace('-', '_')
            assertEquals(h.name, english["${key}_name"], "${key}_name must be the bundle's English name")
            assertEquals(h.purpose, english["${key}_purpose"], "${key}_purpose must be the bundle's English purpose")
            for (lang in listOf("hi", "es", "fr")) {
                val translated = strings(File(res, "values-$lang"))
                assertNotNull(translated["${key}_name"], "$lang ${key}_name")
                assertNotNull(translated["${key}_purpose"], "$lang ${key}_purpose")
            }
        }
    }

    private fun strings(dir: File): Map<String, String> = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
        .flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { i ->
                val e = nodes.item(i) as Element
                e.getAttribute("name") to e.textContent.replace("\\'", "'").replace("\\\"", "\"")
            }
        }.toMap()
}
