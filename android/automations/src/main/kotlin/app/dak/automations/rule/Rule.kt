package app.dak.automations.rule

import kotlinx.serialization.Serializable

/** Current [Rule.schemaVersion]. Bump only for a breaking AST change; additive fields do not need a bump. */
public const val CURRENT_RULE_SCHEMA_VERSION: Int = 1

/**
 * A user-authored automation rule: a small versioned JSON AST, evaluated by the pure [app.dak.automations.RuleEngine].
 *
 * @param conditions defaults to [Condition.All] of an empty list, i.e. "always true", so a rule with no
 *   extra conditions fires on every event its [trigger] matches.
 * @param meta free-form UI metadata (e.g. `kind = forwarding` plus display names), never evaluated. Additive:
 *   older JSON without it decodes to an empty map, and older builds ignore it.
 */
@Serializable
public data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val conditions: Condition = Condition.All(emptyList()),
    val actions: List<ActionSpec>,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = CURRENT_RULE_SCHEMA_VERSION,
    val meta: Map<String, String> = emptyMap(),
)
