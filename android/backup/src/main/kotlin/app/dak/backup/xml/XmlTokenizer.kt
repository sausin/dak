package app.dak.backup.xml

import app.dak.backup.format.ArchiveLimitException
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
 *
 * Imported files are untrusted: DTDs are skipped and only predefined/numeric entities are decoded (so entity
 * expansion attacks such as "billion laughs" and external entities cannot happen), and element names, attribute
 * counts and values, and text runs are length-capped ([XmlLimits]); exceeding a cap throws
 * [ArchiveLimitException] instead of exhausting memory.
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
                    c2 == '!'.code -> { skipDeclaration(); continue }
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

    /** Skips input up to and including [terminator] without buffering what is skipped. */
    private fun skipUntil(terminator: String) {
        var matched = 0
        while (true) {
            val c = reader.read()
            if (c < 0) return
            matched = when {
                c == terminator[matched].code -> matched + 1
                c == terminator[0].code -> 1
                else -> 0
            }
            if (matched == terminator.length) return
        }
    }

    /**
     * Skips a `<!…>` construct after `<!`: comments up to `-->`, CDATA up to `]]>`, and DOCTYPE including an
     * internal subset in `[...]` (whose entity declarations are deliberately ignored, never expanded).
     */
    private fun skipDeclaration() {
        reader.mark(8)
        val head = CharArray(7)
        var n = 0
        while (n < head.size) {
            val c = reader.read()
            if (c < 0) break
            head[n++] = c.toChar()
        }
        val start = String(head, 0, n)
        when {
            start.startsWith("--") -> { reader.reset(); reader.skip(2); skipUntil("-->") }
            start.startsWith("[CDATA[") -> skipUntil("]]>")
            else -> {
                reader.reset()
                var depth = 0
                while (true) {
                    val c = reader.read()
                    if (c < 0) return
                    when (c) {
                        '['.code -> depth++
                        ']'.code -> if (depth > 0) depth--
                        '>'.code -> if (depth == 0) return
                    }
                }
            }
        }
    }

    private fun readMarkup(): XmlToken {
        // '<' already consumed.
        var c = reader.read()
        if (c == '/'.code) {
            val name = StringBuilder()
            c = reader.read()
            while (c >= 0 && c != '>'.code) { appendLimited(name, c, XmlLimits.MAX_NAME_CHARS, "element name"); c = reader.read() }
            return XmlToken.EndElement(name.toString().trim())
        }
        if (c == '!'.code) { // comment or CDATA inside content; skip and continue
            skipDeclaration()
            return next()
        }
        val name = StringBuilder()
        while (c >= 0 && !c.toChar().isWhitespace() && c != '>'.code && c != '/'.code) {
            appendLimited(name, c, XmlLimits.MAX_NAME_CHARS, "element name")
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
            while (c >= 0 && c != '='.code && !c.toChar().isWhitespace()) {
                appendLimited(attrName, c, XmlLimits.MAX_NAME_CHARS, "attribute name")
                c = reader.read()
            }
            while (c >= 0 && c.toChar().isWhitespace()) c = reader.read()
            if (c == '='.code) c = reader.read()
            while (c >= 0 && c.toChar().isWhitespace()) c = reader.read()
            val quote = c
            val attrValue = StringBuilder()
            if (quote == '"'.code || quote == '\''.code) {
                c = reader.read()
                while (c >= 0 && c != quote) { appendLimited(attrValue, c, XmlLimits.MAX_ATTRIBUTE_CHARS, "attribute value"); c = reader.read() }
                c = reader.read() // consume closing quote
            } else {
                // unquoted attribute value (not standard XML, tolerate anyway)
                while (c >= 0 && !c.toChar().isWhitespace() && c != '>'.code && c != '/'.code) {
                    appendLimited(attrValue, c, XmlLimits.MAX_ATTRIBUTE_CHARS, "attribute value")
                    c = reader.read()
                }
            }
            if (attrName.isNotEmpty()) {
                attrs[attrName.toString()] = XmlEntities.decode(attrValue.toString())
                if (attrs.size > XmlLimits.MAX_ATTRIBUTES) throw ArchiveLimitException("more than ${XmlLimits.MAX_ATTRIBUTES} attributes")
            }
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
            appendLimited(buf, c, XmlLimits.MAX_TEXT_CHARS, "text")
        }
        return XmlToken.Text(XmlEntities.decode(buf.toString()))
    }

    private fun appendLimited(sb: StringBuilder, c: Int, limit: Int, what: String) {
        if (sb.length >= limit) throw ArchiveLimitException("XML $what longer than $limit characters")
        sb.append(c.toChar())
    }
}

/** Size caps applied by [XmlTokenizer]. */
object XmlLimits {
    const val MAX_NAME_CHARS: Int = 256
    const val MAX_ATTRIBUTES: Int = 256

    /** Base64 `data` attributes carry whole MMS attachments: ~24 MiB of base64 is ~18 MiB of media. */
    const val MAX_ATTRIBUTE_CHARS: Int = 24 * 1024 * 1024
    const val MAX_TEXT_CHARS: Int = 1024 * 1024
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
        return replaceLoneSurrogates(out)
    }

    /**
     * Numeric references can encode half a surrogate pair (SMS Backup & Restore writes emoji as two decimal
     * references, which recombine here); an unpaired half is replaced with U+FFFD so no malformed UTF-16 reaches
     * the provider or the UI.
     */
    private fun replaceLoneSurrogates(sb: StringBuilder): String {
        var i = 0
        while (i < sb.length) {
            val c = sb[i]
            if (Character.isHighSurrogate(c) && i + 1 < sb.length && Character.isLowSurrogate(sb[i + 1])) {
                i += 2
                continue
            }
            if (Character.isSurrogate(c)) sb.setCharAt(i, '\uFFFD')
            i++
        }
        return sb.toString()
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
        if (cp <= 0 || cp > 0x10FFFF) return null // &#0; is not a character
        return try {
            // Handles both BMP characters and characters outside the BMP (encoded as surrogate pairs).
            String(Character.toChars(cp.toInt()))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
