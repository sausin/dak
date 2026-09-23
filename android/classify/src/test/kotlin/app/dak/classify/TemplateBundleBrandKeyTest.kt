package app.dak.classify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemplateBundleBrandKeyTest {

    @Test
    fun `headers of one brand share the first listed header`() {
        val bundle = TemplateBundle.loadDefault()
        assertEquals("HDFCBK", bundle.brandKey("HDFCBK"))
        assertEquals("HDFCBK", bundle.brandKey("HDFC"))
        assertEquals("hdfcbk".uppercase(), bundle.brandKey("hdfc"))
        assertEquals("ICICIB", bundle.brandKey("ICICIT"))
        assertNull(bundle.brandKey("UNKNOWNHDR"))
    }

    @Test
    fun `brand comparison ignores case and surrounding spaces`() {
        val bundle = TemplateBundle.parseUnsigned(
            """{"version":1,"issuedAt":0,"senders":[
                {"header":"CURIER","brand":"Fast Courier"},
                {"header":"FSTCUR","brand":" fast courier "},
                {"header":"OTHER","brand":"Other"}
            ]}""",
        )
        assertEquals("CURIER", bundle.brandKey("FSTCUR"))
        assertEquals("OTHER", bundle.brandKey("other"))
    }
}
