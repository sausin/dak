package app.dak.classify.unicode

import java.lang.Character.UnicodeScript

/** UTS #39 §5.2 restriction levels, from most to least restrictive. */
public enum class RestrictionLevel {
    /** Only ASCII. */
    ASCII,

    /** One script (plus Common and Inherited characters: digits, punctuation, combining marks). */
    SINGLE_SCRIPT,

    /** One of the CJK combinations: Latin + Han + Hiragana + Katakana, Latin + Han + Bopomofo, Latin + Han + Hangul. */
    HIGHLY_RESTRICTIVE,

    /** Latin plus one other Recommended script, except Cyrillic and Greek (`Ramesh रमेश` is fine). */
    MODERATELY_RESTRICTIVE,

    /** Any mix of Recommended scripts (Latin with Cyrillic or Greek, or two non-Latin scripts). */
    MINIMALLY_RESTRICTIVE,

    /** Characters of scripts outside UTS #31's Recommended set, or unknown to the runtime. */
    UNRESTRICTED,
}

/**
 * Script-mixing checks from UTS #39 §5 (mixed-script and mixed-number detection).
 *
 * Scripts come from [UnicodeScript] (the runtime's Unicode data). Java has no Script_Extensions property, so Common and
 * Inherited characters are ignored rather than resolved (for example the danda `।`, Common with extensions {Deva, Beng,
 * …}): that equals UTS #39's resolved script set for every string whose only Common characters are shared ones, which
 * is the case for digits, punctuation, joiners and combining marks. Linear in the input, no regex.
 */
public object ScriptCheck {

