package app.dak.notifications

/**
 * Pure rules for collapsing repeated messages into one notification with a "×N" count instead of stacking.
 * (The thread view's fold of repeats is separate, in :core-index / the conversation list.)
 *
 * - Informational and OTP messages repeat when their [template] matches: same text after lower-casing, collapsing
 *   whitespace and masking digit runs (so "Balance Rs 1,200" and "Balance Rs 1,450" match, as do resent OTPs).
 * - Personal messages repeat only when the text is identical ([exact]); "Meet at 5" / "Meet at 6" both stay.
 * - Either way only within [WINDOW_MILLIS] of the previous one.
 */
object RepeatCollapse {

    /** How close together two messages must arrive to count as a repeat. */
    const val WINDOW_MILLIS: Long = 15 * 60_000L

    /** Distinct messages kept as lines in one conversation's notification. */
    const val MAX_LINES: Int = 5

    private val whitespace = Regex("\\s+")
    private val digits = Regex("\\d+")
    private val maskedNumber = Regex("#([.,:/-]#)+")

    /** Whitespace-normalized, case-folded text: the key for identical repeats. */
    fun exact(body: String): String = body.trim().replace(whitespace, " ").lowercase()

    /** [exact] with every digit run (and digit separators within numbers) masked as `#`. */
    fun template(body: String): String = exact(body).replace(digits, "#").replace(maskedNumber, "#")

    /** True when a message keyed [key] at [atMillis] repeats the previous one keyed [previousKey] at [previousAtMillis]. */
    fun isRepeat(previousKey: String?, previousAtMillis: Long, key: String, atMillis: Long, windowMillis: Long = WINDOW_MILLIS): Boolean =
        previousKey != null && previousKey.isNotEmpty() && previousKey == key && atMillis - previousAtMillis in 0..windowMillis

    /** [text] with a "×N" suffix when [count] > 1. */
    fun withCount(text: String, count: Int): String = if (count > 1) "$text ×$count" else text

    /**
     * Running state of one conversation's informational notification, carried in the notification's extras so it
     * survives process death and disappears with the notification.
     */
    data class State(
        /** Template of the newest message. */
        val key: String,
        /** How many times the newest distinct message arrived in a row. */
        val count: Int,
        val atMillis: Long,
        /** Distinct messages, oldest first, newest last, without counts. */
        val lines: List<String>,
        /** Counts per line (parallel to [lines]). */
        val counts: List<Int>,
        /** Every message folded into this notification. */
        val total: Int,
    ) {
        /** Display lines with their "×N" suffixes. */
        fun displayLines(): List<String> = lines.mapIndexed { i, line -> withCount(line, counts.getOrElse(i) { 1 }) }

        val isRepeat: Boolean get() = count > 1
    }

    /** Folds [body] (keyed [key], arriving at [atMillis]) into [previous]. */
    fun next(previous: State?, body: String, key: String, atMillis: Long, windowMillis: Long = WINDOW_MILLIS): State {
        if (previous != null && previous.lines.isNotEmpty() && isRepeat(previous.key, previous.atMillis, key, atMillis, windowMillis)) {
            val count = previous.count + 1
            return State(
                key = key,
                count = count,
                atMillis = atMillis,
                lines = previous.lines.dropLast(1) + body,
                counts = previous.counts.dropLast(1) + count,
                total = previous.total + 1,
            )
        }
        val lines = ((previous?.lines ?: emptyList()) + body).takeLast(MAX_LINES)
        val counts = ((previous?.counts ?: emptyList()) + 1).takeLast(MAX_LINES)
        return State(key, 1, atMillis, lines, counts, (previous?.total ?: 0) + 1)
    }
}
