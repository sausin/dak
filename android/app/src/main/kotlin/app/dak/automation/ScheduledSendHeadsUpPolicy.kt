package app.dak.automation

import app.dak.automations.broadcast.BroadcastTag
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/*
 * Pure decisions behind the "heads-up before a scheduled message goes out" notifications ([ScheduledSendHeadsUp]):
 * when to post, which notification id a send gets, how several heads-ups are grouped, what the actions may do, and
 * the refusal of scheduled texts to emergency numbers. No Android types, so all of it is unit-tested on the JVM.
 */

/** What a heads-up is about; decides whether a scheduled send gets one and how its time is computed. */
enum class HeadsUpSubject {
    /** A message the user scheduled, or an automation's scheduled reply. */
    MESSAGE,

    /** A birthday / anniversary wish in automatic mode: also gets a morning heads-up (see [HeadsUpTiming]). */
    AUTOMATIC_BIRTHDAY,

    /** One heads-up per broadcast (not per recipient). */
    BROADCAST,

    /** No heads-up. */
    NONE,
    ;

    companion object {
        fun of(origin: ScheduledSendOrigin): HeadsUpSubject = when (origin) {
            ScheduledSendOrigin.USER, ScheduledSendOrigin.AUTO_REPLY, ScheduledSendOrigin.OTHER_RULE -> MESSAGE
            ScheduledSendOrigin.BROADCAST -> BROADCAST
            ScheduledSendOrigin.BIRTHDAY_AUTO -> AUTOMATIC_BIRTHDAY
            // "Ask me first" wishes already post their own prompt on the day (that prompt IS the heads-up, so the
            // user never gets two notifications for one wish); a confirmed wish goes out the moment it is confirmed;
            // a held auto-forward only waits minutes for a free sending slot.
            ScheduledSendOrigin.BIRTHDAY_ASK, ScheduledSendOrigin.BIRTHDAY_CONFIRMED, ScheduledSendOrigin.AUTO_FORWARD -> NONE
        }
    }
}

/** Outcome of [HeadsUpTiming.decide]. */
sealed interface HeadsUpDecision {
    /** Not yet: look again at [atMillis]. */
    data class WaitUntil(val atMillis: Long) : HeadsUpDecision

    /** Post the heads-up now (its time has come, or already passed while enough time is left to act). */
    data object NotifyNow : HeadsUpDecision

    /** No heads-up for this send (turned off, not wanted for this kind of send, or too close to going out). */
    data object Skip : HeadsUpDecision
}

/**
 * When a heads-up is due.
 *
 * - The lead time comes from the setting (off / 5 / 15 / 60 minutes, default 15).
 * - Automatic birthday wishes get the earlier of "lead before" and [MORNING] on the day of the wish, so a wish that
 *   goes out at 18:00 is announced at 08:00 and the user has the day to change it; a wish at 08:05 is announced at
 *   07:50 (lead only).
 * - When the heads-up time has already passed (scheduled 5 minutes ahead with a 15-minute lead, a reboot, a late
 *   alarm), the heads-up is posted at once as long as at least [MIN_NOTICE_MILLIS] are left; with less than that there
 *   is no time to act, so none is posted.
 */
object HeadsUpTiming {
    const val MIN_NOTICE_MILLIS: Long = 2 * 60_000L
    val MORNING: LocalTime = LocalTime.of(8, 0)

    /** When the heads-up for a send at [sendAtMillis] is due, or null when it gets none. */
    fun headsUpAt(sendAtMillis: Long, leadMinutes: Int?, subject: HeadsUpSubject, zone: ZoneId): Long? {
        if (leadMinutes == null || leadMinutes <= 0 || subject == HeadsUpSubject.NONE) return null
        val beforeLead = sendAtMillis - leadMinutes * 60_000L
        if (subject != HeadsUpSubject.AUTOMATIC_BIRTHDAY) return beforeLead
        val morning = Instant.ofEpochMilli(sendAtMillis).atZone(zone).toLocalDate()
            .atTime(MORNING).atZone(zone).toInstant().toEpochMilli()
        return minOf(beforeLead, morning)
    }

