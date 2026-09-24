package app.dak.automations.rule

import app.dak.core.model.Category
import app.dak.core.model.TransactionDirection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A boolean condition tree evaluated against a [app.dak.automations.MessageEvent].
 *
 * Serialized with an explicit `type` discriminator (see [ConditionSerializer]) so the JSON is
 * forward-compatible: a condition type this build does not know about decodes as [Condition.Unknown]
 * (which always evaluates to `false`) instead of failing to parse the whole rule.
 */
@Serializable(with = ConditionSerializer::class)
public sealed interface Condition {

    @Serializable
    @SerialName("all")
    public data class All(val children: List<Condition>) : Condition

    @Serializable
    @SerialName("any")
    public data class Any(val children: List<Condition>) : Condition

    @Serializable
    @SerialName("not")
    public data class Not(val child: Condition) : Condition

    /** Sender address (as stored by the provider) or a display-layer merge key, exact match. */
    @Serializable
    @SerialName("senderIs")
    public data class SenderIs(val value: String) : Condition

    /** Sender address matched against a regex. Invalid patterns are treated as non-matching, never crash. */
    @Serializable
    @SerialName("senderMatches")
    public data class SenderMatches(val pattern: String) : Condition

    @Serializable
    @SerialName("categoryIs")
    public data class CategoryIs(val category: Category) : Condition

    /** Matches if either [subId] or [slot] is given and equals the event's, whichever is present. */
    @Serializable
    @SerialName("simIs")
    public data class SimIs(val subId: Int? = null, val slot: Int? = null) : Condition

    @Serializable
    @SerialName("bodyContains")
    public data class BodyContains(val keyword: String, val ignoreCase: Boolean = true) : Condition

    @Serializable
    @SerialName("bodyMatches")
    public data class BodyMatches(val pattern: String) : Condition

    @Serializable
    @SerialName("amountAtLeast")
    public data class AmountAtLeast(val amountMinor: Long, val currency: String? = null) : Condition

    @Serializable
    @SerialName("amountAtMost")
    public data class AmountAtMost(val amountMinor: Long, val currency: String? = null) : Condition

    /**
     * Time-of-day window, inclusive, as minutes since midnight (0..1439), in [zoneId] (an IANA zone such as
     * `Asia/Kolkata`; null or unrecognised = UTC, which is how rules saved before the field existed were always
     * evaluated). Wraps past midnight if from > to. Evaluated on the wall clock of that zone, so it follows DST.
     */
    @Serializable
    @SerialName("timeWindow")
    public data class TimeWindow(val fromMinuteOfDay: Int, val toMinuteOfDay: Int, val zoneId: String? = null) : Condition

    @Serializable
    @SerialName("directionIs")
    public data class DirectionIs(val direction: TransactionDirection) : Condition

    @Serializable
    @SerialName("hasOtp")
    public data object HasOtp : Condition

    /**
     * Validity window on the message's own time: true when `startMillis <= dateMillis` and, if [endMillis] is set,
     * `dateMillis <= endMillis` (both inclusive, epoch millis; the UI converts local calendar dates). A null
     * [endMillis] means "until I stop". Used as a top-level conjunct it also marks the rule as expiring — see
     * [activeWindow] / [isExpired].
     */
    @Serializable
    @SerialName("activeBetween")
    public data class ActiveBetween(val startMillis: Long, val endMillis: Long? = null) : Condition

    /**
     * The message belongs to one of the chosen channels: its sender merge group ([mergeKeys], e.g. `HDFCBK`), its
     * display conversation ([conversationIds], `t:<threadId>` / `m:<mergeKey>`), or one of the raw sender
     * [addresses] (phone numbers compare on their last 10 digits). Any one hit is enough.
     */
    @Serializable
    @SerialName("senderInGroups")
    public data class SenderInGroups(
        val mergeKeys: List<String> = emptyList(),
        val conversationIds: List<String> = emptyList(),
        val addresses: List<String> = emptyList(),
    ) : Condition

    /** Preserves an unrecognised condition type (from a newer app version) instead of failing to parse. */
    public data class Unknown(val type: String, val raw: JsonObject) : Condition
}
