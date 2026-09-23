package app.dak.notifications

/**
 * Message text as it may appear in a notification. The body is written by whoever sent the message, and a
 * notification is rendered by SystemUI, persisted by NotificationManager and mirrored to watches and cars, so:
 *
 * - it is cut to [MAX_BODY_CHARS] (a concatenated SMS can carry ~39k characters and an MMS text part up to 64k).
 *   Posting a notification is a Binder call with a 1 MB limit; the conversation style, the custom OTP views and the
 *   repeat-collapse extras each carry the text again, so an unbounded body could make `notify()` throw and the
 *   message never be announced. Nobody reads more than a few lines in a shade anyway;
 * - explicit bidi embeddings, overrides and isolates (U+202A–U+202E, U+2066–U+2069) are removed, so a body cannot
 *   visually reverse or re-order the rest of the notification ("…‮lanigiro‬" tricks around amounts, links and codes).
 *   Implicit directionality (Arabic, Hebrew, Urdu text) is untouched.
 */
object NotificationText {

    /** Longest message text put in a notification. */
    const val MAX_BODY_CHARS: Int = 2_000

    fun body(raw: String): String {
        val cut = if (raw.length <= MAX_BODY_CHARS) raw else raw.substring(0, safeEnd(raw, MAX_BODY_CHARS)) + "…"
        if (cut.none(::isExplicitBidiControl)) return cut
        return buildString(cut.length) { for (c in cut) if (!isExplicitBidiControl(c)) append(c) }
    }

    private fun isExplicitBidiControl(c: Char): Boolean = c in '‪'..'‮' || c in '⁦'..'⁩'

    /** [end] moved back one when it would split a surrogate pair. */
    private fun safeEnd(text: String, end: Int): Int = if (end > 0 && Character.isHighSurrogate(text[end - 1])) end - 1 else end
}
