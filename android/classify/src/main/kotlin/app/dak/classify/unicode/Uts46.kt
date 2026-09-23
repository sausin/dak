package app.dak.classify.unicode

import java.text.Normalizer

/**
 * UTS #46 (Unicode IDNA Compatibility Processing) with nontransitional (IDNA2008) semantics: the mapping table is
 * generated from `IdnaMappingTable.txt` (version in `idna-mapping.txt`'s header), so `faß.de` stays `xn--fa-hia.de`
 * instead of IDNA2003's `fass.de`, and newer Unicode characters get their real status.
 *
 * Processing (UTS #46 §4): map every code point (valid / mapped / deviation kept / ignored dropped / disallowed an
 * error), normalise to NFC, split at `.`, decode `xn--` labels with [Punycode], then check each label against the
 * validity criteria (§4.1): NFC, hyphen rules (optional), no leading combining mark, only valid code points, STD3 ASCII
 * rules (optional), CONTEXTJ for ZWJ/ZWNJ (RFC 5892 A.1/A.2) and the RFC 5893 Bidi rule. CONTEXTO rules are not
 * checked (optional for clients). Java's own Unicode data supplies NFC, General_Category and Bidi_Class; it can be
 * older than the table, which only makes characters newer than the runtime fail validation (the safe direction).
 *
 * Errors never throw: [Result.errors] lists the failed checks (IdnaTestV2-style codes, for tests and diagnostics) and
 * [Result.value] still carries the best-effort output.
 */
public object Uts46 {

    /** UTS #46 processing flags. Transitional processing is not offered: IDNA2008 and every current browser use none. */
    public data class Options(
        val checkHyphens: Boolean = false,
        val checkBidi: Boolean = true,
        val checkJoiners: Boolean = true,
        val useStd3AsciiRules: Boolean = false,
        val verifyDnsLength: Boolean = false,
        /** Empty labels other than a trailing root label are errors (IdnaTestV2's X4_2); browsers allow them. */
        val rejectEmptyLabels: Boolean = false,
    )

    /** What the WHATWG URL standard (every browser a link opens in) uses for host names: "domain to ASCII", beStrict=false. */
    public val BROWSER: Options = Options()

    /** Everything on: IDNA2008-style registration checks, used to decide whether a host is fit to show in Unicode. */
    public val STRICT: Options =
        Options(checkHyphens = true, useStd3AsciiRules = true, verifyDnsLength = true, rejectEmptyLabels = true)

    public class Result(public val value: String, public val errors: Set<String>) {
        public val ok: Boolean get() = errors.isEmpty()
        override fun toString(): String = if (ok) value else "$value $errors"
    }

    /** Longest input processed; longer hosts are rejected outright (a DNS name is at most 253 octets). */
    public const val MAX_INPUT: Int = 1_024

    public fun toAscii(domain: String, options: Options = BROWSER): Result {
        val processed = process(domain, options)
        val errors = processed.errors.toMutableSet()
        val labels = processed.labels.map { label ->
            if (label.all { it.code < 0x80 }) {
                label
            } else {
                val encoded = Punycode.encode(label)
                if (encoded == null) {
                    errors += "A3"
                    label
                } else {
                    "xn--$encoded"
                }
            }
        }
        if (options.verifyDnsLength) {
            // The total excludes a trailing root dot; every label, the empty root label included, must be 1..63 long.
            val withoutRoot = if (labels.size > 1 && labels.last().isEmpty()) labels.dropLast(1) else labels
            val total = withoutRoot.sumOf { it.length } + withoutRoot.size - 1
            if (total !in 1..253) errors += "A4_1"
            if (labels.any { it.length !in 1..63 }) errors += "A4_2"
        }
        return Result(labels.joinToString("."), errors)
    }

    public fun toUnicode(domain: String, options: Options = BROWSER): Result {
        val processed = process(domain, options)
        return Result(processed.labels.joinToString("."), processed.errors)
    }

    private class Processed(val labels: List<String>, val errors: Set<String>)

    private fun process(domain: String, options: Options): Processed {
        val errors = LinkedHashSet<String>()
        if (domain.length > MAX_INPUT) return Processed(listOf(domain), setOf("P1"))
        val labels = ArrayList<String>()

        // Fast path: an ASCII domain with no xn-- label only needs lower-casing (every other ASCII character is valid
        // or, under STD3 rules, checked below). Keeps the common case from loading the table.
        val mapped: String = if (domain.all { it.code < 0x80 }) {
            domain.lowercase()
        } else {
            val sb = StringBuilder(domain.length)
            var i = 0
            while (i < domain.length) {
                val cp = domain.codePointAt(i)
                i += Character.charCount(cp)
                if (cp in 0xD800..0xDFFF) {
                    errors += "P1"
                    sb.append('\uFFFD')
                    continue
                }
                when (UnicodeTables.idnaStatus(cp)) {
                    UnicodeTables.IdnaStatus.VALID, UnicodeTables.IdnaStatus.DEVIATION -> sb.appendCodePoint(cp)
                    UnicodeTables.IdnaStatus.MAPPED -> sb.append(UnicodeTables.idnaMapping(cp))
                    UnicodeTables.IdnaStatus.IGNORED -> Unit
                    UnicodeTables.IdnaStatus.DISALLOWED -> {
                        errors += "P1"
                        sb.appendCodePoint(cp)
                    }
                }
            }
            Normalizer.normalize(sb, Normalizer.Form.NFC)
        }

        val parts = mapped.split('.')
        for ((index, raw) in parts.withIndex()) {
            var label = raw
            if (label.isEmpty() && options.rejectEmptyLabels && (index < parts.size - 1 || parts.size == 1)) errors += "X4_2"
            if (label.startsWith("xn--")) {
                if (label.any { it.code >= 0x80 }) {
                    errors += "P4"
                } else {
                    val decoded = Punycode.decode(label.substring(4))
                    if (decoded == null || decoded.isEmpty() || decoded.all { it.code < 0x80 }) {
                        errors += "P4"
                    } else {
                        label = decoded
                        validate(label, options, errors, fromPunycode = true)
                    }
                }
            } else {
                validate(label, options, errors, fromPunycode = false)
            }
            labels += label
        }
        if (options.checkBidi) checkBidi(labels, errors)
        return Processed(labels, errors)
    }

