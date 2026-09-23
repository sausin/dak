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
    private val lastReplyAt = HashMap<String, Long>()

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
        val key = replyKey(address)
        val last = lastReplyAt[key]
        if (last != null && nowMillis - last in 0 until REPLY_COOLDOWN_MILLIS) return false
        lastReplyAt[key] = nowMillis
        if (lastReplyAt.size > MAX_TRACKED_SENDERS) lastReplyAt.entries.removeAll { nowMillis - it.value >= REPLY_COOLDOWN_MILLIS }
        true
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
        fun replyKey(address: String): String {
            val digits = address.filter { it.isDigit() }
            return if (digits.length >= 7) digits.takeLast(10) else address.trim().lowercase()
        }
    }
}

/** Today's unattended-send count ([day] is a local epoch day) and the day the user was last told it ran out. */
internal data class UnattendedBudget(val day: Long, val count: Int, val notifiedDay: Long) {

    data class Decision(val allowed: Boolean, val notify: Boolean, val budget: UnattendedBudget)

    /** One send on [today]: allowed while under [limit]; the first refusal of a day asks for a notification. */
    fun consume(today: Long, limit: Int): Decision {
        val used = if (day == today) count else 0
        if (used < limit) return Decision(allowed = true, notify = false, budget = copy(day = today, count = used + 1))
        val notify = notifiedDay != today
        return Decision(allowed = false, notify = notify, budget = copy(day = today, count = used, notifiedDay = today))
    }
}
