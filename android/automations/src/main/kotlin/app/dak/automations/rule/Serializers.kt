package app.dak.automations.rule

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Shared machinery for every polymorphic node in the rule AST: encodes as `{"type": "<tag>", ...fields}`
 * and, on decode, dispatches on `type`, falling back to a preserved `Unknown` for a tag this build does
 * not recognise. This (plus [kotlinx.serialization.json.Json.ignoreUnknownKeys] on [RuleCodec.json] for
 * *extra fields* on a *known* type) is what makes the format forward-compatible: a rule created by a
 * newer app build that adds a brand-new node type, or extra fields on an existing one, still loads on
 * an older build, with anything unrecognised kept verbatim (round-trippable, inert) rather than crashing.
 *
 * [JsonContentPolymorphicSerializer] does not fit here since it never gained a matching encode-side
 * discriminator, so it silently drops "type" on the way out. Encoding here always goes through the
 * [JsonEncoder] actually in use (so it inherits the caller's `Json` configuration), and dispatch itself
 * needs no [kotlinx.serialization.modules.SerializersModule] registration.
 */
private fun JsonObject.typeTag(): String = (this["type"] as? JsonPrimitive)?.content ?: ""

private fun mergeType(type: String, element: JsonElement): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    if (element is JsonObject) element.forEach { (k, v) -> put(k, v) }
}

private fun <T : Any> JsonEncoder.encodeTagged(type: String, serializer: KSerializer<T>, value: T) {
    encodeJsonElement(mergeType(type, json.encodeToJsonElement(serializer, value)))
}

private fun <T : Any> JsonDecoder.decodeTagged(obj: JsonObject, serializer: KSerializer<T>): T =
    json.decodeFromJsonElement(serializer, obj)

private fun asJsonEncoder(encoder: Encoder): JsonEncoder =
    encoder as? JsonEncoder ?: error("This type can only be serialized to JSON")

private fun asJsonDecoder(decoder: Decoder): JsonDecoder =
    decoder as? JsonDecoder ?: error("This type can only be deserialized from JSON")

internal object ConditionSerializer : KSerializer<Condition> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Condition) {
        val json = asJsonEncoder(encoder)
        when (value) {
            is Condition.All -> json.encodeTagged("all", Condition.All.serializer(), value)
            is Condition.Any -> json.encodeTagged("any", Condition.Any.serializer(), value)
            is Condition.Not -> json.encodeTagged("not", Condition.Not.serializer(), value)
            is Condition.SenderIs -> json.encodeTagged("senderIs", Condition.SenderIs.serializer(), value)
            is Condition.SenderMatches -> json.encodeTagged("senderMatches", Condition.SenderMatches.serializer(), value)
            is Condition.CategoryIs -> json.encodeTagged("categoryIs", Condition.CategoryIs.serializer(), value)
            is Condition.SimIs -> json.encodeTagged("simIs", Condition.SimIs.serializer(), value)
            is Condition.BodyContains -> json.encodeTagged("bodyContains", Condition.BodyContains.serializer(), value)
            is Condition.BodyMatches -> json.encodeTagged("bodyMatches", Condition.BodyMatches.serializer(), value)
            is Condition.AmountAtLeast -> json.encodeTagged("amountAtLeast", Condition.AmountAtLeast.serializer(), value)
            is Condition.AmountAtMost -> json.encodeTagged("amountAtMost", Condition.AmountAtMost.serializer(), value)
            is Condition.TimeWindow -> json.encodeTagged("timeWindow", Condition.TimeWindow.serializer(), value)
            is Condition.DirectionIs -> json.encodeTagged("directionIs", Condition.DirectionIs.serializer(), value)
            is Condition.HasOtp -> json.encodeTagged("hasOtp", Condition.HasOtp.serializer(), value)
            is Condition.Unknown -> json.encodeJsonElement(value.raw)
        }
    }

    override fun deserialize(decoder: Decoder): Condition {
        val json = asJsonDecoder(decoder)
        val obj = json.decodeJsonElement() as? JsonObject ?: return Condition.Unknown("", JsonObject(emptyMap()))
        return when (val type = obj.typeTag()) {
            "all" -> json.decodeTagged(obj, Condition.All.serializer())
            "any" -> json.decodeTagged(obj, Condition.Any.serializer())
            "not" -> json.decodeTagged(obj, Condition.Not.serializer())
            "senderIs" -> json.decodeTagged(obj, Condition.SenderIs.serializer())
            "senderMatches" -> json.decodeTagged(obj, Condition.SenderMatches.serializer())
            "categoryIs" -> json.decodeTagged(obj, Condition.CategoryIs.serializer())
            "simIs" -> json.decodeTagged(obj, Condition.SimIs.serializer())
            "bodyContains" -> json.decodeTagged(obj, Condition.BodyContains.serializer())
            "bodyMatches" -> json.decodeTagged(obj, Condition.BodyMatches.serializer())
            "amountAtLeast" -> json.decodeTagged(obj, Condition.AmountAtLeast.serializer())
            "amountAtMost" -> json.decodeTagged(obj, Condition.AmountAtMost.serializer())
            "timeWindow" -> json.decodeTagged(obj, Condition.TimeWindow.serializer())
            "directionIs" -> json.decodeTagged(obj, Condition.DirectionIs.serializer())
            "hasOtp" -> json.decodeTagged(obj, Condition.HasOtp.serializer())
            else -> Condition.Unknown(type.ifEmpty { "unknown" }, obj)
        }
    }
}

