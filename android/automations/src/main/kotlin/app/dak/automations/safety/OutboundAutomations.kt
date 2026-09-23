package app.dak.automations.safety

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Rule
import app.dak.automations.rule.activeWindow
import app.dak.automations.rule.sendsOffDevice

/**
 * Pure decisions behind the two anti-tampering safeguards for automations that send messages off the phone
 * ([sendsOffDevice]): the threat is someone with a minute on the owner's unlocked phone who sets up auto-forwarding of
 * bank SMS and OTPs to themselves.
 *
 * 1. Such automations only run while an app lock is set up (the :app guard disables them when it is not).
 * 2. Some hours after one is turned on, the owner gets a "Was this you?" notification ([REMINDER_DELAY_MILLIS]),
 *    repeated daily while it stays on ([REMINDER_REPEAT_MILLIS]).
 */
public object OutboundAutomations {

    /**
     * Delay before the first "Was this you?" reminder. Three hours: long enough that the person who set the rule up
     * (possibly the owner, in the middle of something) is not nagged at once, and short enough that a tampered phone
     * is noticed the same day, while the damage (forwarded OTPs) can still be limited.
     */
    public const val REMINDER_DELAY_MILLIS: Long = 3L * 60 * 60 * 1000

    /** While the automation stays on, the reminder repeats this often. */
    public const val REMINDER_REPEAT_MILLIS: Long = 24L * 60 * 60 * 1000

    /** How many rule names the "turn off app lock?" dialog lists before "and N more". */
    public const val NOTICE_MAX_NAMES: Int = 5

    /**
     * True when saving [after] over [before] (null for a new rule) turns on sending off the phone, so the "Was this
     * you?" reminder must be (re)armed: the rule becomes enabled, gains an outbound action, sends to a different place,
     * or runs later than before. Saving an unchanged enabled rule does not re-arm it.
     */
    public fun remindAfterSave(before: Rule?, after: Rule): Boolean {
        if (!after.enabled || !after.sendsOffDevice()) return false
        if (before == null || !before.enabled || !before.sendsOffDevice()) return true
        if (outbound(before) != outbound(after)) return true
        return runsLonger(before, after)
    }

    /** The rule's actions that send off the phone, in order (to compare destinations between two versions). */
    public fun outbound(rule: Rule): List<ActionSpec> = rule.actions.filter { it.sendsOffDevice() }

    /** Names to list in a notice, at most [max], plus how many more there are. */
    public fun namesForNotice(names: List<String>, max: Int = NOTICE_MAX_NAMES): Pair<List<String>, Int> {
        val shown = names.take(max.coerceAtLeast(0))
        return shown to (names.size - shown.size)
    }

    /** True when [after]'s window ends later than [before]'s, or is open-ended where [before]'s was not. */
    private fun runsLonger(before: Rule, after: Rule): Boolean {
        val old = before.activeWindow()
        val new = after.activeWindow()
        if (old == null) return false
        if (new == null) return true
        val oldEnd = old.endMillis ?: return false
        val newEnd = new.endMillis ?: return true
        return newEnd > oldEnd
    }
}
