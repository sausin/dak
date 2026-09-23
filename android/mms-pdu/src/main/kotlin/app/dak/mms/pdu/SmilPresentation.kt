package app.dak.mms.pdu

/**
 * Receive-side SMIL: reads the slide order of a received MMS presentation so the parts can be shown in the order the
 * sender composed them (OMA MMS-CONF / 3GPP TS 26.140 presentation part), falling back to PDU part order.
 *
 * The SMIL part is attacker-controlled, so this is deliberately not an XML parser:
 * - a hand-rolled, single-pass tokenizer over at most [MAX_SMIL_CHARS] characters, with [MAX_ELEMENTS] elements and
 *   [MAX_DEPTH] nesting levels; anything beyond a limit, or structurally broken, yields null (use part order);
 * - DOCTYPE / internal subsets, processing instructions, comments and CDATA are skipped unread. No entity is ever
 *   defined or expanded except the five predefined XML entities and numeric character references in attribute
 *   values, so there is no XXE, no entity expansion blow-up and no external fetch;
 * - only `src` attributes of media elements are kept, and they are only ever *compared* with the message's own part
 *   headers ([order]): a `src` that names a URL, a file path or anything else that is not one of the parts simply
 *   matches nothing. Timing, regions, layout, `begin`/`end`, scripts and links are ignored.
 */
object SmilPresentation {

    /** Longest SMIL text read (a real presentation is a few hundred bytes to a few KB). */
    const val MAX_SMIL_CHARS: Int = 64 * 1024

    /** Most elements (start tags) read before giving up. */
    const val MAX_ELEMENTS: Int = 2048

    /** Deepest element nesting accepted. */
    const val MAX_DEPTH: Int = 32

    /** Longest `src` value kept (longer ones cannot name one of our parts, see [MmsLimits.MAX_TOKEN_CHARS]). */
    private const val MAX_SRC_CHARS: Int = MmsLimits.MAX_TOKEN_CHARS

    /** One media reference: [element] is the lower-case local element name (`img`, `text`, `video`, …). */
    data class Ref(val src: String, val element: String)

    /** Slides in presentation order, each with its media references in document order. */
    data class Slides(val slides: List<List<Ref>>) {
        val refs: List<Ref> get() = slides.flatten()
    }

    /** Media elements whose `src` references a part. */
    private val MEDIA = setOf("img", "image", "text", "textstream", "video", "audio", "ref", "animation")

    /**
     * Slides of [smil], or null when it is missing, too large, malformed, or references nothing. Never throws.
     *
     * Each `<par>` directly in `<body>` (or in a `<seq>`) is one slide; nested `<par>`/`<seq>` inside a slide stay
     * in that slide. A media element outside any `<par>` is a slide of its own.
     */
    fun parse(smil: String?): Slides? {
        if (smil.isNullOrEmpty() || smil.length > MAX_SMIL_CHARS) return null
        return try {
            Tokenizer(smil).run()
        } catch (e: RuntimeException) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    /** Header fields of one part, as used to resolve SMIL references. */
    data class PartKey(
        val contentId: String? = null,
        val contentLocation: String? = null,
        val name: String? = null,
        val fileName: String? = null,
    )

    /**
     * Indices of [parts] in presentation order: parts referenced by [smil] first, in slide order (each part once, at
     * its first reference), then every part it does not reference, in their original order. With no usable SMIL, or
     * when no reference resolves, the original order (`0 until parts.size`). Never throws.
     *
     * A `src` of `cid:<id>` matches a part's Content-ID (angle brackets and case ignored); any other `src` matches a
     * part's Content-Location, then its name, file name or Content-ID, exactly first and then ignoring case.
     */
    fun order(smil: String?, parts: List<PartKey>): List<Int> {
        val identity = parts.indices.toList()
        val slides = parse(smil) ?: return identity
        val used = BooleanArray(parts.size)
        val out = ArrayList<Int>(parts.size)
        for (ref in slides.refs) {
            val index = resolve(ref.src, parts, used) ?: continue
            used[index] = true
            out += index
        }
        if (out.isEmpty()) return identity
        for (i in parts.indices) if (!used[i]) out += i
        return out
    }

    /** Index of the unused part [src] names, or null. */
    internal fun resolve(src: String, parts: List<PartKey>, used: BooleanArray): Int? {
        val raw = src.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("cid:", ignoreCase = true)) {
            val id = stripAngles(percentDecode(raw.substring(4)))
            if (id.isEmpty()) return null
            return firstUnused(parts, used) { p -> p.contentId?.let { stripAngles(it).equals(id, ignoreCase = true) } == true }
        }
        val candidates = linkedSetOf(raw, percentDecode(raw), raw.removePrefix("./"))
        for (ignoreCase in booleanArrayOf(false, true)) {
            for (c in candidates) {
                firstUnused(parts, used) { p -> p.contentLocation?.equals(c, ignoreCase) == true }?.let { return it }
            }
            for (c in candidates) {
                firstUnused(parts, used) { p ->
                    p.name?.equals(c, ignoreCase) == true || p.fileName?.equals(c, ignoreCase) == true ||
                        p.contentId?.let { stripAngles(it).equals(c, ignoreCase) } == true
                }?.let { return it }
            }
        }
        return null
    }

