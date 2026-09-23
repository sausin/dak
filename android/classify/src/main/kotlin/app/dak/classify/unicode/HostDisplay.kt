package app.dak.classify.unicode

import java.lang.Character.UnicodeScript

/**
 * How to show a link's host to a reader: each label in Unicode when that is safe for them, otherwise in its `xn--`
 * (Punycode) form, in the spirit of the browsers' IDN display policies. A label is shown in Unicode only when:
 *
 * - it passes UTS #46 with every check on ([Uts46.STRICT]: hyphens, STD3 ASCII rules, CONTEXTJ, Bidi rule);
 * - it is single-script or one of the CJK combinations ([RestrictionLevel.HIGHLY_RESTRICTIVE]) and does not mix number
 *   systems (UTS #39 §5.2, §5.3);
 * - every script in it is one the reader reads: Latin, the scripts of their languages, or the script of the top-level
 *   domain when that is itself an internationalised label (`उदाहरण.भारत` for anyone, since `.भारत` only takes
 *   Devanagari names);
 * - it is not a non-Latin label whose every character passes for ASCII (`раураl` in Cyrillic): whole-script
 *   confusable with Latin, UTS #39 §4.
 *
 * Anything else, and any host that fails processing, is shown as its ASCII form, which is what the browser will open.
 */
public object HostDisplay {

    /**
     * The host to show for [asciiHost] (the UTS #46 ToASCII form, as in `ExtractedLink.asciiHost`) to a reader of
     * [readerScripts] (see [ScriptCheck.scriptsForLanguages]).
     */
    public fun displayHost(asciiHost: String, readerScripts: Set<UnicodeScript> = emptySet()): String {
        val asciiLabels = asciiHost.split('.')
        if (asciiLabels.none { it.startsWith("xn--", ignoreCase = true) }) return asciiHost.lowercase()
        val unicode = Uts46.toUnicode(asciiHost, Uts46.BROWSER)
        if (!unicode.ok) return asciiHost.lowercase()
        val labels = unicode.value.split('.')
        if (labels.size != asciiLabels.size) return asciiHost.lowercase()
        val tld = labels.lastOrNull { it.isNotEmpty() }.orEmpty()
        val tldScripts = if (tld.any { it.code >= 0x80 }) ScriptCheck.scriptsOf(tld).takeIf { it.size == 1 }.orEmpty() else emptySet()
        val allowed = readerScripts + tldScripts + UnicodeScript.LATIN
        return labels.indices.joinToString(".") { i ->
            val label = labels[i]
            if (label.all { it.code < 0x80 } || isLabelSafe(label, allowed)) label else asciiLabels[i].lowercase()
        }
    }

    /** True when every label of [unicodeHost] could be shown in Unicode to someone who reads every script. */
    public fun isSafeForSomeReader(unicodeHost: String): Boolean =
        unicodeHost.split('.').all { label -> label.all { it.code < 0x80 } || isLabelSafe(label, null) }

    /** The checks in the class comment for one Unicode [label]; [allowed] null skips the reader-script check. */
    internal fun isLabelSafe(label: String, allowed: Set<UnicodeScript>?): Boolean {
        if (!Uts46.toUnicode(label, STRICT_LABEL).ok) return false
        if (ScriptCheck.restrictionLevel(label) > RestrictionLevel.HIGHLY_RESTRICTIVE) return false
        if (ScriptCheck.hasMixedNumbers(label)) return false
        val scripts = ScriptCheck.scriptsOf(label)
        if (allowed != null && !allowed.containsAll(scripts)) return false
        if (UnicodeScript.LATIN !in scripts && Confusables.isAsciiLookalike(label)) return false
        return true
    }

    /** [Uts46.STRICT] without the length check, which only makes sense for a whole ASCII host. */
    private val STRICT_LABEL = Uts46.STRICT.copy(verifyDnsLength = false)
}