    private fun validate(label: String, options: Options, errors: MutableSet<String>, fromPunycode: Boolean) {
        if (label.isEmpty()) return
        if (!Normalizer.isNormalized(label, Normalizer.Form.NFC)) errors += "V1"
        if (options.checkHyphens) {
            if (label.length >= 4 && label[2] == '-' && label[3] == '-') errors += "V2"
            if (label.startsWith('-') || label.endsWith('-')) errors += "V3"
        } else if (label.startsWith("xn--")) {
            errors += "V4"
        }
        if (label.contains('.')) errors += "V5"
        val first = label.codePointAt(0)
        if (isMark(first)) errors += "V6"
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x80) {
                if (options.useStd3AsciiRules && !(cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code || cp == '-'.code)) {
                    errors += "U1"
                }
                if (fromPunycode && cp in 'A'.code..'Z'.code) errors += "V7"
                continue
            }
            when (UnicodeTables.idnaStatus(cp)) {
                UnicodeTables.IdnaStatus.VALID, UnicodeTables.IdnaStatus.DEVIATION -> Unit
                else -> errors += "V7"
            }
        }
        if (options.checkJoiners) checkJoiners(label, errors)
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> false
    }

    /** RFC 5892 appendix A.1 (ZWNJ) and A.2 (ZWJ). */
    private fun checkJoiners(label: String, errors: MutableSet<String>) {
        if (label.none { it == '\u200C' || it == '\u200D' }) return
        val cps = label.codePoints().toArray()
        for (i in cps.indices) {
            val cp = cps[i]
            if (cp != 0x200C && cp != 0x200D) continue
            if (i > 0 && UnicodeTables.isVirama(cps[i - 1])) continue
            if (cp == 0x200D) {
                errors += "C2"
                continue
            }
            // (Joining_Type:{L,D})(Joining_Type:T)*\u200C(Joining_Type:T)*(Joining_Type:{R,D})
            var before = i - 1
            while (before >= 0 && UnicodeTables.joiningType(cps[before]) == 'T') before--
            var after = i + 1
            while (after < cps.size && UnicodeTables.joiningType(cps[after]) == 'T') after++
            val leftOk = before >= 0 && UnicodeTables.joiningType(cps[before]).let { it == 'L' || it == 'D' }
            val rightOk = after < cps.size && UnicodeTables.joiningType(cps[after]).let { it == 'R' || it == 'D' }
            if (!leftOk || !rightOk) errors += "C1"
        }
    }

    /** RFC 5893 §2, applied to every label once the domain is a Bidi domain name (some label has R, AL or AN). */
    private fun checkBidi(labels: List<String>, errors: MutableSet<String>) {
        if (labels.all { label -> label.all { it.code < 0x80 } }) return // ASCII has no R, AL or AN characters
        val bidiDomain = labels.any { label -> label.codePoints().anyMatch { bidi(it).let { d -> d == R || d == AL || d == AN } } }
        if (!bidiDomain) return
        for (label in labels) {
            if (label.isEmpty()) continue
            val classes = label.codePoints().map { bidi(it) }.toArray()
            val first = classes[0]
            val rtl = when (first) {
                R, AL -> true
                L -> false
                else -> {
                    errors += "B1"
                    // Judge the rest as RTL when any strong RTL character follows, else as LTR.
                    classes.any { it == R || it == AL }
                }
            }
            var last = classes.size - 1
            while (last > 0 && classes[last] == NSM) last--
            if (rtl) {
                if (classes.any { it !in RTL_ALLOWED }) errors += "B2"
                if (classes[last] !in intArrayOf(R, AL, EN, AN)) errors += "B3"
                if (classes.contains(EN) && classes.contains(AN)) errors += "B4"
            } else {
                if (classes.any { it !in LTR_ALLOWED }) errors += "B5"
                if (classes[last] != L && classes[last] != EN) errors += "B6"
            }
        }
    }

    private val L = Character.DIRECTIONALITY_LEFT_TO_RIGHT.toInt()
    private val R = Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt()
    private val AL = Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt()
    private val EN = Character.DIRECTIONALITY_EUROPEAN_NUMBER.toInt()
    private val ES = Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR.toInt()
    private val ET = Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR.toInt()
    private val AN = Character.DIRECTIONALITY_ARABIC_NUMBER.toInt()
    private val CS = Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR.toInt()
    private val NSM = Character.DIRECTIONALITY_NONSPACING_MARK.toInt()
    private val BN = Character.DIRECTIONALITY_BOUNDARY_NEUTRAL.toInt()
    private val ON = Character.DIRECTIONALITY_OTHER_NEUTRALS.toInt()
    private val RTL_ALLOWED = intArrayOf(R, AL, AN, EN, ES, CS, ET, ON, BN, NSM)
    private val LTR_ALLOWED = intArrayOf(L, EN, ES, CS, ET, ON, BN, NSM)

    private fun bidi(cp: Int): Int = Character.getDirectionality(cp).toInt()
}
