package app.dak.classify.text

/**
 * The form of a message body that Dak's regex-based analysis reads (template rules, the model, OTP extraction, the
 * fake-credit detector, the masker); the text shown to the user is never changed. One linear pass:
 *
 * - **Combining-mark floods are capped.** A run of more than [MAX_MARKS] combining marks (Unicode Mn / Me / Mc,
 *   "zalgo") keeps its first [MAX_MARKS]. On a long run, every `\b` in every regex walks back over the whole run to
 *   find its base character, which made analysis quadratic (1.6 s for a 3,000-mark body;
 *   `shared/adversarial/pwn/unicode.tsv`, `unicode-zalgo-02`). Real text never stacks that many marks on one
 *   character (Indic syllables and Vietnamese use at most a few).
 * - **Right-to-left overrides are applied.** Text inside RIGHT-TO-LEFT OVERRIDE (U+202E) is displayed reversed, so it
 *   is reversed here, up to the matching POP DIRECTIONAL FORMATTING / POP DIRECTIONAL ISOLATE or the end of the
 *   line: `Rs <RLO>00.000,05<PDF>` is analysed as the `Rs 50,000.00` a bidi-unaware screen shows. Every scoped bidi
 *   control (U+202A–U+202E, U+2066–U+2069) is then dropped.
 * - **Invisible characters are dropped:** zero-width space and (non-)joiners, word joiner, invisible operators, BOM,
 *   soft hyphen, Mongolian vowel separator and the LRM / RLM / ALM marks, so `K\u200BYC` reads as `KYC`.
 *
 * [capMarksKeepingOffsets] is the length-preserving variant for callers that report character ranges.
 * `:finance` keeps an identical copy for the transaction parser (it does not depend on `:classify`);
 * `AnalysisTextEquivalenceTest` checks the two agree.
 */
public object AnalysisText {

    /** Combining marks kept in one run. */
    public const val MAX_MARKS: Int = 8

    /** The analysis form of [text] (see the object comment); [text] itself when nothing changes. */
    public fun of(text: String): String {
        if (!needsWork(text)) return text
        val sb = StringBuilder(text.length)
        var marks = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == RLO) {
                val end = overrideEnd(text, i + 1)
                appendReversed(text, i + 1, end, sb)
                marks = 0
                i = end
                continue
            }
            if (isDropped(c)) {
                i++
                continue
            }
            if (isMark(c)) {
                if (++marks > MAX_MARKS) {
                    i++
                    continue
                }
            } else {
                marks = 0
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * [text] with every combining mark after the first [MAX_MARKS] of a run replaced by WORD JOINER (U+2060: invisible,
     * not a mark, not a word character), so character offsets stay valid.
     */
    public fun capMarksKeepingOffsets(text: String): String {
        var run = 0
        var chars: CharArray? = null
        for (i in text.indices) {
            val c = text[i]
            if (c.code < 0x300) {
                run = 0
                continue
            }
            if (isMark(c)) {
                if (++run > MAX_MARKS) {
                    val out = chars ?: text.toCharArray().also { chars = it }
                    out[i] = '\u2060'
                }
            } else {
                run = 0
            }
        }
        return chars?.let { String(it) } ?: text
    }

    private const val RLO = '\u202E'

    private fun needsWork(text: String): Boolean {
        var run = 0
        for (c in text) {
            if (c.code < 0xAD) {
                run = 0
                continue
            }
            if (c == RLO || isDropped(c)) return true
            if (isMark(c)) {
                if (++run > MAX_MARKS) return true
            } else {
                run = 0
            }
        }
        return false
    }

    /** Where an override starting at [from] ends: its PDF / PDI (exclusive of it, which is then dropped), or the line end. */
    private fun overrideEnd(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            val c = text[j]
            if (c == '\u202C' || c == '\u2069') return j
            if (c == '\n' || c == '\r' || c == '\u2029') return j
            j++
        }
        return j
    }

    /** Appends text[from, to) in reverse code-point order, dropping invisible characters and capping mark runs. */
    private fun appendReversed(text: String, from: Int, to: Int, sb: StringBuilder) {
        var j = to
        var marks = 0
        while (j > from) {
            val low = text[j - 1]
            if (Character.isLowSurrogate(low) && j - 2 >= from && Character.isHighSurrogate(text[j - 2])) {
                sb.append(text[j - 2]).append(low)
                marks = 0
                j -= 2
                continue
            }
            j--
            if (isDropped(low) || low == RLO) continue
            if (isMark(low)) {
                if (++marks > MAX_MARKS) continue
            } else {
                marks = 0
            }
            sb.append(low)
        }
    }

    private fun isMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }

    /** Scoped bidi controls, bidi marks and invisible characters. */
    private fun isDropped(c: Char): Boolean =
        c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c in '\u200B'..'\u200F' || c in '\u2060'..'\u2064' ||
            c == '\u061C' || c == '\uFEFF' || c == '\u00AD' || c == '\u180E'
}
