package app.dak.automations.broadcast

import app.dak.automations.birthdays.WishTemplates

/** Why a list member does not get this broadcast. */
public enum class ExclusionReason {
    /** Same number as an earlier member (formatting / country code ignored). */
    DUPLICATE,

    /** On the phone's block list. */
    BLOCKED,

    /** One of the user's own numbers. */
    OWN_NUMBER,

    /** A short code (service number, possibly premium-rate). */
    SHORT_CODE,

    /** An alphanumeric sender id, which cannot receive SMS. */
    ALPHANUMERIC,

    /** Premium-rate or another special-tariff / service destination (the app's cost classifier said so). */
    SPECIAL_TARIFF,

    /** Nothing dialable. */
    INVALID,
}

/** Why a whole broadcast cannot be sent as planned. */
public sealed interface PlanProblem {
    public data object EmptyMessage : PlanProblem
    public data object NoRecipients : PlanProblem
    public data class TooManyRecipients(val count: Int, val max: Int) : PlanProblem
    public data class DailyLimit(val requested: Int, val remaining: Int, val max: Int) : PlanProblem
    public data object ScheduledInPast : PlanProblem
    public data class ScheduledTooFar(val maxAheadMillis: Long) : PlanProblem
}

/**
 * Inputs for [BroadcastPlanner.plan].
 *
 * @property template message text; `{firstName}` and `{name}` are filled per recipient (empty for a raw number).
 * @property scheduledAtMillis the single optional send time (one-shot only), or null for "send now".
 * @property ownNumbers the user's own SIM numbers, never messaged.
 * @property isBlocked the phone's block list.
 * @property isRefusedDestination premium-rate / special-tariff check (the app wraps its cost classifier).
 * @property usedToday broadcast copies already sent in the last 24 hours ([BroadcastQuota.usedInLastDay]).
 * @property recentSendHistory recent real send times, so pacing also respects sends made outside this broadcast.
 */
public data class BroadcastRequest(
    val members: List<Member>,
    val template: String,
    val nowMillis: Long,
    val scheduledAtMillis: Long? = null,
    val ownNumbers: Collection<String> = emptyList(),
    val isBlocked: (String) -> Boolean = { false },
    val isRefusedDestination: (String) -> Boolean = { false },
    val usedToday: Int = 0,
    val recentSendHistory: List<Long> = emptyList(),
)

/** One copy to send. */
public data class PlannedCopy(val member: Member, val text: String, val sendAtMillis: Long)

/** A member left out, and why. */
public data class Excluded(val member: Member, val reason: ExclusionReason)

/**
 * The result of planning a broadcast: who gets which text when, who is left out, the spam-risk assessment and any
 * blocking [problems]. Only send when [canSend].
 */
public data class BroadcastPlan(
    val copies: List<PlannedCopy>,
    val excluded: List<Excluded>,
    val risk: SpamRisk,
    val problems: List<PlanProblem>,
    val startAtMillis: Long,
) {
    public val canSend: Boolean get() = problems.isEmpty() && copies.isNotEmpty()

    /** When the last copy is due. */
    public val finishAtMillis: Long get() = copies.maxOfOrNull { it.sendAtMillis } ?: startAtMillis

    /** How long spreading takes from the first copy to the last (0 when everything goes in one batch). */
    public val spreadMillis: Long get() = finishAtMillis - startAtMillis

    /** A typed / ticked "These people expect this message" is required before sending. */
    public val requiresExtraConfirmation: Boolean get() = risk.requiresExtraConfirmation
}

/**
 * Builds a [BroadcastPlan]: dedupes numbers, drops blocked, own, short-code, alphanumeric and special-tariff
 * destinations, renders each copy, spreads send times ([BroadcastPacing]) and enforces [BroadcastLimits]. A list over
 * the cap or the daily quota is refused as a whole (never silently truncated): the user decides whom to drop.
 */
public class BroadcastPlanner(
    private val limits: BroadcastLimits = BroadcastLimits(),
    private val pacing: BroadcastPacing = BroadcastPacing(),
) {
    public fun plan(request: BroadcastRequest): BroadcastPlan {
        val (kept, excluded) = filterMembers(request)
        val problems = ArrayList<PlanProblem>()
        if (request.template.isBlank()) problems += PlanProblem.EmptyMessage
        if (kept.isEmpty()) problems += PlanProblem.NoRecipients
        if (kept.size > limits.maxRecipients) problems += PlanProblem.TooManyRecipients(kept.size, limits.maxRecipients)
        val remaining = (limits.maxMessagesPerDay - request.usedToday).coerceAtLeast(0)
        if (kept.size > remaining) problems += PlanProblem.DailyLimit(kept.size, remaining, limits.maxMessagesPerDay)
        val scheduled = request.scheduledAtMillis
        if (scheduled != null) {
            if (scheduled < request.nowMillis - PAST_GRACE_MILLIS) problems += PlanProblem.ScheduledInPast
            if (scheduled > request.nowMillis + limits.maxScheduleAheadMillis) {
                problems += PlanProblem.ScheduledTooFar(limits.maxScheduleAheadMillis)
            }
        }
        val start = maxOf(scheduled ?: request.nowMillis, request.nowMillis)
        val times = pacing.schedule(kept.size, start, request.recentSendHistory)
        val copies = kept.mapIndexed { i, member -> PlannedCopy(member, render(request.template, member), times[i]) }
        return BroadcastPlan(
            copies = copies,
            excluded = excluded,
            risk = SpamRiskAssessor.assess(request.template, kept.size),
            problems = problems,
            startAtMillis = start,
        )
    }

    private fun filterMembers(request: BroadcastRequest): Pair<List<Member>, List<Excluded>> {
        val kept = ArrayList<Member>()
        val excluded = ArrayList<Excluded>()
        for (member in request.members) {
            val address = member.address.trim()
            val reason = when {
                PhoneKey.isInvalid(address) -> ExclusionReason.INVALID
                PhoneKey.isAlphanumeric(address) -> ExclusionReason.ALPHANUMERIC
                PhoneKey.isShortCode(address) -> ExclusionReason.SHORT_CODE
                kept.any { PhoneKey.same(it.address, address) } -> ExclusionReason.DUPLICATE
                request.ownNumbers.any { it.isNotBlank() && PhoneKey.same(it, address) } -> ExclusionReason.OWN_NUMBER
                request.isBlocked(address) -> ExclusionReason.BLOCKED
                request.isRefusedDestination(address) -> ExclusionReason.SPECIAL_TARIFF
                else -> null
            }
            if (reason == null) kept += member.copy(address = address) else excluded += Excluded(member, reason)
        }
        return kept to excluded
    }

    public companion object {
        private const val PAST_GRACE_MILLIS = 60_000L

        /**
         * Fills `{firstName}` / `{name}` for [member] (via [WishTemplates.render]). A member without a real name (blank,
         * or just the number) gets empty placeholders, and the surrounding punctuation is tidied ("Hi {firstName}!" ->
         * "Hi!").
         */
        public fun render(template: String, member: Member): String {
            val name = realName(member)
            return WishTemplates.render(template, name = name, firstName = null)
        }

        /** The member's display name when it is a name rather than a number. */
        public fun realName(member: Member): String {
            val n = member.displayName.trim()
            if (n.isEmpty() || n.none { it.isLetter() }) return ""
            return n
        }
    }
}
