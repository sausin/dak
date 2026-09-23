package app.dak.ui.common.text

import app.dak.classify.unicode.UntrustedText

/**
 * Makes untrusted text (a sender display name/address, a message snippet, a subject, a link) safe to splice into
 * surrounding UI text, regardless of what script or bidi control characters it contains. The logic lives in
 * [UntrustedText] (`:classify`, unit-tested on the JVM); this is the app-side entry point.
 *
 * Two independent problems are handled (UAX #9):
 * - **Directionality leakage**: an RTL sender name (Arabic/Urdu/Hebrew) sitting next to LTR UI text (a timestamp, a
 *   balance) can visually reorder that neighbouring text unless it is isolated. [isolate] wraps it in real Unicode
 *   isolates, FIRST STRONG ISOLATE … POP DIRECTIONAL ISOLATE (U+2068 … U+2069), so its direction comes from its own
 *   first strong character and never escapes its span. (`android.text.BidiFormatter.unicodeWrap`, used before, emits
 *   embeddings and LRM/RLM marks, not isolates.) Android's text stack (ICU bidi, API 24+) and Compose handle isolates.
 * - **Bidi spoofing**: explicit embeddings, overrides and isolates inside the untrusted text (the U+202E
 *   RIGHT-TO-LEFT OVERRIDE "gpj.exe" trick, or a stray PDI that would close the wrapper early) are always removed
 *   before wrapping. For names, [sanitizeDisplayName] also strips LRM/RLM/ALM and stray zero-width characters, keeping
 *   ZWJ/ZWNJ where Indic and Arabic spelling needs them.
 */
object BidiText {

    /**
     * Wraps [text] in FSI…PDI after removing any explicit bidi embeddings, overrides and isolates it carries, so its own
     * directionality can never affect the text around it once composed into a larger string or
     * [androidx.compose.ui.text.AnnotatedString]. The isolate takes its direction from the text itself, so
     * [isRtlContext] is no longer needed; it is kept so existing call sites compile unchanged.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isolate(text: String, isRtlContext: Boolean = false): String = UntrustedText.isolate(text)

    /** Like [isolate], but always laid out left to right (LRI…PDI): links, hosts, phone numbers, codes. */
    fun isolateLtr(text: String): String = UntrustedText.isolateLtr(text)

    /**
     * Strips characters that have no business in a display name: explicit bidi override/embedding/isolate/mark
     * characters (the U+202E spoofing trick and friends) and stray zero-width characters, while preserving ZWJ/ZWNJ
     * inside words (after a letter, vowel sign or virama and before a letter), where they are a legitimate part of how
     * an Indic or Arabic conjunct renders, and ZWJ inside emoji sequences.
     */
    fun sanitizeDisplayName(name: String): String = UntrustedText.sanitizeName(name)

    /** Sanitizes then isolates [name] - the one call sites rendering an untrusted name should use. */
    @Suppress("UNUSED_PARAMETER")
    fun displaySafe(name: String, isRtlContext: Boolean = false): String = UntrustedText.isolateName(name)
}