internal object TriggerSerializer : KSerializer<Trigger> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Trigger) {
        val json = asJsonEncoder(encoder)
        when (value) {
            is Trigger.MessageReceived -> json.encodeTagged("messageReceived", Trigger.MessageReceived.serializer(), value)
            is Trigger.Schedule -> json.encodeTagged("schedule", Trigger.Schedule.serializer(), value)
            is Trigger.Keyword -> json.encodeTagged("keyword", Trigger.Keyword.serializer(), value)
            is Trigger.Unknown -> json.encodeJsonElement(value.raw)
        }
    }

    override fun deserialize(decoder: Decoder): Trigger {
        val json = asJsonDecoder(decoder)
        val obj = json.decodeJsonElement() as? JsonObject ?: return Trigger.Unknown("", JsonObject(emptyMap()))
        return when (val type = obj.typeTag()) {
            "messageReceived" -> json.decodeTagged(obj, Trigger.MessageReceived.serializer())
            "schedule" -> json.decodeTagged(obj, Trigger.Schedule.serializer())
            "keyword" -> json.decodeTagged(obj, Trigger.Keyword.serializer())
            else -> Trigger.Unknown(type.ifEmpty { "unknown" }, obj)
        }
    }
}

internal object ScheduleSpecSerializer : KSerializer<ScheduleSpec> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ScheduleSpec) {
        val json = asJsonEncoder(encoder)
        when (value) {
            is ScheduleSpec.OneShot -> json.encodeTagged("oneShot", ScheduleSpec.OneShot.serializer(), value)
            is ScheduleSpec.Recurring -> json.encodeTagged("recurring", ScheduleSpec.Recurring.serializer(), value)
            is ScheduleSpec.Unknown -> json.encodeJsonElement(value.raw)
        }
    }

    override fun deserialize(decoder: Decoder): ScheduleSpec {
        val json = asJsonDecoder(decoder)
        val obj = json.decodeJsonElement() as? JsonObject ?: return ScheduleSpec.Unknown("", JsonObject(emptyMap()))
        return when (val type = obj.typeTag()) {
            "oneShot" -> json.decodeTagged(obj, ScheduleSpec.OneShot.serializer())
            "recurring" -> json.decodeTagged(obj, ScheduleSpec.Recurring.serializer())
            else -> ScheduleSpec.Unknown(type.ifEmpty { "unknown" }, obj)
        }
    }
}

