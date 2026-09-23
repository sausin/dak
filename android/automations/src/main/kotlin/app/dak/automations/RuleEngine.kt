package app.dak.automations

import app.dak.automations.rule.Condition
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import app.dak.automations.rule.conditionsCanMatchOtp
import app.dak.automations.rule.isForwardingOrRelay
import app.dak.automations.safety.Addresses
import app.dak.automations.safety.ForwardLoopGuard
import app.dak.automations.safety.RegexSafety
import app.dak.core.model.TransactionDirection
import java.time.Instant
import java.time.ZoneOffset
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Pure, deterministic evaluation of automation rules against one incoming message. No I/O, no clock
 * reads beyond what [MessageEvent] and rules already carry, no side effects: it only decides what
 * *should* happen, as a list of [PlannedAction]s. Executing them is `app.dak.automations.action.ActionRegistry`'s job.
 */
public object RuleEngine {

    /** Body/sender text longer than this is truncated before regex matching, so a pathological regex on a
     * huge MMS-derived body cannot blow up evaluation time. */
    public const val MAX_REGEX_INPUT_LENGTH: Int = 4_000

    /**
     * Rules are evaluated in list order; each enabled rule whose [Rule.trigger] matches [event] and whose
     * [Rule.conditions] hold contributes one [PlannedAction] per [Rule.actions] entry, in order.
     * [Trigger.Schedule] rules never match a message event (they are driven by a separate scheduler) and
     * so never contribute here. Forwards that would loop (see [ForwardLoopGuard]) are dropped.
     */
    public fun evaluate(event: MessageEvent, rules: List<Rule>): List<PlannedAction> {
        val planned = mutableListOf<PlannedAction>()
        for (rule in rules) {
            if (!rule.enabled) continue
            if (!triggerMatches(rule.trigger, event)) continue
            if (!evaluateCondition(rule.conditions, event)) continue
            val requiresBiometric = conditionsCanMatchOtp(rule.conditions)
            for (action in rule.actions) {
                if (ForwardLoopGuard.shouldSkip(action, event)) continue
                val flag = requiresBiometric && action.isForwardingOrRelay()
                planned += PlannedAction(rule.id, rule.name, action, requiresBiometricConfirmation = flag)
            }
        }
        return planned
    }

    private fun triggerMatches(trigger: Trigger, event: MessageEvent): Boolean = when (trigger) {
        is Trigger.MessageReceived -> trigger.predicates?.let { evaluateCondition(it, event) } ?: true
        is Trigger.Keyword -> containsIgnoreCaseAware(event.body, trigger.keyword, trigger.ignoreCase)
        is Trigger.Schedule -> false
        is Trigger.Unknown -> false
    }

    public fun evaluateCondition(condition: Condition, event: MessageEvent): Boolean = when (condition) {
        is Condition.All -> condition.children.all { evaluateCondition(it, event) }
        is Condition.Any -> condition.children.any { evaluateCondition(it, event) }
        is Condition.Not -> !evaluateCondition(condition.child, event)
        is Condition.SenderIs -> event.address.equals(condition.value, ignoreCase = true) ||
            (event.mergeKey != null && event.mergeKey.equals(condition.value, ignoreCase = true))
        is Condition.SenderMatches -> matchesRegex(event.address, condition.pattern)
        is Condition.CategoryIs -> event.category == condition.category
        is Condition.SimIs -> (condition.subId != null && condition.subId == event.subId) ||
            (condition.slot != null && condition.slot == event.slot)
        is Condition.BodyContains -> containsIgnoreCaseAware(event.body, condition.keyword, condition.ignoreCase)
        is Condition.BodyMatches -> matchesRegex(event.body, condition.pattern)
        is Condition.AmountAtLeast -> amountSatisfies(event, condition.amountMinor, condition.currency) { a, b -> a >= b }
        is Condition.AmountAtMost -> amountSatisfies(event, condition.amountMinor, condition.currency) { a, b -> a <= b }
        is Condition.TimeWindow -> withinTimeWindow(event.dateMillis, condition.fromMinuteOfDay, condition.toMinuteOfDay)
        is Condition.DirectionIs -> event.transaction?.direction == condition.direction
        is Condition.HasOtp -> event.otp != null
        is Condition.ActiveBetween -> event.dateMillis >= condition.startMillis &&
            (condition.endMillis == null || event.dateMillis <= condition.endMillis)
        is Condition.SenderInGroups -> inGroups(condition, event)
        is Condition.Unknown -> false
    }

    private fun inGroups(condition: Condition.SenderInGroups, event: MessageEvent): Boolean {
        val mergeKey = event.mergeKey
        if (mergeKey != null && condition.mergeKeys.any { it.equals(mergeKey, ignoreCase = true) }) return true
        val conversationId = event.conversationId
        if (conversationId != null && conversationId in condition.conversationIds) return true
        return condition.addresses.any { Addresses.same(it, event.address) }
    }

    private fun amountSatisfies(
        event: MessageEvent,
        thresholdMinor: Long,
        currency: String?,
        compare: (Long, Long) -> Boolean,
    ): Boolean {
        val tx = event.transaction ?: return false
        if (currency != null && !tx.currency.equals(currency, ignoreCase = true)) return false
        return compare(tx.amountMinor, thresholdMinor)
    }

    private fun withinTimeWindow(dateMillis: Long, fromMinute: Int, toMinute: Int): Boolean {
        val minuteOfDay = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).run { hour * 60 + minute }
        return if (fromMinute <= toMinute) {
            minuteOfDay in fromMinute..toMinute
        } else {
            // Window wraps past midnight, e.g. 22:00..06:00.
            minuteOfDay >= fromMinute || minuteOfDay <= toMinute
        }
    }

    private fun containsIgnoreCaseAware(haystack: String, needle: String, ignoreCase: Boolean): Boolean =
        haystack.contains(needle, ignoreCase = ignoreCase)

    private fun matchesRegex(input: String, pattern: String): Boolean {
        val bounded = if (input.length > MAX_REGEX_INPUT_LENGTH) input.substring(0, MAX_REGEX_INPUT_LENGTH) else input
        // A pattern that can backtrack exponentially never runs against sender-controlled text (rules saved before
        // this check existed are caught here; the validator reports them in the editor).
        if (!RegexSafety.isSafe(pattern)) return false
        return try {
            Pattern.compile(pattern).matcher(bounded).find()
        } catch (e: PatternSyntaxException) {
            false
        }
    }
}
