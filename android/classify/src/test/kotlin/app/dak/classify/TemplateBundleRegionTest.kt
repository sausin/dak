package app.dak.classify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Region filtering, brand folding and hostile OTA payloads of [TemplateBundle]. */
class TemplateBundleRegionTest {

    private val bundle = TemplateBundle.parseUnsigned(
        """
        {"version":3,"issuedAt":5,
         "senders":[
           {"header":"hdfcbk","brand":"HDFC Bank","regions":["IN"]},
           {"header":"HDFCBN","brand":" hdfc bank "},
           {"header":"BARCLY","brand":"Barclays","regions":["GB"]},
           {"header":"NOBRND","brand":""}
         ],
         "rules":[
           {"id":"generic-50","category":"SPAM","pattern":"a","priority":50},
           {"id":"india-50","category":"TRANSACTION","pattern":"b","priority":50,"regions":["in"]},
           {"id":"uk-60","category":"TRANSACTION","pattern":"c","priority":60,"regions":["GB"]},
           {"id":"generic-10","category":"PROMOTION","pattern":"d","priority":10},
           {"id":"hdfc-only","category":"OTP","pattern":"e","priority":70,"senderHeaders":["HDFCBK"]}
         ],
         "futureField":"ignored"}
        """.trimIndent(),
    )

    @Test
    fun `sender lookup is case-insensitive and region-aware`() {
        assertEquals("HDFC Bank", bundle.sender("HdfcBk")?.brand)
        assertEquals("HDFC Bank", bundle.sender("HDFCBK", SenderRegion.INDIA)?.brand)
        assertNull(bundle.sender("HDFCBK", SenderRegion.of("GB")))
        assertEquals("HDFC Bank", bundle.sender("HDFCBK", SenderRegion.UNKNOWN)?.brand)
        assertEquals(" hdfc bank ", bundle.sender("HDFCBN", SenderRegion.of("GB"))?.brand) // untagged: global
        assertNull(bundle.sender("NOSUCH"))
        assertEquals(3, bundle.version)
        assertEquals(5L, bundle.issuedAt)
    }

    @Test
    fun `brand key folds every header of a brand onto its first header`() {
        assertEquals("HDFCBK", bundle.brandKey("HDFCBN"))
        assertEquals("HDFCBK", bundle.brandKey("hdfcbk"))
        assertEquals("BARCLY", bundle.brandKey("BARCLY"))
        assertNull(bundle.brandKey("NOBRND")) // a blank brand folds nothing
        assertNull(bundle.brandKey("NOSUCH"))
    }

    @Test
    fun `region rules apply only in their region and win ties there`() {
        assertEquals(
            listOf("hdfc-only", "india-50", "generic-50", "generic-10"),
            bundle.rulesFor("HDFCBK", SenderRegion.INDIA).map { it.id },
        )
        assertEquals(listOf("uk-60", "generic-50", "generic-10"), bundle.rulesFor("BARCLY", SenderRegion.of("GB")).map { it.id })
        // Unknown region: every rule, highest priority first, region rules first on ties.
        assertEquals(
            listOf("uk-60", "india-50", "generic-50", "generic-10"),
            bundle.rulesFor(null, SenderRegion.UNKNOWN).map { it.id },
        )
    }

    @Test
    fun `malformed OTA payloads are rejected, never thrown`() {
        val accept = BundleVerifier { _, _ -> true }
        val throwing = BundleVerifier { _, _ -> error("verifier crashed") }
        val valid = """{"payload":{"version":1,"issuedAt":0},"signature":"AAAA"}"""
        assertTrue(TemplateBundle.parse(valid, accept) != null)
        for (bad in listOf("", "{", "null", "[]", """{"payload":{}}""", """{"payload":{"version":"x","issuedAt":0},"signature":"AAAA"}""")) {
            assertNull(TemplateBundle.parse(bad, accept), bad)
        }
        assertNull(TemplateBundle.parse("""{"payload":{"version":1,"issuedAt":0},"signature":"not base64!"}""", accept))
        assertNull(TemplateBundle.parse(valid, throwing))
    }

    @Test
    fun `every default rule compiles, and none matches everything`() {
        for (rule in TemplateBundle.loadDefault().rules) {
            val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
            assertTrue(!regex.containsMatchIn(""), "${rule.id} matches the empty body")
            assertTrue(!regex.containsMatchIn("hello"), "${rule.id} matches plain chat")
        }
    }

    @Test
    fun `default rule ids are unique`() {
        val ids = TemplateBundle.loadDefault().rules.map { it.id }
        assertEquals(ids.size, ids.toSet().size, ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toString())
    }
}
