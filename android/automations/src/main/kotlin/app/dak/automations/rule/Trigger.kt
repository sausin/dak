package app.dak.automations.rule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * What starts evaluation of a [Rule]. Serialized with an explicit `type` discriminator (see
 * [TriggerSerializer]); an unrecognised trigger type decodes as [Trigger.Unknown] rather than failing.
 */
@Serializable(with = TriggerSerializer::class)
public sealed interface Trigger {

    /** Fires for every incoming message; [predicates], if set, is an extra gate evaluated before [Rule.conditions]. */
    @Serializable
    @SerialName("messageReceived")
    public data class MessageReceived(val predicates: Condition? = null) : Trigger

    @Serializable
    @SerialName("schedule")
    public data class Schedule(val spec: ScheduleSpec) : Trigger

    /** Fires when the message body contains [keyword]. A convenience trigger equivalent to a [Condition.BodyContains]. */
    @Serializable
    @SerialName("keyword")
    public data class Keyword(val keyword: String, val ignoreCase: Boolean = true) : Trigger

    public data class Unknown(val type: String, val raw: JsonObject) : Trigger
}

/** One-shot or recurring schedule for a [Trigger.Schedule]. */
@Serializable(with = ScheduleSpecSerializer::class)
public sealed interface ScheduleSpec {

    @Serializable
    @SerialName("oneShot")
    public data class OneShot(val atMillis: Long) : ScheduleSpec

    @Serializable
    @SerialName("recurring")
    public data class Recurring(val recurrence: Recurrence) : ScheduleSpec

    public data class Unknown(val type: String, val raw: JsonObject) : ScheduleSpec
}

/**
 * A recurring time-of-day schedule, used both by [ScheduleSpec.Recurring] and by
 * [app.dak.automations.ratelimit.ScheduledSend]. See [app.dak.automations.ratelimit.nextOccurrence].
 */
@Serializable(with = RecurrenceSerializer::class)
public sealed interface Recurrence {
    public val hour: Int
    public val minute: Int
    public val zoneId: String

    @Serializable
    @SerialName("daily")
    public data class Daily(
        override val hour: Int,
        override val minute: Int,
        override val zoneId: String,
    ) : Recurrence

    /** [dayOfWeek] is ISO-8601: 1 = Monday .. 7 = Sunday. */
    @Serializable
    @SerialName("weekly")
    public data class Weekly(
        val dayOfWeek: Int,
        override val hour: Int,
        override val minute: Int,
        override val zoneId: String,
    ) : Recurrence

    /** [dayOfMonth] over 28 clamps to the last day of shorter months (e.g. 31 -> Feb 28/29). */
    @Serializable
    @SerialName("monthly")
    public data class Monthly(
        val dayOfMonth: Int,
        override val hour: Int,
        override val minute: Int,
        override val zoneId: String,
    ) : Recurrence

    public data class Unknown(val type: String, val raw: JsonObject) : Recurrence {
        override val hour: Int get() = 0
        override val minute: Int get() = 0
        override val zoneId: String get() = "UTC"
    }
}
