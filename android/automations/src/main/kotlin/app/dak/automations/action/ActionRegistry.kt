package app.dak.automations.action

import app.dak.automations.MessageEvent
import app.dak.automations.PlannedAction
import app.dak.automations.TemplateRenderer
import app.dak.automations.audit.AuditLogEntry
import app.dak.automations.audit.AuditSink
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.RelayChannel
import app.dak.automations.rule.isForwardingOrRelay
import app.dak.premium.Entitlements
import app.dak.premium.Feature
import app.dak.premium.PremiumGateway
import app.dak.premium.WebhookRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Everything a built-in [ActionSpec] executor needs. `:app` assembles one real implementation; tests
 * pass fakes. [clockMillis] exists so scheduling/logging stay deterministic under test.
 */
public interface ActionContext {
    public val entitlements: Entitlements
    public val premiumGateway: PremiumGateway
    public val smsForwarder: SmsForwarder
    public val notifier: Notifier
    public val labeler: Labeler
    public val archiver: Archiver
    public val binner: Binner
    public val intentLauncher: IntentLauncher
    public val replyScheduler: ReplyScheduler
    public val auditSink: AuditSink
    public val clockMillis: () -> Long
    /** Zone used to render `{time}` in templates; see [TemplateRenderer]. */
    public val zoneId: String
}

/**
 * Runs [PlannedAction]s. Every [ActionSpec] type is registered once, in [DefaultActionRegistry.install];
 * a caller only needs [execute]. Premium action types are gated on [ActionContext.entitlements] before
 * the executor ever runs, so `:app`'s executors never have to re-check entitlements themselves.
 */
public interface ActionRegistry {
    public suspend fun execute(planned: PlannedAction, event: MessageEvent, context: ActionContext): ActionResult
}

/** The one built-in [ActionRegistry], wiring every [ActionSpec] type to its behaviour. */
public class DefaultActionRegistry : ActionRegistry {

    private companion object {
        val PREMIUM_FEATURE: Map<Class<out ActionSpec>, Feature> = mapOf(
            ActionSpec.Webhook::class.java to Feature.WEBHOOKS,
            ActionSpec.RelayToWebClient::class.java to Feature.WEB_CLIENT,
            ActionSpec.RelayRule::class.java to Feature.RELAY_RULES,
        )
    }

    override suspend fun execute(planned: PlannedAction, event: MessageEvent, context: ActionContext): ActionResult {
        val action = planned.action
        PREMIUM_FEATURE[action.javaClass]?.let { feature ->
            if (!context.entitlements.has(feature)) return ActionResult.Locked(feature)
        }
        val outcome = runCatching { runAction(action, event, context) }
            .getOrElse { ActionResult.Failed(it.message ?: it::class.simpleName ?: "unknown error") }
        recordAudit(planned, event, outcome, context)
        return outcome
    }