    fun decide(headsUpAtMillis: Long?, sendAtMillis: Long, nowMillis: Long): HeadsUpDecision = when {
        headsUpAtMillis == null -> HeadsUpDecision.Skip
        sendAtMillis - nowMillis < MIN_NOTICE_MILLIS -> HeadsUpDecision.Skip
        nowMillis < headsUpAtMillis -> HeadsUpDecision.WaitUntil(headsUpAtMillis)
        else -> HeadsUpDecision.NotifyNow
    }
}

/**
 * Stable keys and notification ids. A send's key is `s:<scheduled send id>`; every copy of a broadcast shares the key
 * `b:<broadcast id>` (one heads-up per broadcast). Each heads-up is posted with tag [tagOf] and id [notificationId],
 * both derived from the key, so an update replaces the notification instead of stacking a new one.
 */
object HeadsUpKeys {
    private const val SEND = "s:"
    private const val BROADCAST = "b:"
    private const val TAG_PREFIX = "scheduled-heads-up:"
    private const val BASE_ID = 70_000
    private const val RANGE = 20_000

    /** Id of the group summary (tag [SUMMARY_TAG]). */
    const val SUMMARY_ID: Int = BASE_ID - 1
    const val SUMMARY_TAG: String = "scheduled-heads-up-summary"

    fun forSend(sendId: Long): String = SEND + sendId

    fun forBroadcast(broadcastId: String): String = BROADCAST + broadcastId

    fun of(sendId: Long, ruleId: String?): String =
        BroadcastTag.decode(ruleId)?.let { forBroadcast(it.broadcastId) } ?: forSend(sendId)

    fun sendIdOf(key: String): Long? = if (key.startsWith(SEND)) key.removePrefix(SEND).toLongOrNull() else null

    fun broadcastIdOf(key: String): String? = if (key.startsWith(BROADCAST)) key.removePrefix(BROADCAST).takeIf { it.isNotEmpty() } else null

    fun tagOf(key: String): String = TAG_PREFIX + key

    /** The key of one of our heads-up tags, else null (other Dak notifications, the summary). */
    fun keyOfTag(tag: String?): String? = tag?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)?.takeIf { it.isNotEmpty() }

    /** Stable id in a range no other Dak notification uses: from the send id, or from the broadcast id's hash. */
    fun notificationId(key: String): Int {
        sendIdOf(key)?.let { return BASE_ID + Math.floorMod(it, RANGE.toLong()).toInt() }
        return BASE_ID + RANGE + Math.floorMod(key.hashCode(), RANGE)
    }
}

/** A heads-up candidate: one pending send, or one broadcast (all its pending copies). */
data class HeadsUpItem(
    val key: String,
    val sendAtMillis: Long,
    /**
     * Changes when the user moves the send, which makes a new heads-up due: the send time for a single send; the
     * broadcast's scheduled start for a broadcast (copies going out one by one do not change it).
     */
    val version: Long,
    val headsUpAtMillis: Long?,
)

/** A heads-up already posted for [version]. [collapsed]: listed in the summary only (see [HeadsUpPlanner]). */
data class HeadsUpAnnounced(val version: Long, val collapsed: Boolean)

/** A child notification to (re)post; [silent] for one that was already announced (moved out of the summary). */
data class HeadsUpPost(val key: String, val silent: Boolean)

