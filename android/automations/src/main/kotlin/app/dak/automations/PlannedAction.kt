package app.dak.automations

import app.dak.automations.rule.ActionSpec

/**
 * One action the [RuleEngine] decided should run for a given [MessageEvent], produced by a matching
 * [app.dak.automations.rule.Rule]. Purely descriptive: nothing about planning an action executes it —
 * see `app.dak.automations.action.ActionRegistry`.
 */
public data class PlannedAction(
    val ruleId: String,
    val ruleName: String,
    val action: ActionSpec,
    /** True for a forwarding/relay action whose conditions can match OTPs; see [RuleEngine] and `RuleValidator`. */
    val requiresBiometricConfirmation: Boolean = false,
)
