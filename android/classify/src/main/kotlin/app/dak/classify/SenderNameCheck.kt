package app.dak.classify

import app.dak.classify.unicode.Confusables
import app.dak.classify.unicode.ScriptCheck

/**
 * UTS #39 checks on a sender address or display name that is not plain ASCII: MMS `From` strings, e-mail-gateway and
 * RCS/aggregator originators, contact-less alphanumeric names. An SMS alphanumeric TP-OA is GSM 7-bit (Latin plus a few
 * Greek capitals that look like no Latin letter), so real bank and brand headers are ASCII; a header written with
 * Cyrillic, Greek or fullwidth look-alikes, or with digits from another script, is an imitation.
 *
 * Nothing here looks at brand names: callers compare [Result.skeleton] with the skeletons of the names they know.
 */
public object SenderNameCheck {

    /**
     * @property mixedScript one word mixes scripts beyond UTS #39's moderately-restrictive level (Latin with Cyrillic or
     *   Greek, two non-Latin scripts) or mixes number systems: `НDFCBK` with a Cyrillic Н, `PаyTM`, `SBI০1`.
     * @property asciiLookalike the name has non-ASCII characters but its skeleton is spelled only with what ASCII letters
     *   and digits look like, so it can pass for an ASCII header (`ＨＤＦＣＢＫ`, `АХІЅВК` all in Cyrillic).
     * @property skeleton the case-folded UTS #39 skeleton of the name ([Confusables.caseFoldedSkeleton]), for
     *   comparison with known headers' skeletons.
     */
    public data class Result(val mixedScript: Boolean, val asciiLookalike: Boolean, val skeleton: String) {
        public val suspicious: Boolean get() = mixedScript || asciiLookalike
    }

    /** Longest name examined (display names are short; the checks are linear anyway). */
    private const val MAX_CHARS = 256

    /** Null for an ASCII-only [name] (nothing to check: the existing header rules apply). */
    public fun check(name: String): Result? {
        val text = if (name.length > MAX_CHARS) name.substring(0, MAX_CHARS) else name
        if (text.all { it.code < 0x80 }) return null
        val upper = text.trim().uppercase()
        return Result(
            mixedScript = ScriptCheck.hasMixedScriptWord(text),
            asciiLookalike = Confusables.isAsciiLookalike(upper),
            skeleton = Confusables.caseFoldedSkeleton(upper),
        )
    }

    /** True when [name] is a non-ASCII imitation or mixes scripts (see [Result]). */
    public fun isSuspicious(name: String): Boolean = check(name)?.suspicious == true
}