/** What [ScheduledSendHeadsUp] does after one look at the pending sends. */
data class HeadsUpPlan(
    val post: List<HeadsUpPost>,
    /** Child notifications to remove (sent, cancelled, moved, collapsed into the summary, or stale). */
    val cancel: Set<String>,
    /** The new announced state to persist. */
    val announced: Map<String, HeadsUpAnnounced>,
    /** Keys listed in the group summary, soonest first; empty when there is no summary (fewer than two). */
    val summaryKeys: List<String>,
    /** True when the summary itself should alert (a new heads-up went straight into it). */
    val summaryAlerts: Boolean,
    /** When to look again (the next heads-up due), or null for nothing to wait for. */
    val nextWakeAtMillis: Long?,
)

/**
 * Keeps several heads-ups tidy:
 * - each pending send (or broadcast) is announced once per [HeadsUpItem.version]; a heads-up the user swiped away is
 *   not posted again, but one for a moved send (Delay, Pick time, edit) is;
 * - a heads-up disappears as soon as its send is no longer pending (sent, failed, cancelled) or was moved;
 * - at most [MAX_INDIVIDUAL] heads-ups show individually (the soonest); the rest are listed only in the group summary,
 *   which exists while two or more heads-ups are showing.
 */
object HeadsUpPlanner {
    const val MAX_INDIVIDUAL: Int = 5

    /**
     * @param items pending candidates (one per key).
     * @param announced what was announced so far.
     * @param visible keys whose child notification is showing right now.
     */
    fun plan(
        items: List<HeadsUpItem>,
        announced: Map<String, HeadsUpAnnounced>,
        visible: Set<String>,
        nowMillis: Long,
        maxIndividual: Int = MAX_INDIVIDUAL,
    ): HeadsUpPlan {
        val byKey = items.associateBy { it.key }
        // Still valid: the send is pending, unmoved, and heads-ups are still wanted for it.
        val kept = announced.filter { (key, state) ->
            val item = byKey[key]
            item != null && item.version == state.version && item.headsUpAtMillis != null
        }
        val fresh = LinkedHashSet<String>()
        var nextWake: Long? = null
        for (item in items.sortedWith(compareBy({ it.sendAtMillis }, { it.key }))) {
            if (item.key in kept) continue
            when (val decision = HeadsUpTiming.decide(item.headsUpAtMillis, item.sendAtMillis, nowMillis)) {
                HeadsUpDecision.NotifyNow -> fresh += item.key
                is HeadsUpDecision.WaitUntil -> nextWake = minOf(nextWake ?: Long.MAX_VALUE, decision.atMillis)
                HeadsUpDecision.Skip -> Unit
            }
        }
        // Posted individually before and no longer showing: the user swiped it away. Never shown again.
        val dismissed = kept.filter { (key, state) -> !state.collapsed && key !in visible }.keys
        val shown = (kept.keys + fresh)
            .filter { it !in dismissed }
            .mapNotNull { byKey[it] }
            .sortedWith(compareBy({ it.sendAtMillis }, { it.key }))
            .map { it.key }
        val individual = shown.take(maxIndividual.coerceAtLeast(1)).toSet()
        val collapsed = shown.drop(maxIndividual.coerceAtLeast(1)).toSet()

        val post = shown.filter { it in individual }.mapNotNull { key ->
            when {
                key in fresh -> HeadsUpPost(key, silent = false)
                kept[key]?.collapsed == true -> HeadsUpPost(key, silent = true)
                else -> null // already showing
            }
        }
        val next = LinkedHashMap<String, HeadsUpAnnounced>()
        for ((key, state) in kept) next[key] = HeadsUpAnnounced(state.version, collapsed = key in collapsed)
        for (key in fresh) next[key] = HeadsUpAnnounced(byKey.getValue(key).version, collapsed = key in collapsed)
        return HeadsUpPlan(
            post = post,
            cancel = visible - individual,
            announced = next,
            summaryKeys = if (shown.size >= 2) shown else emptyList(),
            summaryAlerts = fresh.any { it in collapsed },
            nextWakeAtMillis = nextWake,
        )
    }
}

/** The heads-up's actions. */
enum class HeadsUpAction(val wire: String) {
    SEND_NOW("send_now"),

