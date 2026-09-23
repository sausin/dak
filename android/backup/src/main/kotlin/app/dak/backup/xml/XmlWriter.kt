package app.dak.backup.xml

import java.io.OutputStream
import java.io.Writer
import java.nio.charset.Charset

/**
 * A tiny streaming, escaping XML writer — the write side of [XmlTokenizer]. Emits flat,
 * attribute-heavy XML (self-closing elements, no namespaces) which is all the SMS Backup & Restore
 * format needs.
 */
class XmlWriter(output: OutputStream, charset: Charset = Charsets.UTF_8) {
    private val writer: Writer = output.bufferedWriter(charset)

    fun writeDeclaration(encoding: String = "UTF-8") {
        writer.write("<?xml version=\"1.0\" encoding=\"$encoding\" standalone=\"yes\"?>\n")
    }

    /** Writes `<name attr="value" ...>` with no closing, for an element that will hold children. */
    fun startElement(name: String, attributes: Map<String, String?> = emptyMap()) {
        writer.write("<")
        writer.write(name)
        writeAttributes(attributes)
        writer.write(">")
    }

    fun endElement(name: String) {
        writer.write("</")
        writer.write(name)
        writer.write(">")
    }

    /** Writes `<name attr="value" .../>`, a self-closing leaf element. */
    fun selfClosingElement(name: String, attributes: Map<String, String?> = emptyMap()) {
        writer.write("<")
        writer.write(name)
        writeAttributes(attributes)
        writer.write(" />")
    }

    fun raw(text: String) {
        writer.write(text)
    }

    fun flush() = writer.flush()

    private fun writeAttributes(attributes: Map<String, String?>) {
        for ((k, v) in attributes) {
            writer.write(" ")
            writer.write(k)
            writer.write("=\"")
            writer.write(escapeAttribute(v ?: "null"))
            writer.write("\"")
        }
    }

    companion object {
        /** Escapes text for use inside an XML attribute value (double-quoted). */
        fun escapeAttribute(value: String): String {
            val sb = StringBuilder(value.length + 16)
            for (ch in value) {
                when {
                    ch == '&' -> sb.append("&amp;")
                    ch == '<' -> sb.append("&lt;")
                    ch == '>' -> sb.append("&gt;")
                    ch == '"' -> sb.append("&quot;")
                    ch == '\r' -> sb.append("&#13;")
                    ch == '\n' -> sb.append("&#10;")
                    ch == '\t' -> sb.append("&#9;")
                    ch.code in 0x00..0x1F -> sb.append("&#").append(ch.code).append(';') // control chars: not valid raw XML
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
