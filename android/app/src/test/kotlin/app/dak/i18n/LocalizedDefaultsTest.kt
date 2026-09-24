package app.dak.i18n

import app.dak.automations.birthdays.WishTemplates
import app.dak.automations.forwarding.ForwardingSpec
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * English resources that stand in for constants of the pure modules must read exactly like them, so English users
 * see no change and the loop guard's English marker matches what new English rules send. docs/i18n.md.
 */
class LocalizedDefaultsTest {
    private val values = File("src/main/res/values")

    private fun strings(): Map<String, String> = values.listFiles { f -> f.name.startsWith("strings") }!!.flatMap { f ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
        (0 until nodes.length).map { i -> (nodes.item(i) as Element).let { it.getAttribute("name") to it.textContent } }
    }.toMap()

    @Test
    fun `english defaults match the pure modules`() {
        val s = strings()
        assertEquals(ForwardingSpec.DEFAULT_TEMPLATE, s["fw_default_template"])
        assertEquals(ForwardingSpec.DEFAULT_TEMPLATE, ForwardingSpec.defaultTemplate(s["fw_default_template"]))
        assertEquals(WishTemplates.FALLBACK_OCCASION, s["wish_fallback_occasion"])
        assertTrue(s.getValue("selftest_sms_body").contains("%1\$s"))
    }

    @Test
    fun `locale config lists english first`() {
        val nodes = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/xml/locales_config.xml")).getElementsByTagName("locale")
        val tags = (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name") }
        assertEquals("en", tags.first())
    }
}