    /** Swaps the notification's actions for the delay choices (+1 hour, Tomorrow same time, Pick time). */
    DELAY_MENU("delay_menu"),
    DELAY_HOUR("delay_hour"),
    DELAY_TOMORROW("delay_tomorrow"),
    CANCEL("cancel"),
    ;

    companion object {
        fun fromWire(value: String?): HeadsUpAction? = entries.firstOrNull { it.wire == value }
    }
}

/** Why an action was not carried out. */
enum class HeadsUpActionCheck { OK, GONE, NOT_PENDING }

/**
 * What the heads-up's actions may do. Every action re-reads the row and only acts on one that still exists and is
 * PENDING (a stale notification must not resurrect a sent or cancelled message). "Send now" moves the send to now and
 * runs the normal executor, with all of its gates (app lock for unattended sends, premium-rate guard, daily
 * unattended cap, default-SMS-app hold); Delay and Cancel only make sending later or never, so they need no unlock.
 */
object HeadsUpActions {
    const val HOUR_MILLIS: Long = 60 * 60_000L

    fun check(exists: Boolean, pending: Boolean): HeadsUpActionCheck = when {
        !exists -> HeadsUpActionCheck.GONE
        !pending -> HeadsUpActionCheck.NOT_PENDING
        else -> HeadsUpActionCheck.OK
    }

    /** The new send time for a Delay action; never earlier than an hour from now. */
    fun delayedTo(action: HeadsUpAction, sendAtMillis: Long, nowMillis: Long, zone: ZoneId): Long? = when (action) {
        HeadsUpAction.DELAY_HOUR -> maxOf(sendAtMillis, nowMillis) + HOUR_MILLIS
        // Same wall-clock time on the next day (DST-safe), measured from the planned time.
        HeadsUpAction.DELAY_TOMORROW -> maxOf(
            Instant.ofEpochMilli(sendAtMillis).atZone(zone).plusDays(1).toInstant().toEpochMilli(),
            nowMillis + HOUR_MILLIS,
        )
        HeadsUpAction.SEND_NOW, HeadsUpAction.DELAY_MENU, HeadsUpAction.CANCEL -> null
    }

    /**
     * New times for every pending copy of a broadcast when its start moves from [oldStartMillis] to [newStartMillis]:
     * the pacing between copies is kept.
     */
    fun shifted(sendTimes: List<Long>, oldStartMillis: Long, newStartMillis: Long): List<Long> {
        val delta = newStartMillis - oldStartMillis
        return sendTimes.map { it + delta }
    }
}

/**
 * Scheduled texts to emergency numbers are not allowed: emergency services need to hear from the user now, and a
 * text that waits (or goes out while the user is not there to follow up) helps no one. Every scheduling path refuses
 * them up front; a legacy PENDING row that includes one is cancelled when it falls due instead of being sent.
 * Immediate texts to emergency numbers are unaffected.
 */
object ScheduledEmergencyPolicy {
    /** [ScheduledSend.failureReason] of a legacy row cancelled at send time. */
    const val CANCEL_REASON: String = "emergency number: scheduled texts to emergency services are not allowed"

    /** True when any of [addresses] is an emergency number (a failing check counts as "not emergency"). */
    fun refuses(addresses: List<String>, isEmergency: (String) -> Boolean): Boolean =
        firstEmergency(addresses, isEmergency) != null

    /** The first emergency number among [addresses], or null. */
    fun firstEmergency(addresses: List<String>, isEmergency: (String) -> Boolean): String? =
        addresses.firstOrNull { address -> runCatching { isEmergency(address) }.getOrDefault(false) }
}

/** Thrown by [ScheduledSendScheduler.schedule] for a send to an emergency number (see [ScheduledEmergencyPolicy]). */
class EmergencyScheduleRefusedException : IllegalArgumentException("scheduled texts to emergency numbers are not allowed")
