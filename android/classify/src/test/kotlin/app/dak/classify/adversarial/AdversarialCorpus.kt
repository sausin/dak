package app.dak.classify.adversarial

import java.io.File

/**
 * Loader for the adversarial SMS corpus in `shared/adversarial/` (format: `shared/adversarial/README.md`).
 *
 * The corpus is plain tab-separated text so that people who do not write Kotlin can add scams they receive. The
 * parser is strict: an unknown expectation, a bad escape, a duplicate id or a line with the wrong number of fields is
 * a [ParseError] naming the file and line, never a silently skipped case.
 */
internal object AdversarialCorpus {

    /** Folder of the corpus, relative to the repository root. */
    const val DIR: String = "shared/adversarial"

    /** Longest body a generator may produce (the largest SMS/MMS text part we care to simulate, and then some). */
    const val MAX_DECODED_CHARS: Int = 2_000_000

    data class Entry(
        /** Path relative to [DIR], e.g. `in.tsv` or `pwn/unicode.tsv`. */
        val file: String,
        val line: Int,
        val id: String,
        val sender: String,
        val expects: List<Expect>,
        val tags: Set<String>,
        val body: String,
        /** ISO 3166 country of the receiving SIM, or null for "unknown region" (generic rules only). */
        val region: String?,
    ) {
        val where: String get() = "$file:$line [$id]"
        val knownGap: Boolean get() = KNOWN_GAP in tags
        val isContact: Boolean get() = CONTACT in tags

        /** Id of an earlier entry that arrived an hour before this one (`after:<id>`), for follow-up detection. */
        val after: String? get() = tags.firstOrNull { it.startsWith(AFTER) }?.removePrefix(AFTER)
    }

    data class ParseError(val file: String, val line: Int, val message: String) {
        override fun toString(): String = "$file:$line: $message"
    }

    data class Result(val entries: List<Entry>, val errors: List<ParseError>, val files: List<String>)

    /** What a line expects. See the README for the meaning of each. */
    sealed class Expect(val text: String) {
        override fun toString(): String = text

        object Scam : Expect("scam")
        object NotScam : Expect("not-scam")
        object LikelyScam : Expect("likely-scam")
        object Suspicious : Expect("suspicious")
        object FakeCredit : Expect("fake-credit")
        object NoFakeCredit : Expect("no-fake-credit")
        object Spam : Expect("spam")
        object NotSpam : Expect("not-spam")
        object LinkWarning : Expect("link-warning")
        object NoLinkWarning : Expect("no-link-warning")
        object Lookalike : Expect("lookalike")
        object NoOtp : Expect("no-otp")
        object NoTxn : Expect("no-txn")
        object SenderSpoof : Expect("sender-spoof")
        object NoCrash : Expect("no-crash")
        class Category(val category: app.dak.core.model.Category) : Expect("category:${category.name.lowercase()}")
        class Reason(val code: String) : Expect("reason:$code")
        class Label(val label: String) : Expect("label:$label")
        class NoLabel(val label: String) : Expect("no-label:$label")
        class Links(val count: Int) : Expect("links:$count")
        class LinkHost(val host: String) : Expect("link-host:$host")
        class Otp(val code: String) : Expect("otp:$code")
        class Txn(val credit: Boolean) : Expect("txn:${if (credit) "credit" else "debit"}")
        class Amount(val minor: Long) : Expect("amount:" + java.math.BigDecimal.valueOf(minor, 2).toPlainString())
    }

    const val KNOWN_GAP: String = "known-gap"
    const val CONTACT: String = "contact"
    const val AFTER: String = "after:"
    const val REGION: String = "region:"
    const val EMPTY_SENDER: String = "<empty>"

    private val SIMPLE_EXPECTS: Map<String, Expect> = listOf(
        Expect.Scam, Expect.NotScam, Expect.LikelyScam, Expect.Suspicious, Expect.FakeCredit, Expect.NoFakeCredit,
        Expect.Spam, Expect.NotSpam, Expect.LinkWarning, Expect.NoLinkWarning, Expect.Lookalike, Expect.NoOtp,
        Expect.NoTxn, Expect.SenderSpoof, Expect.NoCrash,
    ).associateBy { it.text }

