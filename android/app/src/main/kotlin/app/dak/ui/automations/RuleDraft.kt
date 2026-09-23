package app.dak.ui.automations

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.RelayChannel
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import app.dak.core.model.Category
import app.dak.premium.Feature
import java.math.BigDecimal
import java.util.UUID
import java.util.regex.Pattern

/** Actions the simple editor offers. Premium ones are shown locked in the free tier. */
enum class ActionKind(val premium: Feature? = null) {
    LABEL, ARCHIVE, NOTIFY, FORWARD_SMS, SCHEDULE_REPLY, DELETE, OPEN_LINK,
    WEBHOOK(Feature.WEBHOOKS), RELAY(Feature.RELAY_RULES),
}

/**
 * The simple rule editor's model: one trigger (any message or a keyword), a few common conditions combined with
 * AND, and one action. Rules built elsewhere (presets, imports) that do not fit this shape are shown read-only
 * apart from enable/delete ([fromRule] returns null for them).
 */
data class RuleDraft(
    val id: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val keyword: String = "",
    val senderContains: String = "",
    val category: Category? = null,
    /** 0-based SIM slot, or null for any SIM. */
    val simSlot: Int? = null,
    val bodyContains: String = "",
    val onlyOtp: Boolean = false,
    /** Minimum amount in major units as typed ("500"), or blank. */
    val amountAtLeast: String = "",
    val action: ActionKind = ActionKind.LABEL,
    val label: String = "",
    val notifyText: String = "",
    val forwardTo: String = "",
    /** Sending SIM sub id for forwards/replies; null = default SMS SIM. */
    val sendSubId: Int? = null,
    val template: String = "{body}",
    val replyText: String = "",
    val replyDelayMinutes: String = "5",
    val url: String = "",
    val webhookSecret: String = "",
    val relayChannel: RelayChannel = RelayChannel.SMS,
    val createdAt: Long? = null,
) {
    /** Builds the rule AST; null when a required field for the chosen action is missing. */
    fun toRule(now: Long): Rule? {
        val conditions = buildList<Condition> {
            senderContains.trim().takeIf { it.isNotEmpty() }?.let { add(Condition.SenderMatches("(?i)" + Pattern.quote(it))) }
            category?.let { add(Condition.CategoryIs(it)) }
            simSlot?.let { add(Condition.SimIs(slot = it)) }
            bodyContains.trim().takeIf { it.isNotEmpty() }?.let { add(Condition.BodyContains(it)) }
            if (onlyOtp) add(Condition.HasOtp)
            amountMinor()?.let { add(Condition.AmountAtLeast(it)) }
        }
        val spec: ActionSpec = when (action) {
            ActionKind.LABEL -> ActionSpec.Label(label.trim().ifEmpty { return null })
            ActionKind.ARCHIVE -> ActionSpec.Archive
            ActionKind.NOTIFY -> ActionSpec.Notify(title = name.trim().ifEmpty { null }, text = notifyText.trim().ifEmpty { "{body}" })
            ActionKind.FORWARD_SMS -> ActionSpec.ForwardSms(forwardTo.trim(), sendSubId, template.ifBlank { "{body}" })
            ActionKind.SCHEDULE_REPLY -> ActionSpec.ScheduleReply(
                text = replyText.trim().ifEmpty { return null },
                delayMinutes = replyDelayMinutes.toLongOrNull()?.coerceIn(0, MAX_DELAY_MINUTES) ?: return null,
                subId = sendSubId,
            )
            ActionKind.DELETE -> ActionSpec.Delete
            ActionKind.OPEN_LINK -> ActionSpec.LaunchIntent(url.trim().ifEmpty { return null })
            ActionKind.WEBHOOK -> ActionSpec.Webhook(url = url.trim(), template = template.ifBlank { "{body}" }, secretRef = webhookSecret)
            ActionKind.RELAY -> ActionSpec.RelayRule(recipient = forwardTo.trim(), channel = relayChannel, template = template.ifBlank { "{body}" })
        }
        val trigger = keyword.trim().takeIf { it.isNotEmpty() }?.let { Trigger.Keyword(it) } ?: Trigger.MessageReceived()
        return Rule(
            id = id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { defaultName() },
            enabled = enabled,
            trigger = trigger,
            conditions = Condition.All(conditions),
            actions = listOf(spec),
            createdAt = createdAt ?: now,
            updatedAt = now,
        )
    }

    private fun amountMinor(): Long? = amountAtLeast.trim().takeIf { it.isNotEmpty() }
        ?.let { runCatching { BigDecimal(it).movePointRight(2).toLong() }.getOrNull() }

    private fun defaultName(): String = when (action) {
        ActionKind.LABEL -> "Label \"${label.trim()}\""
        ActionKind.ARCHIVE -> "Archive"
        ActionKind.NOTIFY -> "Notify"
        ActionKind.FORWARD_SMS -> "Forward to ${forwardTo.trim()}"
        ActionKind.SCHEDULE_REPLY -> "Auto-reply"
        ActionKind.DELETE -> "Move to bin"
        ActionKind.OPEN_LINK -> "Open link"
        ActionKind.WEBHOOK -> "Webhook"
        ActionKind.RELAY -> "Relay to ${forwardTo.trim()}"
    }

    companion object {
        private const val MAX_DELAY_MINUTES = 7L * 24 * 60

        /** The editor model for [rule], or null when the rule uses anything the simple editor cannot show. */
        fun fromRule(rule: Rule): RuleDraft? {
            val keyword = when (val t = rule.trigger) {
                is Trigger.Keyword -> t.keyword
                is Trigger.MessageReceived -> if (t.predicates == null) "" else return null
                else -> return null
            }
            val leaves = when (val c = rule.conditions) {
                is Condition.All -> c.children
                else -> listOf(c)
            }
            var draft = RuleDraft(id = rule.id, name = rule.name, enabled = rule.enabled, keyword = keyword, createdAt = rule.createdAt)
            for (leaf in leaves) {
                draft = when (leaf) {
                    is Condition.SenderMatches -> draft.copy(senderContains = unquote(leaf.pattern) ?: return null)
                    is Condition.CategoryIs -> draft.copy(category = leaf.category)
                    is Condition.SimIs -> draft.copy(simSlot = leaf.slot ?: return null)
                    is Condition.BodyContains -> draft.copy(bodyContains = leaf.keyword)
                    is Condition.HasOtp -> draft.copy(onlyOtp = true)
                    is Condition.AmountAtLeast -> if (leaf.currency == null) {
                        draft.copy(amountAtLeast = BigDecimal.valueOf(leaf.amountMinor).movePointLeft(2).stripTrailingZeros().toPlainString())
                    } else {
                        return null
                    }
                    else -> return null
                }
            }
            val action = rule.actions.singleOrNull() ?: return null
            return when (action) {
                is ActionSpec.Label -> draft.copy(action = ActionKind.LABEL, label = action.name)
                ActionSpec.Archive -> draft.copy(action = ActionKind.ARCHIVE)
                is ActionSpec.Notify -> draft.copy(action = ActionKind.NOTIFY, notifyText = action.text.orEmpty())
                is ActionSpec.ForwardSms -> draft.copy(action = ActionKind.FORWARD_SMS, forwardTo = action.to, sendSubId = action.subId, template = action.template)
                is ActionSpec.ScheduleReply -> draft.copy(
                    action = ActionKind.SCHEDULE_REPLY,
                    replyText = action.text,
                    replyDelayMinutes = action.delayMinutes.toString(),
                    sendSubId = action.subId,
                )
                ActionSpec.Delete -> draft.copy(action = ActionKind.DELETE)
                is ActionSpec.LaunchIntent -> draft.copy(action = ActionKind.OPEN_LINK, url = action.uri)
                is ActionSpec.Webhook -> draft.copy(action = ActionKind.WEBHOOK, url = action.url, template = action.template, webhookSecret = action.secretRef)
                is ActionSpec.RelayRule -> draft.copy(action = ActionKind.RELAY, forwardTo = action.recipient, relayChannel = action.channel, template = action.template)
                else -> null
            }
        }

        /** Inverse of the `(?i)` + `Pattern.quote` encoding used by [toRule]. */
        private fun unquote(pattern: String): String? {
            val body = pattern.removePrefix("(?i)")
            if (!body.startsWith("\\Q") || !body.endsWith("\\E")) return null
            return body.removePrefix("\\Q").removeSuffix("\\E").takeIf { !it.contains("\\E") }
        }
    }
}
