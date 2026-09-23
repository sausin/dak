package app.dak.automations.rule

import app.dak.automations.forwarding.ForwardingPolicy

/**
 * The rule's validity window: a [Condition.ActiveBetween] among its top-level conjuncts (see [topLevelConjuncts]),
 * or null for a rule that is always valid. A window nested under `Any`/`Not` still filters messages but does not
 * make the rule expire.
 */
public fun Rule.activeWindow(): Condition.ActiveBetween? =
    topLevelConjuncts(conditions).firstNotNullOfOrNull { it as? Condition.ActiveBetween }

/** True once [nowMillis] is past the rule's inclusive end; such a rule can never match again and should be disabled. */
public fun Rule.isExpired(nowMillis: Long): Boolean {
    val end = activeWindow()?.endMillis ?: return false
    return nowMillis > end
}

/** True while [nowMillis] is before the rule's start. */
public fun Rule.isNotStartedYet(nowMillis: Long): Boolean {
    val window = activeWindow() ?: return false
    return nowMillis < window.startMillis
}

/**
 * The rule with its validity window moved to start at [nowMillis] and last as long as it did before
 * ([ForwardingPolicy.restartedWindow]): "use again" for an ended time-boxed rule. A rule without a window is returned
 * unchanged.
 */
public fun Rule.withRestartedWindow(nowMillis: Long): Rule {
    val window = activeWindow() ?: return this
    val (start, end) = ForwardingPolicy.restartedWindow(window.startMillis, window.endMillis, nowMillis)
    return copy(conditions = replaceTopLevelWindow(conditions, Condition.ActiveBetween(start, end)), updatedAt = nowMillis)
}

private fun replaceTopLevelWindow(condition: Condition, window: Condition.ActiveBetween): Condition = when (condition) {
    is Condition.All -> Condition.All(condition.children.map { replaceTopLevelWindow(it, window) })
    is Condition.ActiveBetween -> window
    else -> condition
}
