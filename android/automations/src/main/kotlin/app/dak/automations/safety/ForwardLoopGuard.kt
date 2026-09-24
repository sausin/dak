package app.dak.automations.safety

import app.dak.automations.MessageEvent
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.RelayChannel

/**
 * Runtime loop protection for SMS forwards, applied by [app.dak.automations.RuleEngine] to every
 * [ActionSpec.ForwardSms] (and SMS [ActionSpec.RelayRule]) before it is planned:
 * - never forward a message *from* the forward's recipient (A forwards to B, B's reply would bounce back);
 * - never forward a message that already looks like a forward made by Dak, i.e. whose body starts with the literal
 *   prefix of the action's own template (`"Fwd from"` for the default), of the English default template (rules
 *   stored before the prefix was translated, and Dak users in English), or of the default template in any language
 *   the app ships ([registerDefaultTemplates]) — this breaks A→B→A chains when both ends run forwarding rules, even
 *   when the two phones run Dak in different languages.
 *
 * Messages we send ourselves never reach the engine at all (only inbox messages are evaluated).
 */
public object ForwardLoopGuard {

    /** Minimum length of a template's literal prefix for it to count as a "this was forwarded" marker. */
    private const val MIN_PREFIX_LENGTH = 3

    /** The English default template's marker ("Fwd from"): always recognised, whatever the rule's own template. */
    public val DEFAULT_MARKER: String = checkNotNull(templatePrefix(ForwardingSpec.DEFAULT_TEMPLATE))

    @Volatile
    private var registeredMarkers: Set<String> = emptySet()

    /**
     * Registers the default forward template of every language the app ships (the app calls this at start and on a
     * language change), so a forward made by Dak in another language is recognised too. Replaces the previous set.
     */
    public fun registerDefaultTemplates(templates: Collection<String>) {
        registeredMarkers = templates.mapNotNull(::templatePrefix).toSet()
    }

    /** Every marker a body is checked against for a rule whose template is [template]. */
    public fun markersFor(template: String): Set<String> =
        buildSet {
            templatePrefix(template)?.let(::add)
            add(DEFAULT_MARKER)
            addAll(registeredMarkers)
        }

    /** True when [action] must not run for [event]. Non-forwarding actions are never skipped. */
    public fun shouldSkip(action: ActionSpec, event: MessageEvent): Boolean = when (action) {
        is ActionSpec.ForwardSms -> isLoop(action.to, action.template, event)
        is ActionSpec.RelayRule -> action.channel == RelayChannel.SMS && isLoop(action.recipient, action.template, event)
        else -> false
    }

    private fun isLoop(recipient: String, template: String, event: MessageEvent): Boolean {
        if (Addresses.same(event.address, recipient)) return true
        val body = event.body.trimStart()
        return markersFor(template).any { body.startsWith(it, ignoreCase = true) }
    }

    /**
     * The literal text before a template's first `{placeholder}`, trimmed, or null when it is too short to be a
     * reliable marker (e.g. the raw `"{body}"` template has no prefix at all).
     */
    public fun templatePrefix(template: String): String? {
        val open = template.indexOf('{')
        val literal = (if (open < 0) template else template.substring(0, open)).trim()
        return literal.takeIf { it.length >= MIN_PREFIX_LENGTH }
    }
}

/** Address comparison shared by the loop guard, the validator and [app.dak.automations.rule.Condition.SenderInGroups]. */
public object Addresses {
    private const val MIN_PHONE_DIGITS = 7
    private const val SIGNIFICANT_DIGITS = 10

    /**
     * Phone numbers compare on their last 10 digits (so `+91 98765 43210`, `098765 43210` and `9876543210` are
     * the same); anything else (alphanumeric sender ids such as `VM-HDFCBK`) compares case-insensitively, trimmed.
     */
    public fun same(a: String, b: String): Boolean {
        val da = phoneDigits(a)
        val db = phoneDigits(b)
        if (da != null && db != null) return da == db
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    /** Last 10 digits of a phone-number-looking address, or null for alphanumeric / short-code senders. */
    public fun phoneDigits(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.any { it.isLetter() }) return null
        val digits = trimmed.filter { it in '0'..'9' }
        if (digits.length < MIN_PHONE_DIGITS) return null
        return if (digits.length > SIGNIFICANT_DIGITS) digits.takeLast(SIGNIFICANT_DIGITS) else digits
    }
}