    private inline fun firstUnused(parts: List<PartKey>, used: BooleanArray, match: (PartKey) -> Boolean): Int? {
        for (i in parts.indices) if (!used[i] && match(parts[i])) return i
        return null
    }

    private fun stripAngles(s: String): String = s.trim().removePrefix("<").removeSuffix(">").trim()

    /** `%XX` decoding of ASCII escapes only; anything malformed is kept literally. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null && v in 0x20..0x7E) {
                    sb.append(v.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** Single-pass tokenizer; throws [IllegalStateException] on anything malformed (turned into null by [parse]). */
    private class Tokenizer(private val s: String) {
        private var i = 0
        private val stack = ArrayList<String>()
        private var elements = 0

        /** Depth (in [stack]) of the `<body>` element, or -1 outside it. */
        private var bodyDepth = -1

        /** Depth of the `<par>` that opened the current slide, or -1 when not inside a slide. */
        private var slideDepth = -1
        private val slides = ArrayList<MutableList<Ref>>()
        private var refs = 0

        fun run(): Slides? {
            while (i < s.length) {
                val lt = s.indexOf('<', i)
                if (lt < 0) break
                i = lt
                when {
                    s.startsWith("<!--", i) -> skipPast("-->", i + 4)
                    s.startsWith("<![CDATA[", i) -> skipPast("]]>", i + 9)
                    s.startsWith("<!", i) -> skipDeclaration()
                    s.startsWith("<?", i) -> skipPast("?>", i + 2)
                    s.startsWith("</", i) -> endTag()
                    else -> startTag()
                }
            }
            check(stack.isEmpty()) { "unclosed element" }
            val result = slides.filter { it.isNotEmpty() }
            return if (result.isEmpty()) null else Slides(result)
        }

        private fun skipPast(end: String, from: Int) {
            val at = s.indexOf(end, from)
            check(at >= 0) { "unterminated markup" }
            i = at + end.length
        }

        /** `<!DOCTYPE …>` with an optional `[ … ]` internal subset: skipped, never interpreted. */
        private fun skipDeclaration() {
            var j = i + 2
            var brackets = 0
            var quote = '\u0000'
            while (j < s.length) {
                val c = s[j]
                when {
                    quote != '\u0000' -> if (c == quote) quote = '\u0000'
                    c == '"' || c == '\'' -> quote = c
                    c == '[' -> brackets++
                    c == ']' -> brackets--
                    c == '>' && brackets <= 0 -> {
                        i = j + 1
                        return
                    }
                }
                j++
            }
            error("unterminated declaration")
        }

        private fun endTag() {
            var j = i + 2
            val name = readName(j).also { j += it.length }
            check(name.isNotEmpty()) { "empty end tag" }
            j = skipSpace(j)
            check(j < s.length && s[j] == '>') { "bad end tag" }
            i = j + 1
            check(stack.isNotEmpty() && stack.last() == localName(name)) { "mismatched end tag" }
            close()
        }