    /** The scripts in [s], ignoring Common and Inherited. */
    public fun scriptsOf(s: CharSequence): Set<UnicodeScript> {
        var single: UnicodeScript? = null
        var many: MutableSet<UnicodeScript>? = null
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            i += Character.charCount(cp)
            if (cp < 0x80 && !Character.isLetter(cp)) continue
            val script = UnicodeScript.of(cp)
            if (script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED) continue
            when {
                many != null -> many += script
                single == null -> single = script
                single != script -> many = linkedSetOf(single, script)
            }
        }
        return many ?: single?.let { setOf(it) } ?: emptySet()
    }

    public fun restrictionLevel(s: CharSequence): RestrictionLevel {
        if (s.all { it.code < 0x80 }) return RestrictionLevel.ASCII
        val scripts = scriptsOf(s)
        if (scripts.any { it !in RECOMMENDED }) return RestrictionLevel.UNRESTRICTED
        if (scripts.size <= 1) return RestrictionLevel.SINGLE_SCRIPT
        if (HIGHLY_RESTRICTIVE_SETS.any { it.containsAll(scripts) }) return RestrictionLevel.HIGHLY_RESTRICTIVE
        if (scripts.size == 2 && UnicodeScript.LATIN in scripts &&
            UnicodeScript.CYRILLIC !in scripts && UnicodeScript.GREEK !in scripts
        ) {
            return RestrictionLevel.MODERATELY_RESTRICTIVE
        }
        return RestrictionLevel.MINIMALLY_RESTRICTIVE
    }

    /**
     * True when [s] mixes decimal digits of different numbering systems (`1২3`, `HDFC০1`): UTS #39 §5.3. ASCII and
     * fullwidth digits count as one system (NFKC folds them).
     */
    public fun hasMixedNumbers(s: CharSequence): Boolean {
        var zero = -1
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            i += Character.charCount(cp)
            if (Character.getType(cp) != Character.DECIMAL_DIGIT_NUMBER.toInt()) continue
            var z = cp - Character.digit(cp, 10)
            if (z == 0xFF10) z = '0'.code
            if (zero == -1) zero = z else if (zero != z) return true
        }
        return false
    }

    /**
     * True when one word of [name] (split at whitespace and punctuation) mixes scripts beyond
     * [RestrictionLevel.MODERATELY_RESTRICTIVE] (Latin with Cyrillic or Greek, or two non-Latin scripts) or mixes number
     * systems. Separate words may use different scripts: `Ramesh रमेश` and `Анна Smith` are not flagged, `НDFC Bank`
     * (Cyrillic Н) and `PayPаl` are.
     */
    public fun hasMixedScriptWord(name: CharSequence): Boolean {
        var start = 0
        val n = name.length
        while (start < n) {
            while (start < n && isSeparator(name[start])) start++
            var end = start
            while (end < n && !isSeparator(name[end])) end++
            if (end > start) {
                val word = name.subSequence(start, end)
                // A word in one script is never "mixed", even a script outside the Recommended set (Ol Chiki, Santali).
                if (word.any { it.code >= 0x80 }) {
                    if (hasMixedNumbers(word)) return true
                    if (scriptsOf(word).size > 1 && restrictionLevel(word) >= RestrictionLevel.MINIMALLY_RESTRICTIVE) return true
                }
            }
            start = end
        }
        return false
    }

    private fun isSeparator(c: Char): Boolean =
        c.isWhitespace() || (c.code < 0x80 && !c.isLetterOrDigit()) || c == '\u00A0' || c == '\u3000'

    /** The scripts written by the languages in [languageTags] (BCP 47, e.g. from the device's locale list). */
    public fun scriptsForLanguages(languageTags: List<String>): Set<UnicodeScript> {
        val out = LinkedHashSet<UnicodeScript>()
        for (tag in languageTags) {
            val locale = java.util.Locale.forLanguageTag(tag)
            val explicit = locale.script.takeIf { it.isNotEmpty() }?.let { runCatching { UnicodeScript.forName(it) }.getOrNull() }
            if (explicit != null) {
                out += explicit
                continue
            }
            LANGUAGE_SCRIPTS[locale.language]?.let { out += it }
        }
        return out
    }

    /** Likely scripts of common languages (CLDR likely subtags), enough to cover India's scheduled languages. */
    private val LANGUAGE_SCRIPTS: Map<String, List<UnicodeScript>> = mapOf(
        "hi" to listOf(UnicodeScript.DEVANAGARI), "mr" to listOf(UnicodeScript.DEVANAGARI),
        "ne" to listOf(UnicodeScript.DEVANAGARI), "sa" to listOf(UnicodeScript.DEVANAGARI),
        "kok" to listOf(UnicodeScript.DEVANAGARI), "mai" to listOf(UnicodeScript.DEVANAGARI),
        "doi" to listOf(UnicodeScript.DEVANAGARI), "brx" to listOf(UnicodeScript.DEVANAGARI),
        "bn" to listOf(UnicodeScript.BENGALI), "as" to listOf(UnicodeScript.BENGALI), "mni" to listOf(UnicodeScript.BENGALI),
        "pa" to listOf(UnicodeScript.GURMUKHI), "gu" to listOf(UnicodeScript.GUJARATI), "or" to listOf(UnicodeScript.ORIYA),
        "ta" to listOf(UnicodeScript.TAMIL), "te" to listOf(UnicodeScript.TELUGU), "kn" to listOf(UnicodeScript.KANNADA),
        "ml" to listOf(UnicodeScript.MALAYALAM), "si" to listOf(UnicodeScript.SINHALA),
        "ur" to listOf(UnicodeScript.ARABIC), "ar" to listOf(UnicodeScript.ARABIC), "fa" to listOf(UnicodeScript.ARABIC),
        "ks" to listOf(UnicodeScript.ARABIC), "sd" to listOf(UnicodeScript.ARABIC), "ps" to listOf(UnicodeScript.ARABIC),
        "he" to listOf(UnicodeScript.HEBREW), "iw" to listOf(UnicodeScript.HEBREW), "yi" to listOf(UnicodeScript.HEBREW),
        "ru" to listOf(UnicodeScript.CYRILLIC), "uk" to listOf(UnicodeScript.CYRILLIC), "bg" to listOf(UnicodeScript.CYRILLIC),
        "sr" to listOf(UnicodeScript.CYRILLIC), "mk" to listOf(UnicodeScript.CYRILLIC), "be" to listOf(UnicodeScript.CYRILLIC),
        "kk" to listOf(UnicodeScript.CYRILLIC), "ky" to listOf(UnicodeScript.CYRILLIC), "mn" to listOf(UnicodeScript.CYRILLIC),
        "el" to listOf(UnicodeScript.GREEK), "hy" to listOf(UnicodeScript.ARMENIAN), "ka" to listOf(UnicodeScript.GEORGIAN),
        "th" to listOf(UnicodeScript.THAI), "lo" to listOf(UnicodeScript.LAO), "km" to listOf(UnicodeScript.KHMER),
        "my" to listOf(UnicodeScript.MYANMAR), "am" to listOf(UnicodeScript.ETHIOPIC), "dv" to listOf(UnicodeScript.THAANA),
        "bo" to listOf(UnicodeScript.TIBETAN), "dz" to listOf(UnicodeScript.TIBETAN),
        "zh" to listOf(UnicodeScript.HAN, UnicodeScript.BOPOMOFO),
        "ja" to listOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA),
        "ko" to listOf(UnicodeScript.HANGUL, UnicodeScript.HAN),
    )

    /** UTS #31 Table 4, Recommended scripts. */
    private val RECOMMENDED: Set<UnicodeScript> = setOf(
        UnicodeScript.ARABIC, UnicodeScript.ARMENIAN, UnicodeScript.BENGALI, UnicodeScript.BOPOMOFO,
        UnicodeScript.CYRILLIC, UnicodeScript.DEVANAGARI, UnicodeScript.ETHIOPIC, UnicodeScript.GEORGIAN,
        UnicodeScript.GREEK, UnicodeScript.GUJARATI, UnicodeScript.GURMUKHI, UnicodeScript.HAN, UnicodeScript.HANGUL,
        UnicodeScript.HEBREW, UnicodeScript.HIRAGANA, UnicodeScript.KANNADA, UnicodeScript.KATAKANA,
        UnicodeScript.KHMER, UnicodeScript.LAO, UnicodeScript.LATIN, UnicodeScript.MALAYALAM, UnicodeScript.MYANMAR,
        UnicodeScript.ORIYA, UnicodeScript.SINHALA, UnicodeScript.TAMIL, UnicodeScript.TELUGU, UnicodeScript.THAANA,
        UnicodeScript.THAI, UnicodeScript.TIBETAN,
    )

    private val HIGHLY_RESTRICTIVE_SETS: List<Set<UnicodeScript>> = listOf(
        setOf(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA),
        setOf(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.BOPOMOFO),
        setOf(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.HANGUL),
    )
}
