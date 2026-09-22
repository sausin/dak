package app.dak.automations.rule

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encodes and decodes [Rule] lists as JSON, for export/import and for storing presets. */
public object RuleCodec {

    /**
     * `ignoreUnknownKeys = true` (unknown fields on a known node are dropped) plus the `Unknown`
     * variants in the AST (an unknown node *type* is preserved) is what makes the format
     * forward-compatible in both directions.
     */
    public val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        isLenient = false
    }

    public fun encode(rules: List<Rule>): String = json.encodeToString(rules)

    public fun encode(rule: Rule): String = json.encodeToString(rule)

    /** @throws kotlinx.serialization.SerializationException on malformed JSON (not on an unknown node type). */
    public fun decode(text: String): List<Rule> = json.decodeFromString(text)

    public fun decodeOne(text: String): Rule = json.decodeFromString(text)
}
