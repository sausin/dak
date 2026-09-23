package app.dak.automations.safety

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.RelayChannel
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import app.dak.premium.Entitlements
import app.dak.premium.Feature
import java.util.regex.PatternSyntaxException

/** One problem found with a [Rule]. Never thrown; [RuleValidator.validate] always returns a (possibly empty) list. */
public sealed interface ValidationIssue {
    public data class InvalidRegex(val location: String, val pattern: String, val error: String) : ValidationIssue
    public data class MissingRecipient(val actionIndex: Int, val actionType: String) : ValidationIssue
    public data class PremiumActionInFreeTier(val actionIndex: Int, val actionType: String, val feature: Feature) :
        ValidationIssue
    public data class ForwardingLoop(val actionIndex: Int, val address: String) : ValidationIssue
    public data object NoActions : ValidationIssue
}

/** Pure validation: no I/O, no side effects. Everything it needs is passed in. */
public object RuleValidator {

    private val PREMIUM_FEATURE: Map<Class<out ActionSpec>, Feature> = mapOf(
        ActionSpec.Webhook::class.java to Feature.WEBHOOKS,
        ActionSpec.RelayToWebClient::class.java to Feature.WEB_CLIENT,
        ActionSpec.RelayRule::class.java to Feature.RELAY_RULES,
    )

    /**
     * @param ownAddresses the user's own SIM numbers (any normalized form), to catch a forward-to-self loop.
     *
     * A forward whose recipient is also one of the rule's source senders ([Condition.SenderIs] /
     * [Condition.SenderInGroups] addresses) is reported as [ValidationIssue.ForwardingLoop] too.
     */
    public fun validate(rule: Rule, entitlements: Entitlements, ownAddresses: Set<String> = emptySet()): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        collectRegexIssues(rule.trigger.asCondition(), "trigger", issues)
        collectRegexIssues(rule.conditions, "conditions", issues)

        if (rule.actions.isEmpty()) issues += ValidationIssue.NoActions

        val sources = sourceAddresses(rule.conditions) + (rule.trigger.asCondition()?.let(::sourceAddresses) ?: emptyList())

        rule.actions.forEachIndexed { index, action ->
            val actionType = action::class.simpleName ?: "Unknown"

            PREMIUM_FEATURE[action.javaClass]?.let { feature ->
                if (!entitlements.has(feature)) {
                    issues += ValidationIssue.PremiumActionInFreeTier(index, actionType, feature)
                }
            }

            val recipient = recipientOf(action)
            if (recipient != null && recipient.isBlank()) {
                issues += ValidationIssue.MissingRecipient(index, actionType)
            } else if (recipient != null && normalized(recipient) in ownAddresses.map(::normalized)) {
                issues += ValidationIssue.ForwardingLoop(index, recipient)
            } else if (recipient != null && sources.any { Addresses.same(it, recipient) }) {
                issues += ValidationIssue.ForwardingLoop(index, recipient)
            }
        }

        return issues
    }

    /** `null` = this action type has no recipient concept; blank string = recipient required but missing. */
    private fun recipientOf(action: ActionSpec): String? = when (action) {
        is ActionSpec.ForwardSms -> action.to
        is ActionSpec.Webhook -> action.url
        is ActionSpec.RelayToWebClient -> action.pairingId ?: ""
        is ActionSpec.RelayRule -> if (action.channel == RelayChannel.WEBHOOK) null else action.recipient
        else -> null
    }

    /** Raw sender addresses the rule positively selects on (not under a `Not`). */
    private fun sourceAddresses(condition: Condition): List<String> = when (condition) {
        is Condition.SenderIs -> listOf(condition.value)
        is Condition.SenderInGroups -> condition.addresses
        is Condition.All -> condition.children.flatMap(::sourceAddresses)
        is Condition.Any -> condition.children.flatMap(::sourceAddresses)
        else -> emptyList()
    }

    private fun normalized(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    private fun Trigger.asCondition(): Condition? = (this as? Trigger.MessageReceived)?.predicates

    private fun collectRegexIssues(condition: Condition?, location: String, into: MutableList<ValidationIssue>) {
        if (condition == null) return
        when (condition) {
            is Condition.SenderMatches -> checkPattern(condition.pattern, location, into)
            is Condition.BodyMatches -> checkPattern(condition.pattern, location, into)
            is Condition.All -> condition.children.forEach { collectRegexIssues(it, location, into) }
            is Condition.Any -> condition.children.forEach { collectRegexIssues(it, location, into) }
            is Condition.Not -> collectRegexIssues(condition.child, location, into)
            else -> Unit
        }
    }

    private fun checkPattern(pattern: String, location: String, into: MutableList<ValidationIssue>) {
        try {
            java.util.regex.Pattern.compile(pattern)
        } catch (e: PatternSyntaxException) {
            into += ValidationIssue.InvalidRegex(location, pattern, e.message ?: "invalid pattern")
            return
        }
        // Message bodies are attacker-controlled: refuse patterns that can backtrack exponentially (the engine
        // skips them too, see RuleEngine.matchesRegex).
        RegexSafety.problem(pattern)?.let { into += ValidationIssue.InvalidRegex(location, pattern, it) }
    }
}
