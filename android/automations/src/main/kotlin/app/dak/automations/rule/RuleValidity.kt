package app.dak.automations.rule

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
