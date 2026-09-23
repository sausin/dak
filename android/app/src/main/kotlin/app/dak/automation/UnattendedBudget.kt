package app.dak.automation

// Pure state of [UnattendedSendLimits], kept apart from its Context-bound storage so it is JVM-tested.

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

/**
 * At most one auto-reply per sender key per [cooldownMillis] (see [UnattendedSendLimits.allowReply]). Remembers at
 * most about [maxTracked] senders: past that, entries whose cooldown is over are forgotten. Not thread-safe (the
 * caller synchronizes).
 */
internal class ReplyCooldown(private val cooldownMillis: Long, private val maxTracked: Int) {
    private val lastReplyAt = HashMap<String, Long>()

    /** True when a reply to [key] may go at [nowMillis] (and records it); false inside the cooldown. */
    fun allow(key: String, nowMillis: Long): Boolean {
        val last = lastReplyAt[key]
        if (last != null && nowMillis - last in 0 until cooldownMillis) return false
        lastReplyAt[key] = nowMillis
        if (lastReplyAt.size > maxTracked) lastReplyAt.entries.removeAll { nowMillis - it.value >= cooldownMillis }
        return true
    }

    /** Senders currently remembered (for tests). */
    val trackedCount: Int get() = lastReplyAt.size

    companion object {
        /** The same sender however it is written: the last 10 digits of a number, else the trimmed, lowercased id. */
        fun keyOf(address: String): String {
            val digits = address.filter { it.isDigit() }
            return if (digits.length >= 7) digits.takeLast(10) else address.trim().lowercase()
        }
    }
}