internal object RecurrenceSerializer : KSerializer<Recurrence> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Recurrence) {
        val json = asJsonEncoder(encoder)
        when (value) {
            is Recurrence.Daily -> json.encodeTagged("daily", Recurrence.Daily.serializer(), value)
            is Recurrence.Weekly -> json.encodeTagged("weekly", Recurrence.Weekly.serializer(), value)
            is Recurrence.Monthly -> json.encodeTagged("monthly", Recurrence.Monthly.serializer(), value)
            is Recurrence.Unknown -> json.encodeJsonElement(value.raw)
        }
    }

    override fun deserialize(decoder: Decoder): Recurrence {
        val json = asJsonDecoder(decoder)
        val obj = json.decodeJsonElement() as? JsonObject ?: return Recurrence.Unknown("", JsonObject(emptyMap()))
        return when (val type = obj.typeTag()) {
            "daily" -> json.decodeTagged(obj, Recurrence.Daily.serializer())
            "weekly" -> json.decodeTagged(obj, Recurrence.Weekly.serializer())
            "monthly" -> json.decodeTagged(obj, Recurrence.Monthly.serializer())
            else -> Recurrence.Unknown(type.ifEmpty { "unknown" }, obj)
        }
    }
}

internal object ActionSpecSerializer : KSerializer<ActionSpec> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ActionSpec) {
        val json = asJsonEncoder(encoder)
        when (value) {
            is ActionSpec.Label -> json.encodeTagged("label", ActionSpec.Label.serializer(), value)
            is ActionSpec.Archive -> json.encodeTagged("archive", ActionSpec.Archive.serializer(), value)
            is ActionSpec.Notify -> json.encodeTagged("notify", ActionSpec.Notify.serializer(), value)
            is ActionSpec.ForwardSms -> json.encodeTagged("forwardSms", ActionSpec.ForwardSms.serializer(), value)
            is ActionSpec.ScheduleReply -> json.encodeTagged("scheduleReply", ActionSpec.ScheduleReply.serializer(), value)
            is ActionSpec.LaunchIntent -> json.encodeTagged("launchIntent", ActionSpec.LaunchIntent.serializer(), value)
            is ActionSpec.Delete -> json.encodeTagged("delete", ActionSpec.Delete.serializer(), value)
            is ActionSpec.Webhook -> json.encodeTagged("webhook", ActionSpec.Webhook.serializer(), value)
            is ActionSpec.RelayToWebClient -> json.encodeTagged("relayToWebClient", ActionSpec.RelayToWebClient.serializer(), value)
            is ActionSpec.RelayRule -> json.encodeTagged("relayRule", ActionSpec.RelayRule.serializer(), value)
            is ActionSpec.Unknown -> json.encodeJsonElement(value.raw)
        }
    }

    override fun deserialize(decoder: Decoder): ActionSpec {
        val json = asJsonDecoder(decoder)
        val obj = json.decodeJsonElement() as? JsonObject ?: return ActionSpec.Unknown("", JsonObject(emptyMap()))
        return when (val type = obj.typeTag()) {
            "label" -> json.decodeTagged(obj, ActionSpec.Label.serializer())
            "archive" -> json.decodeTagged(obj, ActionSpec.Archive.serializer())
            "notify" -> json.decodeTagged(obj, ActionSpec.Notify.serializer())
            "forwardSms" -> json.decodeTagged(obj, ActionSpec.ForwardSms.serializer())
            "scheduleReply" -> json.decodeTagged(obj, ActionSpec.ScheduleReply.serializer())
            "launchIntent" -> json.decodeTagged(obj, ActionSpec.LaunchIntent.serializer())
            "delete" -> json.decodeTagged(obj, ActionSpec.Delete.serializer())
            "webhook" -> json.decodeTagged(obj, ActionSpec.Webhook.serializer())
            "relayToWebClient" -> json.decodeTagged(obj, ActionSpec.RelayToWebClient.serializer())
            "relayRule" -> json.decodeTagged(obj, ActionSpec.RelayRule.serializer())
            else -> ActionSpec.Unknown(type.ifEmpty { "unknown" }, obj)
        }
    }
}