    private suspend fun runAction(action: ActionSpec, event: MessageEvent, context: ActionContext): ActionResult =
        when (action) {
            is ActionSpec.Label -> {
                if (context.labeler.label(event.messageKey, action.name)) ActionResult.Success
                else ActionResult.Failed("label failed")
            }
            is ActionSpec.Archive -> {
                context.archiver.archive(event.messageKey)
                ActionResult.Success
            }
            is ActionSpec.Notify -> {
                if (context.notifier.notify(action.title, action.text)) ActionResult.Success
                else ActionResult.Failed("notify failed")
            }
            is ActionSpec.ForwardSms -> {
                val text = TemplateRenderer.render(action.template, event, context.zoneId)
                if (context.smsForwarder.forward(action.to, action.subId, text)) ActionResult.Success
                else ActionResult.Failed("forward failed")
            }
            is ActionSpec.ScheduleReply -> {
                val at = context.clockMillis() + action.delayMinutes * 60_000L
                // ScheduleReply's text is user-authored verbatim (not a forward template), sent as-is.
                if (context.replyScheduler.scheduleReply(event.address, action.subId, action.text, at)) {
                    ActionResult.Success
                } else {
                    ActionResult.Failed("schedule failed")
                }
            }
            is ActionSpec.LaunchIntent -> {
                if (context.intentLauncher.launch(action.uri)) ActionResult.Success
                else ActionResult.Failed("launch failed")
            }
            is ActionSpec.Delete -> {
                context.binner.delete(event.messageKey, deletedBy = "auto-rule")
                ActionResult.Success
            }
            is ActionSpec.Webhook -> {
                val text = TemplateRenderer.render(action.template, event, context.zoneId)
                val jsonBody = Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("messageKey", JsonPrimitive(event.messageKey))
                        put("sender", JsonPrimitive(event.mergeKey ?: event.address))
                        put("text", JsonPrimitive(text))
                    },
                )
                val request = WebhookRequest(
                    url = action.url,
                    jsonBody = jsonBody,
                    signingSecret = action.secretRef.toByteArray(Charsets.UTF_8),
                )
                if (context.premiumGateway.sendWebhook(request)) ActionResult.Success
                else ActionResult.Failed("webhook failed")
            }
            is ActionSpec.RelayToWebClient -> {
                val text = TemplateRenderer.render(action.template, event, context.zoneId)
                val pairing = action.pairingId ?: return ActionResult.Failed("no pairing configured")
                if (context.premiumGateway.relayCiphertext(pairing, text.toByteArray(Charsets.UTF_8))) {
                    ActionResult.Success
                } else {
                    ActionResult.Failed("relay failed")
                }
            }
            is ActionSpec.RelayRule -> {
                val text = TemplateRenderer.render(action.template, event, context.zoneId)
                when (action.channel) {
                    RelayChannel.SMS ->
                        if (context.smsForwarder.forward(action.recipient, null, text)) ActionResult.Success
                        else ActionResult.Failed("relay-sms failed")
                    RelayChannel.WHATSAPP_ONE_TAP ->
                        // The final tap is the user's; we only prepare the intent (WhatsApp has no
                        // personal-account send API and automating it would break its terms).
                        if (context.intentLauncher.launch("whatsapp://send?phone=${action.recipient}&text=$text")) {
                            ActionResult.Success
                        } else {
                            ActionResult.Failed("relay-whatsapp failed")
                        }
                    RelayChannel.WEBHOOK ->
                        if (context.premiumGateway.relayCiphertext(action.recipient, text.toByteArray(Charsets.UTF_8))) {
                            ActionResult.Success
                        } else {
                            ActionResult.Failed("relay-webhook failed")
                        }
                }
            }
            is ActionSpec.Unknown -> ActionResult.Failed("unknown action type: ${action.type}")
        }

    private suspend fun recordAudit(
        planned: PlannedAction,
        event: MessageEvent,
        outcome: ActionResult,
        context: ActionContext,
    ) {
        if (!planned.action.isForwardingOrRelay()) return
        val (recipient, channel) = recipientAndChannel(planned.action)
        context.auditSink.record(
            AuditLogEntry(
                ruleId = planned.ruleId,
                ruleName = planned.ruleName,
                actionType = planned.action::class.simpleName ?: "Unknown",
                recipient = recipient,
                channel = channel,
                messageKey = event.messageKey,
                atMillis = context.clockMillis(),
                outcome = when (outcome) {
                    is ActionResult.Success -> "success"
                    is ActionResult.Failed -> "failed: ${outcome.reason}"
                    is ActionResult.Locked -> "locked: ${outcome.feature}"
                },
            ),
        )
    }

    private fun recipientAndChannel(action: ActionSpec): Pair<String?, String?> = when (action) {
        is ActionSpec.ForwardSms -> action.to to "SMS"
        is ActionSpec.Webhook -> action.url to "WEBHOOK"
        is ActionSpec.RelayToWebClient -> action.pairingId to "WEB_CLIENT"
        is ActionSpec.RelayRule -> action.recipient to action.channel.name
        else -> null to null
    }
}
