package app.dak.classify.unicode

/**
 * UAX #9 isolation for text other people wrote (sender names, subjects, snippets, links) that the app splices into
 * its own strings. Pure Kotlin so it is unit-tested on the JVM; the app's `BidiText` delegates here.
 *
 * - **Isolation.** [isolate] wraps text in FIRST STRONG ISOLATE … POP DIRECTIONAL ISOLATE (U+2068 … U+2069), so its
 *   direction is taken from its own first strong character and can never reorder the text around it: an Arabic or
 *   Urdu name before "₹500" leaves the amount where it is. [isolateLtr] uses LEFT-TO-RIGHT ISOLATE (U+2066) for text
 *   that is always read left to right (links, hosts, phone numbers, codes).
 * - **Neutralisation.** An isolate only holds if the text inside cannot close it early: a stray PDI (U+2069) inside
 *   would end the wrapper and let the rest escape, and an unterminated RLO would run to the end of the paragraph.
 *   So the scoped controls, embeddings, overrides, isolates and their terminators (U+202A–U+202E, U+2066–U+2069), are
 *   always removed before wrapping ([neutralize]). Implicit directionality (the Arabic, Hebrew or Urdu letters
 *   themselves) is untouched, so right-to-left text still renders right to left inside its isolate.
 * - **Names.** [sanitizeName] also removes the marks (LRM, RLM, ALM) and invisible characters (zero width space,
 *   word joiner, invisible operators, BOM, Mongolian vowel separator) that have no place in a display name, keeping
 *   ZWJ/ZWNJ where Indic and Arabic spelling needs them (after a letter, a vowel sign or a virama and before a letter)
 *   and ZWJ inside emoji sequences.
 *
 * Everything is a single linear pass, with no regex.
 */
public object UntrustedText {
    public const val LRI: Char = '\u2066'
    public const val RLI: Char = '\u2067'
    public const val FSI: Char = '\u2068'
    public const val PDI: Char = '\u2069'

    private const val ZWNJ = '\u200C'
    private const val ZWJ = '\u200D'

    /** LRE, RLE, PDF, LRO, RLO (U+202A–U+202E) and LRI, RLI, FSI, PDI (U+2066–U+2069): the controls with a scope. */
    public fun isScopedBidiControl(c: Char): Boolean = c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

    /** [isScopedBidiControl] plus the marks LRM, RLM (U+200E, U+200F) and ARABIC LETTER MARK (U+061C). */
    public fun isBidiControl(c: Char): Boolean = isScopedBidiControl(c) || c == '\u200E' || c == '\u200F' || c == '\u061C'

    private fun isInvisible(c: Char): Boolean =
        c == '\u200B' || c in '\u2060'..'\u2064' || c == '\uFEFF' || c == '\u180E'

    /** [text] without scoped bidi controls (see the class comment). Returns [text] itself when there are none. */
    public fun neutralize(text: String): String {
        if (text.none(::isScopedBidiControl)) return text
        val sb = StringBuilder(text.length)
        for (c in text) if (!isScopedBidiControl(c)) sb.append(c)
        return sb.toString()
    }

    /**
     * [text] with every scoped bidi control replaced by U+2060 WORD JOINER (invisible, bidi-neutral, no line break), so
     * offsets into [text] (search highlights, OTP, link and entity spans) stay valid. For a message body shown as its
     * own paragraph: an RLO before "https://moc.knabcfdh" can no longer make it read "hdfcbank.com//:sptth".
     */
    public fun neutralizeKeepingOffsets(text: String): String {
        if (text.none(::isScopedBidiControl)) return text
        val chars = text.toCharArray()
        for (i in chars.indices) if (isScopedBidiControl(chars[i])) chars[i] = '\u2060'
        return String(chars)
    }

    /** FSI + [neutralize]d [text] + PDI: direction from its own content, contained in its own span. */
    public fun isolate(text: String): String = "$FSI${neutralize(text)}$PDI"

    /** LRI + [neutralize]d [text] + PDI, for text read left to right whatever it contains (URLs, hosts, numbers). */
    public fun isolateLtr(text: String): String = "$LRI${neutralize(text)}$PDI"

    /** A display name without bidi controls, marks or invisible characters; ZWJ/ZWNJ kept only inside words. */
    public fun sanitizeName(name: String): String {
        if (name.none { isBidiControl(it) || isInvisible(it) || it == ZWJ || it == ZWNJ }) return name
        val sb = StringBuilder(name.length)
        for (i in name.indices) {
            val c = name[i]
            when {
                isBidiControl(c) || isInvisible(c) -> Unit
                c == ZWJ || c == ZWNJ -> {
                    // A joiner shapes the letter before it (a conjunct after a virama, a half form, Arabic joining).
                    val before = sb.lastOrNull()
                    val after = nextVisible(name, i + 1)
                    val inWord = before != null && after != null && isWordPart(before) && Character.isLetter(after)
                    // ZWJ also glues emoji sequences (family, profession and flag emoji).
                    val inEmoji = c == ZWJ && before != null && after != null && isEmojiPart(before) && isEmojiPart(after)
                    if (inWord || inEmoji) sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** [sanitizeName] then [isolate]: what every place that shows an untrusted name inside other text should use. */
    public fun isolateName(name: String): String = "$FSI${sanitizeName(name)}$PDI"

    private fun nextVisible(s: String, from: Int): Char? {
        var j = from
        while (j < s.length && (isBidiControl(s[j]) || isInvisible(s[j]))) j++
        return if (j < s.length) s[j] else null
    }

    private fun isEmojiPart(c: Char): Boolean =
        Character.isSurrogate(c) || c == '\uFE0F' || Character.getType(c) == Character.OTHER_SYMBOL.toInt()

    private fun isWordPart(c: Char): Boolean = Character.isLetter(c) || when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }
}
