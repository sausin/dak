package app.dak.automation

import android.content.Context
import app.dak.R
import app.dak.index.repo.AuditLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caps on SMS that automations send without the user (forwards and auto-replies). A crafted or looping conversation
 * could otherwise make Dak send without end: [SendThrottle] only spreads sends out, it never drops them.
 *
 * - At most [DAILY_LIMIT] unattended sends per local day; past it they are skipped, audit-logged and the user is
 *   notified once that day.
 * - At most one auto-reply per sender per [REPLY_COOLDOWN_MILLIS], so two phones with "reply to everything" rules
 *   cannot ping-pong.
 */
@Singleton
class UnattendedSendLimits @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audit: AuditLogRepository,
    private val notifications: AutomationNotifications,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val replies = ReplyCooldown(REPLY_COOLDOWN_MILLIS, MAX_TRACKED_SENDERS)

    /** Takes one unattended send from today's budget; false (and logged) when it is spent. */
    suspend fun tryConsume(kind: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault()).toEpochDay()
        val decision = synchronized(this) {
            val budget = UnattendedBudget(prefs.getLong(KEY_DAY, Long.MIN_VALUE), prefs.getInt(KEY_COUNT, 0), prefs.getLong(KEY_NOTIFIED_DAY, Long.MIN_VALUE))
            val next = budget.consume(today, DAILY_LIMIT)
            prefs.edit()
                .putLong(KEY_DAY, next.budget.day)
                .putInt(KEY_COUNT, next.budget.count)
                .putLong(KEY_NOTIFIED_DAY, next.budget.notifiedDay)
                .apply()
            next
        }
        if (!decision.allowed) {
            audit.log("automation", "automation.skipped", detail = "$kind: daily limit of $DAILY_LIMIT unattended sends reached")
            if (decision.notify) {
                notifications.post(
                    context.getString(R.string.fw_limit_title),
                    context.getString(R.string.fw_limit_text, DAILY_LIMIT),
                )
            }
        }
        return decision.allowed
    }

    /** True when an auto-reply to [address] may go now (and records it); false inside the per-sender cooldown. */
    fun allowReply(address: String, nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(this) {
        replies.allow(replyKey(address), nowMillis)
    }

    companion object {
        const val DAILY_LIMIT = 150
        const val REPLY_COOLDOWN_MILLIS = 30 * 60_000L
        private const val MAX_TRACKED_SENDERS = 500
        private const val PREFS = "dak_unattended_sends"
        private const val KEY_DAY = "day"
        private const val KEY_COUNT = "count"
        private const val KEY_NOTIFIED_DAY = "notifiedDay"

        /** The same sender however it is written: the last 10 digits of a number, else the trimmed, lowercased id. */
        fun replyKey(address: String): String = ReplyCooldown.keyOf(address)
    }
}
