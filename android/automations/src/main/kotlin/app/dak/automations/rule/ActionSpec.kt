package app.dak.automations.rule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Delivery channel for a [ActionSpec.RelayRule] (premium relay rules). */
@Serializable
public enum class RelayChannel { SMS, WHATSAPP_ONE_TAP, WEBHOOK }

/**
 * One action a [Rule] performs when its trigger and conditions match. Serialized with an explicit
 * `type` discriminator (see [ActionSpecSerializer]); an unrecognised action type decodes as
 * [ActionSpec.Unknown] instead of failing the whole rule, so an older build can still load a rule
 * created by a newer one (skipping the action it does not understand).
 *
 * Free actions: [Label], [Archive], [Notify], [ForwardSms], [ScheduleReply], [LaunchIntent], [Delete].
 * Premium actions (gated by `Entitlements` in [app.dak.automations.action.ActionRegistry]): [Webhook],
 * [RelayToWebClient], [RelayRule].
 */
@Serializable(with = ActionSpecSerializer::class)
public sealed interface ActionSpec {

    @Serializable
    @SerialName("label")
    public data class Label(val name: String) : ActionSpec

    @Serializable
    @SerialName("archive")
    public data object Archive : ActionSpec

    @Serializable
    @SerialName("notify")
    public data class Notify(val title: String? = null, val text: String? = null) : ActionSpec

    /** [template] is rendered per [app.dak.automations.TemplateRenderer]; default is the raw message. */
    @Serializable
    @SerialName("forwardSms")
    public data class ForwardSms(val to: String, val subId: Int? = null, val template: String = "{body}") : ActionSpec

    @Serializable
    @SerialName("scheduleReply")
    public data class ScheduleReply(val text: String, val delayMinutes: Long, val subId: Int? = null) : ActionSpec

    @Serializable
    @SerialName("launchIntent")
    public data class LaunchIntent(val uri: String) : ActionSpec

    /** Soft-deletes the message to the recycle bin (see the OTP lifecycle / recycle bin design). */
    @Serializable
    @SerialName("delete")
    public data object Delete : ActionSpec

    /** Premium. A signed POST; see `app.dak.automations.action.WebhookSigner`. */
    @Serializable
    @SerialName("webhook")
    public data class Webhook(val url: String, val template: String = "{body}", val secretRef: String) : ActionSpec

    /** Premium. Pushes the (encrypted, elsewhere) message to the paired web/desktop client. */
    @Serializable
    @SerialName("relayToWebClient")
    public data class RelayToWebClient(val pairingId: String? = null, val template: String = "{body}") : ActionSpec

    /** Premium. See "Relay rules" in the build plan. */
    @Serializable
    @SerialName("relayRule")
    public data class RelayRule(
        val recipient: String,
        val channel: RelayChannel,
        val template: String = "{body}",
    ) : ActionSpec

    public data class Unknown(val type: String, val raw: JsonObject) : ActionSpec
}
