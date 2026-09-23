package app.dak.automation

import app.dak.automations.birthdays.WishTag
import app.dak.automations.broadcast.BroadcastTag

/**
 * Who put a scheduled send in the queue, read from its `ruleId` tag. It decides what the executor re-checks at send
 * time: an unattended send (nobody confirms it when it goes out) needs an app lock at that moment, like the automation
 * that queued it, because the lock can be removed between queueing and sending.
 */
enum class ScheduledSendOrigin(val unattended: Boolean) {
    /** Scheduled by the user in the composer (no tag). */
    USER(false),
    /** A copy of a broadcast the user sent or scheduled. */
    BROADCAST(false),
    /** A birthday prompt ("Ask me first"): posts a notification, sends nothing by itself. */
    BIRTHDAY_ASK(false),
    /** A birthday wish the user confirmed from the prompt. */
    BIRTHDAY_CONFIRMED(false),
    /** A birthday wish in automatic mode. */
    BIRTHDAY_AUTO(true),
    /** An auto-reply rule's reply. */
    AUTO_REPLY(true),
    /** An auto-forward held for the next free sending slot. */
    AUTO_FORWARD(true),
    /** Any other tag: treated as rule-driven (fail closed). */
    OTHER_RULE(true),
    ;

    companion object {
        fun of(ruleId: String?): ScheduledSendOrigin {
            if (ruleId == null) return USER
            if (BroadcastTag.isBroadcast(ruleId)) return BROADCAST
            WishTag.decode(ruleId)?.let { tag ->
                return when {
                    tag.ask -> BIRTHDAY_ASK
                    tag.confirmed -> BIRTHDAY_CONFIRMED
                    else -> BIRTHDAY_AUTO
                }
            }
            return when (ruleId) {
                ScheduledSendScheduler.AUTO_REPLY_TAG -> AUTO_REPLY
                ScheduledSendScheduler.AUTO_FORWARD_TAG -> AUTO_FORWARD
                else -> OTHER_RULE
            }
        }
    }
}

/** Send-time decisions of [ScheduledSendExecutor] for unattended sends, kept pure for unit tests. */
object ScheduledSendGuard {
    /**
     * True when a due send must be cancelled because no app lock is set up now. Automatic birthday wishes are not
     * cancelled here: [app.dak.birthdays.BirthdaySendGate] turns them into an "Ask me first" prompt instead, so the
     * user can still send the wish by hand. [lockReady] is only called for unattended sends.
     */
    fun cancelForNoLock(origin: ScheduledSendOrigin, lockReady: () -> Boolean): Boolean =
        origin.unattended && origin != ScheduledSendOrigin.BIRTHDAY_AUTO && !lockReady()
}
