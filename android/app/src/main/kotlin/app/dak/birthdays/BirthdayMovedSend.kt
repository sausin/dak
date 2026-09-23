package app.dak.birthdays

import app.dak.automations.birthdays.OccasionKind
import app.dak.automations.birthdays.WishTag
import kotlin.math.abs

/**
 * A pending wish the user moved (heads-up "Delay" / "Tomorrow same time", "Change time" in the scheduled list), or
 * that the executor held back (default-SMS-app hold, rate limit). [BirthdayScheduler.reconcile] must leave such a send
 * alone: rescheduling it to the configured time would undo the user's choice, and once the configured time has passed
 * the next occurrence is next year, which would drop this year's wish. Pure, for unit tests.
 */
internal object BirthdayMovedSend {
    /** A move further than this from the configured time is not treated as a move of that occurrence. */
    const val MAX_MOVE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * True when the pending send ([sendTag], due at [sendAtMillis]) is this contact's wish, moved away from the time
     * the config scheduled it for ([configScheduledAtMillis]), and should be kept as it is.
     */
    fun keep(
        pending: Boolean,
        sendTag: WishTag?,
        contactId: Long,
        kind: OccasionKind,
        configScheduledAtMillis: Long?,
        sendAtMillis: Long,
    ): Boolean {
        if (!pending || sendTag == null || configScheduledAtMillis == null) return false
        if (sendTag.contactId != contactId || sendTag.kind != kind) return false
        if (sendAtMillis == configScheduledAtMillis) return false
        return abs(sendAtMillis - configScheduledAtMillis) <= MAX_MOVE_MILLIS
    }
}
