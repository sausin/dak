package app.dak.backup.xml

import java.io.InputStream
import java.nio.charset.Charset

/** One token from [XmlTokenizer]. */
sealed class XmlToken {
    data class StartElement(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean) : XmlToken()
    data class EndElement(val name: String) : XmlToken()
    data class Text(val text: String) : XmlToken()
    data object EndDocument : XmlToken()
}

/**
 * A small, dependency-free, streaming XML tokenizer good enough for the flat, attribute-heavy
 * documents this module deals with (SMS Backup & Restore XML). It is not a general-purpose XML
 * parser: no namespaces, no DTDs, no CDATA validation beyond passthrough, no external entities.
 *
 * `javax.xml.stream` (StAX) is JVM-only and unavailable on Android, and `org.xmlpull` is unavailable
 * on plain JVM (this module compiles for the JVM target, not Android), so this hand-written reader
 * is what both the app and the local unit-test JVM can share.
 *
 * Reads from [input] using [charset] (default UTF-8, honouring a `<?xml ... encoding="..."?>`
 * declaration when present and simple enough to detect ASCII-compatible).
 */
class XmlTokenizer(input: InputStream, private val charset: Charset = Charsets.UTF_8) {
    private val reader = input.bufferedReader(charset)
    private var pending = StringBuilder()
    private var done = false

    /** Reads the next token, or [XmlToken.EndDocument] once the stream is exhausted. */
    fun next(): XmlToken {
        if (done) return XmlToken.EndDocument
        // Skip a leading XML/processing-instruction declaration and any comments/doctype.
        skipMisc()
        val c = reader.read()
        if (c < 0) {
            done = true
            return XmlToken.EndDocument
        }
        return if (c == '<'.code) {
            readMarkup()
        } else {
            readText(c)
        }
    }

    private fun skipMisc() {
        while (true) {
            reader.mark(4096)
            val c = reader.read()
            if (c < 0) {
                reader.reset()
                return
            }
            if (c == '<'.code) {
                val c2 = reader.read()
                when {
                    c2 == '?'.code -> { skipUntil("?>"); continue }
                    c2 == '!'.code -> { skipUntil(">"); continue }
                    else -> { reader.reset(); return } // real element start; unread back to before '<'
                }
            } else if (c.toChar().isWhitespace()) {
                continue
            } else {
                reader.reset()
                return
            }
        }
    }

    private fun skipUntil(terminator: String) {
        val buf = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return
            buf.append(c.toChar())
            if (buf.length >= terminator.length && buf.endsWith(terminator)) return
        }
    }

    private fun readMarkup(): XmlToken {
        // '<' already consumed.
        var c = reader.read()
        if (c == '/'.code) {
            val name = StringBuilder()
            c = reader.read()
            while (c >= 0 && c != '>'.code) { name.append(c.toChar()); c = reader.read() }
            return XmlToken.EndElement(name.toString().trim())
        }
        if (c == '!'.code) { // comment or CDATA inside content; skip and recurse
            skipUntil(">")
            return next()
        }
        val name = StringBuilder()
        while (c >= 0 && !c.toChar().isWhitespace() && c != '>'.code && c != '/'.code) {
            name.append(c.toChar())
            c = reader.read()
        }
        val attrs = LinkedHashMap<String, String>()
        var selfClosing = false
        while (true) {
            while (c >= 0 && c.toChar().isWhitespace()) c = reader.read()
            if (c < 0) break
            if (c == '/'.code) {
                selfClosing = true
                c = reader.read()
                continue
            }
            if (c == '>'.code) break
            val attrName = StringBuilder()
            while (c >= 0 && c != '='.code && !c.toChar().isWhitespace()) { attrName.append(c.toChar()); c = reader.read() }
            while (c >= 0 && c.toChar().isWhitespace()) c = reader.read()
            if (c == '='.code) c = reader.read()
            while (c >= 0 && c.toChar().isWhitespace()) c = reader.read()
            val quote = c
            val attrValue = StringBuilder()
            if (quote == '"'.code || quote == '\''.code) {
                c = reader.read()
                while (c >= 0 && c != quote) { attrValue.append(c.toChar()); c = reader.read() }
                c = reader.read() // consume closing quote
            } else {
                // unquoted attribute value (not standard XML, tolerate anyway)
                while (c >= 0 && !c.toChar().isWhitespace() && c != '>'.code && c != '/'.code) { attrValue.append(c.toChar()); c = reader.read() }
            }
            if (attrName.isNotEmpty()) attrs[attrName.toString()] = XmlEntities.decode(attrValue.toString())
        }
        return XmlToken.StartElement(name.toString(), attrs, selfClosing)
    }

    private fun readText(firstChar: Int): XmlToken {
        val buf = StringBuilder()
        buf.append(firstChar.toChar())
        while (true) {
            reader.mark(1)
            val c = reader.read()
            if (c < 0 || c == '<'.code) {
                if (c == '<'.code) reader.reset()
                break
            }
            buf.append(c.toChar())
        }
        return XmlToken.Text(XmlEntities.decode(buf.toString()))
    }
}

/** Entity decoding for [XmlTokenizer]: the five predefined XML entities plus numeric references. */
object XmlEntities {
    fun decode(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') { out.append(c); i++; continue }
            val semi = text.indexOf(';', i + 1)
            if (semi < 0) { out.append(c); i++; continue } // stray '&', pass through
            val entity = text.substring(i + 1, semi)
            val decoded = decodeEntity(entity)
            if (decoded != null) {
                out.append(decoded)
                i = semi + 1
            } else {
                out.append(c) // unknown entity: pass through literally
                i++
            }
        }
        return out.toString()
    }

    private fun decodeEntity(entity: String): String? = when {
        entity == "amp" -> "&"
        entity == "lt" -> "<"
        entity == "gt" -> ">"
        entity == "apos" -> "'"
        entity == "quot" -> "\""
        entity.startsWith("#x") || entity.startsWith("#X") -> codePointOrNull(entity.substring(2), 16)
        entity.startsWith("#") -> codePointOrNull(entity.substring(1), 10)
        else -> null
    }

    private fun codePointOrNull(digits: String, radix: Int): String? {
        val cp = digits.toLongOrNull(radix) ?: return null
        if (cp < 0 || cp > 0x10FFFF) return null
        return try {
            // Handles both BMP characters and characters outside the BMP (encoded as surrogate pairs).
            String(Character.toChars(cp.toInt()))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