    /** Every expectation keyword (for the README check), parameterised ones with a trailing `:`. */
    val EXPECT_KEYWORDS: Set<String> = SIMPLE_EXPECTS.keys +
        setOf("category:", "reason:", "label:", "no-label:", "links:", "link-host:", "otp:", "txn:", "amount:")

    private val ID = Regex("""[a-z0-9]+(?:-[a-z0-9]+)+""")
    private val TAG = Regex("""[a-z0-9]+(?:[-:][a-zA-Z0-9.]+)*""")
    private val DIRECTIVE = Regex("""#!\s*region\s*=\s*(\S+)\s*""")
    private val REGION_CODE = Regex("""[A-Z]{2}""")

    /** Finds the repository root (the folder holding [DIR]) from `-Ddak.repoRoot` or the working directory upwards. */
    fun repoRoot(): File {
        val start = File(System.getProperty("dak.repoRoot") ?: System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { it.parentFile }.firstOrNull { File(it, DIR).isDirectory }
            ?: error("$DIR not found above $start (set -Ddak.repoRoot=<repo>)")
    }

    /** Loads every `*.tsv` under [dir] (sorted by path, so reports are stable). */
    fun load(dir: File = File(repoRoot(), DIR)): Result {
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".tsv") }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        val entries = ArrayList<Entry>()
        val errors = ArrayList<ParseError>()
        for (file in files) {
            val rel = file.relativeTo(dir).invariantSeparatorsPath
            parse(rel, file.readText(Charsets.UTF_8), entries, errors)
        }
        val seen = HashMap<String, Entry>()
        for (e in entries) {
            val previous = seen.putIfAbsent(e.id, e)
            if (previous != null) errors += ParseError(e.file, e.line, "duplicate id '${e.id}' (first used at ${previous.file}:${previous.line})")
        }
        for (e in entries) {
            val after = e.after ?: continue
            val target = seen[after]
            if (target == null || target.file != e.file || target.line >= e.line) {
                errors += ParseError(e.file, e.line, "after:$after must name an earlier entry in the same file")
            }
        }
        return Result(entries, errors, files.map { it.relativeTo(dir).invariantSeparatorsPath })
    }

    /** Parses one file's [text] into [entries], adding problems to [errors]. */
    fun parse(file: String, text: String, entries: MutableList<Entry>, errors: MutableList<ParseError>) {
        var region: String? = null
        var regionSeen = false
        val stem = file.substringAfterLast('/').removeSuffix(".tsv")
        text.removePrefix("\uFEFF").split('\n').forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank()) return@forEachIndexed
            if (line.startsWith("#!")) {
                val m = DIRECTIVE.matchEntire(line)
                if (m == null || regionSeen) {
                    errors += ParseError(file, lineNo, if (regionSeen) "second '#! region=' directive" else "bad directive (expected '#! region=XX' or '#! region=none')")
                    return@forEachIndexed
                }
                val value = m.groupValues[1]
                region = when {
                    value == "none" -> null
                    REGION_CODE.matches(value) -> value
                    else -> {
                        errors += ParseError(file, lineNo, "region must be an upper-case ISO 3166 code or 'none', got '$value'")
                        null
                    }
                }
                regionSeen = true
                return@forEachIndexed
            }
            if (line.trimStart().startsWith("#")) return@forEachIndexed
            if (!regionSeen) {
                errors += ParseError(file, lineNo, "entry before the '#! region=' directive")
                regionSeen = true // report once
            }
            val fields = line.split('\t')
            if (fields.size != 5) {
                errors += ParseError(file, lineNo, "expected 5 tab-separated fields (id, sender, expect, tags, body), got ${fields.size}")
                return@forEachIndexed
            }
            val (id, senderField, expectField, tagField, bodyField) = fields
            val problems = ArrayList<String>()
            // Invisible characters must be spelled as escapes so reviewers can see them in a diff.
            line.firstOrNull(::mustBeEscaped)?.let { problems += "raw invisible/control character U+%04X: write it as \\u%04X".format(it.code, it.code) }
            if (!ID.matches(id)) problems += "bad id '$id' (lower-case letters, digits and dashes, e.g. '$stem-kyc-01')"
            if (!id.startsWith("$stem-")) problems += "id '$id' must start with the file name '$stem-'"
            val expects = ArrayList<Expect>()
            if (expectField.isBlank()) problems += "empty expect field"
            for (token in expectField.split(',')) {
                val parsed = parseExpect(token.trim())
                if (parsed == null) problems += "unknown expectation '${token.trim()}'" else expects += parsed
            }
            val tags = LinkedHashSet<String>()
            if (tagField != "-") {
                for (tag in tagField.split(',')) {
                    val t = tag.trim()
                    if (!TAG.matches(t)) problems += "bad tag '$t' (lower-case words joined by '-' or ':'; '-' for none)" else tags += t
                }
            }
            var lineRegion = region
            tags.filter { it.startsWith(REGION) }.forEach { tag ->
                val code = tag.removePrefix(REGION)
                if (code == "none") lineRegion = null
                else if (REGION_CODE.matches(code.uppercase())) lineRegion = code.uppercase()
                else problems += "bad region tag '$tag'"
            }
            val sender = if (senderField == EMPTY_SENDER) "" else decodeOrNull(senderField, "sender", problems)
            val body = decodeOrNull(bodyField, "body", problems)
            if (sender != null && senderField != EMPTY_SENDER && sender.isEmpty()) problems += "empty sender (write $EMPTY_SENDER)"
            if (body != null && body.isEmpty()) problems += "empty body"
            if (problems.isNotEmpty() || sender == null || body == null) {
                problems.forEach { errors += ParseError(file, lineNo, it) }
                return@forEachIndexed
            }
            entries += Entry(file, lineNo, id, sender, expects, tags, body, lineRegion)
        }
        if (!regionSeen) errors += ParseError(file, 1, "missing '#! region=XX' directive")
    }

    /** Characters that may only appear escaped in a corpus file (controls other than the tab separators, format, separators). */
    fun mustBeEscaped(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONTROL -> c != '\t'
        Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        Character.SPACE_SEPARATOR -> c != ' '
        else -> false
    }

    private fun decodeOrNull(field: String, what: String, problems: MutableList<String>): String? = try {
        decode(field)
    } catch (e: IllegalArgumentException) {
        problems += "$what: ${e.message}"
        null
    }

    fun parseExpect(token: String): Expect? {
        SIMPLE_EXPECTS[token]?.let { return it }
        val key = token.substringBefore(':', "")
        val value = token.substringAfter(':', "")
        if (key.isEmpty() || value.isEmpty()) return null
        return when (key) {
            "category" -> app.dak.core.model.Category.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }?.let { Expect.Category(it) }
            "reason" -> app.dak.classify.scam.ScamReason.fromCode(value)?.let { Expect.Reason(value) }
            "label" -> Expect.Label(value)
            "no-label" -> Expect.NoLabel(value)
            "links" -> value.toIntOrNull()?.takeIf { it >= 0 }?.let { Expect.Links(it) }
            "link-host" -> Expect.LinkHost(value.lowercase())
            "otp" -> value.takeIf { v -> v.length in 3..10 && v.all { it.isLetterOrDigit() && it.code < 0x80 } }?.let { Expect.Otp(it) }
            "txn" -> when (value) {
                "credit" -> Expect.Txn(true)
                "debit" -> Expect.Txn(false)
                else -> null
            }
            "amount" -> value.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }
                ?.let { Expect.Amount(it.movePointRight(2).longValueExact()) }
            else -> null
        }
    }

    /**
     * Decodes a field: escapes (`\n \r \t \0 \\ \{ \uXXXX \u{X…}`) and generators (`{repeat:"text":N}`). Any other
     * `{` is literal, so `{firstName}` and `{0}` need no escaping. Throws [IllegalArgumentException] on a bad escape,
     * a malformed generator or an oversized result.
     */
    fun decode(field: String): String {
        val out = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val c = field[i]
            when {
                c == '\\' -> i = escape(field, i, out)
                c == '{' && field.startsWith(GENERATOR, i) -> i = generator(field, i, out)
                else -> {
                    out.append(c)
                    i++
                }
            }
            require(out.length <= MAX_DECODED_CHARS) { "decoded text is longer than $MAX_DECODED_CHARS characters" }
        }
        return out.toString()
    }

    private const val GENERATOR = "{repeat:"

    /** Decodes the escape at [start] (a backslash) into [out]; returns the index after it. */
    private fun escape(s: String, start: Int, out: StringBuilder, inQuotes: Boolean = false): Int {
        require(start + 1 < s.length) { "dangling '\\' at column ${start + 1}" }
        return when (val e = s[start + 1]) {
            'n' -> { out.append('\n'); start + 2 }
            'r' -> { out.append('\r'); start + 2 }
            't' -> { out.append('\t'); start + 2 }
            '0' -> { out.append('\u0000'); start + 2 }
            '\\' -> { out.append('\\'); start + 2 }
            '{' -> { out.append('{'); start + 2 }
            '"' -> {
                require(inQuotes) { "'\\\"' is only needed inside a generator's quotes (column ${start + 1})" }
                out.append('"')
                start + 2
            }
            'u' -> {
                if (start + 2 < s.length && s[start + 2] == '{') {
                    val close = s.indexOf('}', start + 3)
                    require(close > start + 3 && close - (start + 3) <= 6) { "bad \\u{…} escape at column ${start + 1}" }
                    val cp = s.substring(start + 3, close).toIntOrNull(16)
                    require(cp != null && cp in 0..0x10FFFF) { "bad code point in \\u{…} at column ${start + 1}" }
                    out.appendCodePoint(cp)
                    close + 1
                } else {
                    require(start + 6 <= s.length) { "\\u needs 4 hex digits at column ${start + 1}" }
                    val hex = s.substring(start + 2, start + 6)
                    val v = hex.toIntOrNull(16)
                    require(v != null && hex.all { it.isLetterOrDigit() }) { "bad \\u$hex escape at column ${start + 1}" }
                    out.append(v.toChar()) // may be a lone surrogate on purpose
                    start + 6
                }
            }
            else -> throw IllegalArgumentException("unknown escape '\\$e' at column ${start + 1}")
        }
    }

    /** Decodes `{repeat:"text":N}` at [start] into [out]; returns the index after the closing brace. */
    private fun generator(s: String, start: Int, out: StringBuilder): Int {
        var i = start + GENERATOR.length
        require(i < s.length && s[i] == '"') { "generator at column ${start + 1}: expected '\"' after '{repeat:'" }
        i++
        val unit = StringBuilder()
        while (true) {
            require(i < s.length) { "generator at column ${start + 1}: unterminated string" }
            val c = s[i]
            if (c == '"') break
            if (c == '\\') i = escape(s, i, unit, inQuotes = true) else { unit.append(c); i++ }
        }
        i++ // closing quote
        require(i < s.length && s[i] == ':') { "generator at column ${start + 1}: expected ':' after the string" }
        i++
        val digitsStart = i
        while (i < s.length && s[i] in '0'..'9') i++
        val count = s.substring(digitsStart, i).toIntOrNull()
        require(count != null) { "generator at column ${start + 1}: expected a count" }
        require(i < s.length && s[i] == '}') { "generator at column ${start + 1}: expected '}' after the count" }
        require(unit.isNotEmpty()) { "generator at column ${start + 1}: empty string" }
        require(count.toLong() * unit.length <= MAX_DECODED_CHARS) { "generator at column ${start + 1}: result longer than $MAX_DECODED_CHARS characters" }
        repeat(count) { out.append(unit) }
        return i + 1
    }
}
