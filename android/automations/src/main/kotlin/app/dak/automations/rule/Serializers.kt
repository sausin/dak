package app.dak.automations.rule

import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Shared "explicit `type` discriminator, unknown falls back to a preserved `Unknown`" dispatch used
 * by every polymorphic node in the rule AST. This is what makes the JSON forward-compatible: a rule
 * created by a newer app build that adds a new trigger/condition/action type still loads on an older
 * one, with the unrecognised node kept verbatim (round-trippable, inert) rather than throwing.
 */
private fun typeOf(element: JsonElement): String =
    (element as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.content } ?: ""

internal object ConditionSerializer : JsonContentPolymorphicSerializer<Condition>(Condition::class) {
    override fun selectDeserializer(element: JsonElement) = when (typeOf(element)) {
        "all" -> Condition.All.serializer()
        "any" -> Condition.Any.serializer()
        "not" -> Condition.Not.serializer()
        "senderIs" -> Condition.SenderIs.serializer()
        "senderMatches" -> Condition.SenderMatches.serializer()
        "categoryIs" -> Condition.CategoryIs.serializer()
        "simIs" -> Condition.SimIs.serializer()
        "bodyContains" -> Condition.BodyContains.serializer()
        "bodyMatches" -> Condition.BodyMatches.serializer()
        "amountAtLeast" -> Condition.AmountAtLeast.serializer()
        "amountAtMost" -> Condition.AmountAtMost.serializer()
        "timeWindow" -> Condition.TimeWindow.serializer()
        "directionIs" -> Condition.DirectionIs.serializer()
        "hasOtp" -> Condition.HasOtp.serializer()
        else -> UnknownConditionSerializer
    }
}

internal object TriggerSerializer : JsonContentPolymorphicSerializer<Trigger>(Trigger::class) {
    override fun selectDeserializer(element: JsonElement) = when (typeOf(element)) {
        "messageReceived" -> Trigger.MessageReceived.serializer()
        "schedule" -> Trigger.Schedule.serializer()
        "keyword" -> Trigger.Keyword.serializer()
        else -> UnknownTriggerSerializer
    }
}

internal object ScheduleSpecSerializer : JsonContentPolymorphicSerializer<ScheduleSpec>(ScheduleSpec::class) {
    override fun selectDeserializer(element: JsonElement) = when (typeOf(element)) {
        "oneShot" -> ScheduleSpec.OneShot.serializer()
        "recurring" -> ScheduleSpec.Recurring.serializer()
        else -> UnknownScheduleSpecSerializer
    }
}

internal object RecurrenceSerializer : JsonContentPolymorphicSerializer<Recurrence>(Recurrence::class) {
    override fun selectDeserializer(element: JsonElement) = when (typeOf(element)) {
        "daily" -> Recurrence.Daily.serializer()
        "weekly" -> Recurrence.Weekly.serializer()
        "monthly" -> Recurrence.Monthly.serializer()
        else -> UnknownRecurrenceSerializer
    }
}

internal object ActionSpecSerializer : JsonContentPolymorphicSerializer<ActionSpec>(ActionSpec::class) {
    override fun selectDeserializer(element: JsonElement) = when (typeOf(element)) {
        "label" -> ActionSpec.Label.serializer()
        "archive" -> ActionSpec.Archive.serializer()
        "notify" -> ActionSpec.Notify.serializer()
        "forwardSms" -> ActionSpec.ForwardSms.serializer()
        "scheduleReply" -> ActionSpec.ScheduleReply.serializer()
        "launchIntent" -> ActionSpec.LaunchIntent.serializer()
        "delete" -> ActionSpec.Delete.serializer()
        "webhook" -> ActionSpec.Webhook.serializer()
        "relayToWebClient" -> ActionSpec.RelayToWebClient.serializer()
        "relayRule" -> ActionSpec.RelayRule.serializer()
        else -> UnknownActionSpecSerializer
    }
}

// Each `Unknown` variant is deserialized (and re-serialized) as its raw JsonObject, keyed by `type`
// so a decode-then-encode round trip preserves the original payload byte-for-byte in structure.

private fun unknownTypeOf(element: JsonElement): String = typeOf(element).ifEmpty { "unknown" }

internal object UnknownConditionSerializer : kotlinx.serialization.KSerializer<Condition.Unknown> {
    override val descriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Condition.Unknown {
        val obj = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement().jsonObject
        return Condition.Unknown(unknownTypeOf(obj), obj)
    }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Condition.Unknown) {
        (encoder as kotlinx.serialization.json.JsonEncoder).encodeJsonElement(value.raw)
    }
}

internal object UnknownTriggerSerializer : kotlinx.serialization.KSerializer<Trigger.Unknown> {
    override val descriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Trigger.Unknown {
        val obj = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement().jsonObject
        return Trigger.Unknown(unknownTypeOf(obj), obj)
    }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Trigger.Unknown) {
        (encoder as kotlinx.serialization.json.JsonEncoder).encodeJsonElement(value.raw)
    }
}

internal object UnknownScheduleSpecSerializer : kotlinx.serialization.KSerializer<ScheduleSpec.Unknown> {
    override val descriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ScheduleSpec.Unknown {
        val obj = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement().jsonObject
        return ScheduleSpec.Unknown(unknownTypeOf(obj), obj)
    }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ScheduleSpec.Unknown) {
        (encoder as kotlinx.serialization.json.JsonEncoder).encodeJsonElement(value.raw)
    }
}

internal object UnknownRecurrenceSerializer : kotlinx.serialization.KSerializer<Recurrence.Unknown> {
    override val descriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Recurrence.Unknown {
        val obj = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement().jsonObject
        return Recurrence.Unknown(unknownTypeOf(obj), obj)
    }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Recurrence.Unknown) {
        (encoder as kotlinx.serialization.json.JsonEncoder).encodeJsonElement(value.raw)
    }
}

internal object UnknownActionSpecSerializer : kotlinx.serialization.KSerializer<ActionSpec.Unknown> {
    override val descriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ActionSpec.Unknown {
        val obj = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement().jsonObject
        return ActionSpec.Unknown(unknownTypeOf(obj), obj)
    }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ActionSpec.Unknown) {
        (encoder as kotlinx.serialization.json.JsonEncoder).encodeJsonElement(value.raw)
    }
}
