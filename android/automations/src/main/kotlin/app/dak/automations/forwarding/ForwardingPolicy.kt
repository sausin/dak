package app.dak.automations.forwarding

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Rule
import app.dak.automations.rule.activeWindow

/**
 * How long auto-forwarding may run before it needs the user's fingerprint / screen lock. Forwarding bank SMS and
 * OTPs to someone else is a classic fraud setup ("please forward your messages to me for verification"), so:
 * - a new rule defaults to [DEFAULT_DURATION_MILLIS] (1 hour) from now;
 * - anything longer, or "until I stop it", is a *long* period ([isLongPeriod]) and needs a biometric/device-credential
 *   confirmation, which the runner re-checks at forward time (see `OtpForwardConfirmations` in :app);
 * - moving an existing rule's end later ([extends]) needs the confirmation too, however short the new period.
 *
 * Periods are exact instants (epoch millis) and compared against the message's receive time.
 */
public object ForwardingPolicy {

    /** Default and maximum unconfirmed period: 1 hour. */
    public const val DEFAULT_DURATION_MILLIS: Long = 60L * 60 * 1000

    /**
     * Slack on top of [DEFAULT_DURATION_MILLIS] so a 1-hour period whose times were picked to the minute (seconds
     * dropped) never counts as long.
     */
    public const val LONG_PERIOD_GRACE_MILLIS: Long = 60_000L

    /** The period a new rule starts with: now until one hour from now. */
    public fun defaultWindow(nowMillis: Long): Pair<Long, Long> = nowMillis to nowMillis + DEFAULT_DURATION_MILLIS

    /** True for an open-ended period or one longer than an hour. */
    public fun isLongPeriod(startMillis: Long, endMillis: Long?): Boolean =
        endMillis == null || endMillis - startMillis > DEFAULT_DURATION_MILLIS + LONG_PERIOD_GRACE_MILLIS

    /**
     * True when saving [edited] over the stored [original] makes forwarding run later than it would have: a later
     * end, or open-ended where it had an end. A new rule (no [original]) never "extends".
     */
    public fun extends(original: ForwardingSpec?, edited: ForwardingSpec): Boolean {
        if (original?.id == null) return false
        val oldEnd = original.endMillis ?: return false
        val newEnd = edited.endMillis ?: return true
        return newEnd > oldEnd
    }

    /**
     * True when [rule] forwards SMS over a long period. A rule without a validity window (built in the generic
     * Automations editor) runs until stopped, so it counts as long.
     */
    public fun hasLongSmsForward(rule: Rule): Boolean {
        if (rule.actions.none { it is ActionSpec.ForwardSms }) return false
        val window = rule.activeWindow() ?: return true
        return isLongPeriod(window.startMillis, window.endMillis)
    }
}
