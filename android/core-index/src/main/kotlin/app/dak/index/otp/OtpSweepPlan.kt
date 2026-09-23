package app.dak.index.otp

/**
 * One pending OTP auto-delete: the message ([key], `MessageKey.toString()` form), when it is due, and the encoded
 * `DeletedBy` it will carry into the bin.
 */
data class OtpDeleteEntry(val key: String, val deleteAtMillis: Long, val deletedBy: String) {

    /** A consumed-OTP delete (`auto-consumed:<pkg>`): may run late by a little, never early. */
    val consumed: Boolean get() = deletedBy.startsWith(CONSUMED_PREFIX)

    /** Compact storage form: `<deleteAt>|<deletedBy>` (the key is stored separately). */
    fun encodeValue(): String = "$deleteAtMillis|$deletedBy"

    companion object {
        private const val CONSUMED_PREFIX = "auto-consumed:"

        /** Parses [encodeValue] output; null for anything malformed. */
        fun decode(key: String, value: String): OtpDeleteEntry? {
            val bar = value.indexOf('|')
            if (bar <= 0 || bar == value.length - 1) return null
            val at = value.substring(0, bar).toLongOrNull() ?: return null
            return OtpDeleteEntry(key, at, value.substring(bar + 1))
        }
    }
}

/**
 * Batching rules for OTP auto-deletes, as pure arithmetic. Instead of one timer per message, a single job is armed
 * for the most urgent **deadline** ([nextRunAt]) and, when it runs, deletes every entry whose tolerance window has
 * opened ([due]). Each entry may run inside `[deleteAt - early, deleteAt + late]`:
 *
 * - consumed OTPs (default 10 min, never under 5): not early (a retrying app must still be able to read the code),
 *   up to [CONSUMED_LATE_MILLIS] late;
 * - 24 h auto-deletes: [AUTO_EARLY_MILLIS] early to [AUTO_LATE_MILLIS] late - nobody can tell 23 h 15 min from 24 h,
 *   and it lets OTPs that arrived within the same hour go in one wakeup.
 */
object OtpSweepPlan {
    const val CONSUMED_LATE_MILLIS: Long = 3 * 60_000L
    const val AUTO_EARLY_MILLIS: Long = 45 * 60_000L
    const val AUTO_LATE_MILLIS: Long = 15 * 60_000L

    /** Earliest moment [entry] may be deleted. */
    fun earliest(entry: OtpDeleteEntry): Long =
        if (entry.consumed) entry.deleteAtMillis else entry.deleteAtMillis - AUTO_EARLY_MILLIS

    /** Latest moment [entry] should be deleted (its deadline). */
    fun latest(entry: OtpDeleteEntry): Long =
        if (entry.consumed) entry.deleteAtMillis + CONSUMED_LATE_MILLIS else entry.deleteAtMillis + AUTO_LATE_MILLIS

    /** Entries that may be deleted at [nowMillis]. */
    fun due(entries: Collection<OtpDeleteEntry>, nowMillis: Long): List<OtpDeleteEntry> =
        entries.filter { earliest(it) <= nowMillis }

    /** When the single sweep job should next run (the most urgent deadline), or null when nothing is pending. */
    fun nextRunAt(entries: Collection<OtpDeleteEntry>): Long? = entries.minOfOrNull { latest(it) }

    /**
     * Whether a sweep already armed for [armedAtMillis] (0 = none) covers the pending [nextRunAtMillis]: it runs no
     * later than needed and is not stale (a run that should have happened more than [STALE_MILLIS] ago was lost).
     */
    fun armedCovers(armedAtMillis: Long, nextRunAtMillis: Long, nowMillis: Long): Boolean =
        armedAtMillis != 0L && armedAtMillis <= nextRunAtMillis && armedAtMillis > nowMillis - STALE_MILLIS

    /** An armed time this far in the past means the job was lost (force stop, cleared data): arm again. */
    const val STALE_MILLIS: Long = 60 * 60_000L
}
