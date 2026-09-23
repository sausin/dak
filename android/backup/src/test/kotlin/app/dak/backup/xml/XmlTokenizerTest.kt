package app.dak.backup.xml

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class XmlTokenizerTest {

    private fun tokenize(xml: String): List<XmlToken> {
        val tokenizer = XmlTokenizer(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val tokens = mutableListOf<XmlToken>()
        while (true) {
            val t = tokenizer.next()
            tokens += t
            if (t is XmlToken.EndDocument) break
        }
        return tokens
    }

    @Test
    fun `decodes predefined entities`() {
        assertEquals("a & b < c > d \"e\" 'f'", XmlEntities.decode("a &amp; b &lt; c &gt; d &quot;e&quot; &apos;f&apos;"))
    }

    @Test
    fun `decodes numeric decimal and hex entities including newline`() {
        assertEquals("line1\nline2", XmlEntities.decode("line1&#10;line2"))
        assertEquals("A", XmlEntities.decode("&#65;"))
        assertEquals("A", XmlEntities.decode("&#x41;"))
    }

    @Test
    fun `decodes numeric entities outside the BMP as surrogate pairs`() {
        val emoji = "😀" // U+1F600 GRINNING FACE, as a Java/Kotlin surrogate pair
        assertEquals(emoji, XmlEntities.decode("&#128512;"))
        assertEquals(emoji, XmlEntities.decode("&#x1F600;"))
    }

    @Test
    fun `passes through stray ampersands and unknown entities`() {
        assertEquals("Tom & Jerry", XmlEntities.decode("Tom & Jerry"))
        assertEquals("&notareal;", XmlEntities.decode("&notareal;"))
    }

    @Test
    fun `parses attributes and self-closing elements`() {
        val tokens = tokenize("""<sms address="+1234" body="hi" />""")
        val start = tokens[0] as XmlToken.StartElement
        assertEquals("sms", start.name)
        assertEquals("+1234", start.attributes["address"])
        assertEquals("hi", start.attributes["body"])
        assertEquals(true, start.selfClosing)
    }

    @Test
    fun `decodes entities inside attribute values`() {
        val tokens = tokenize("""<sms body="line1&#10;line2 &amp; more &#128512;" />""")
        val start = tokens[0] as XmlToken.StartElement
        assertEquals("line1\nline2 & more 😀", start.attributes["body"])
    }

    @Test
    fun `parses nested elements and text content`() {
        val tokens = tokenize("<a><b>text &amp; stuff</b></a>")
        assertIs<XmlToken.StartElement>(tokens[0]).let { assertEquals("a", it.name) }
        assertIs<XmlToken.StartElement>(tokens[1]).let { assertEquals("b", it.name) }
        assertIs<XmlToken.Text>(tokens[2]).let { assertEquals("text & stuff", it.text) }
        assertIs<XmlToken.EndElement>(tokens[3]).let { assertEquals("b", it.name) }
        assertIs<XmlToken.EndElement>(tokens[4]).let { assertEquals("a", it.name) }
    }

    @Test
    fun `skips xml declaration and comments`() {
        val tokens = tokenize("<?xml version=\"1.0\"?><!-- a comment --><root/>")
        val start = tokens[0] as XmlToken.StartElement
        assertEquals("root", start.name)
    }

    @Test
    fun `xml writer round trips entity and emoji edge cases through the tokenizer`() {
        val out = ByteArrayOutputStream()
        val writer = XmlWriter(out)
        writer.writeDeclaration()
        writer.selfClosingElement(
            "sms",
            mapOf("body" to "Line1\nLine2\tTabbed 😀 & <tag> \"quoted\""),
        )
        writer.flush()

        val tokens = tokenize(out.toString(Charsets.UTF_8))
        val start = tokens[0] as XmlToken.StartElement
        assertEquals("Line1\nLine2\tTabbed 😀 & <tag> \"quoted\"", start.attributes["body"])
    }
}