        private fun startTag() {
            var j = i + 1
            val qname = readName(j)
            check(qname.isNotEmpty()) { "bad start tag" }
            j += qname.length
            val name = localName(qname)
            check(++elements <= MAX_ELEMENTS) { "too many elements" }
            var src: String? = null
            var selfClosing = false
            while (true) {
                j = skipSpace(j)
                check(j < s.length) { "unterminated start tag" }
                val c = s[j]
                if (c == '>') {
                    j++
                    break
                }
                if (c == '/') {
                    check(j + 1 < s.length && s[j + 1] == '>') { "bad self-closing tag" }
                    selfClosing = true
                    j += 2
                    break
                }
                val attr = readName(j)
                check(attr.isNotEmpty()) { "bad attribute" }
                j = skipSpace(j + attr.length)
                check(j < s.length && s[j] == '=') { "attribute without value" }
                j = skipSpace(j + 1)
                check(j < s.length && (s[j] == '"' || s[j] == '\'')) { "unquoted attribute" }
                val quote = s[j]
                val end = s.indexOf(quote, j + 1)
                check(end >= 0) { "unterminated attribute" }
                val rawValue = s.substring(j + 1, end)
                check('<' !in rawValue) { "'<' in attribute" }
                if (localName(attr) == "src" && rawValue.length <= MAX_SRC_CHARS * 6) src = decodeEntities(rawValue)
                j = end + 1
            }
            i = j
            open(name, src)
            if (selfClosing) close()
        }

        private fun open(name: String, src: String?) {
            stack += name
            check(stack.size <= MAX_DEPTH) { "nesting too deep" }
            val depth = stack.size
            when {
                name == "body" && bodyDepth == -1 -> bodyDepth = depth
                bodyDepth < 0 -> Unit // head, layout, meta …: nothing to present
                name == "par" && slideDepth < 0 -> {
                    slideDepth = depth
                    slides.add(ArrayList())
                }
                name in MEDIA -> {
                    val ref = src?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SRC_CHARS } ?: return
                    check(++refs <= MmsLimits.MAX_PARTS * 4) { "too many references" }
                    if (slideDepth < 0) slides.add(mutableListOf(Ref(ref, name))) else slides.last().add(Ref(ref, name))
                }
            }
        }

        private fun close() {
            val depth = stack.size
            if (depth == slideDepth) slideDepth = -1
            if (depth == bodyDepth) bodyDepth = -2 // a second <body> is ignored
            stack.removeAt(stack.size - 1)
        }

        private fun readName(from: Int): String {
            var j = from
            while (j < s.length) {
                val c = s[j]
                if (c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == ':') j++ else break
                if (j - from > 64) error("name too long")
            }
            return s.substring(from, j)
        }

        private fun skipSpace(from: Int): Int {
            var j = from
            while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\n' || s[j] == '\r')) j++
            return j
        }

        private fun localName(qname: String): String = qname.substringAfterLast(':').lowercase()

        /** The five predefined entities and numeric references; anything else is kept literally (never expanded). */
        private fun decodeEntities(v: String): String {
            if ('&' !in v) return v
            val sb = StringBuilder(v.length)
            var k = 0
            while (k < v.length) {
                val c = v[k]
                if (c == '&') {
                    val semi = v.indexOf(';', k + 1)
                    if (semi in (k + 2)..(k + 10)) {
                        val entity = v.substring(k + 1, semi)
                        val decoded: String? = when {
                            entity == "amp" -> "&"
                            entity == "lt" -> "<"
                            entity == "gt" -> ">"
                            entity == "quot" -> "\""
                            entity == "apos" -> "'"
                            entity.startsWith("#x") || entity.startsWith("#X") -> codePoint(entity.substring(2).toIntOrNull(16))
                            entity.startsWith("#") -> codePoint(entity.substring(1).toIntOrNull())
                            else -> null
                        }
                        if (decoded != null) {
                            sb.append(decoded)
                            k = semi + 1
                            continue
                        }
                    }
                }
                sb.append(c)
                k++
            }
            return sb.toString()
        }

        /** A printable code point as a string; control characters and invalid values are refused (null). */
        private fun codePoint(cp: Int?): String? {
            if (cp == null || cp < 0x20 || cp > 0x10FFFF || cp in 0xD800..0xDFFF || cp in 0x7F..0x9F) return null
            return String(Character.toChars(cp))
        }
    }
}
