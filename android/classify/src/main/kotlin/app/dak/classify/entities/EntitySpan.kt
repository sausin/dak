package app.dak.classify.entities

/**
 * Kinds of entity [EntityExtractor] finds in a message body. Declaration order is the overlap priority: when two
 * candidate spans overlap, the one whose type comes first wins (an OTP is never also a phone number, an account
 * mask or amount never a phone, a reference number never a phone).
 */
public enum class EntityType {
    OTP,
    URL,
    EMAIL,
    MASKED_ACCOUNT,
    AMOUNT,
    UPI_ID,
    PNR,
    TRACKING,
    REFERENCE,
    PHONE,
}

/**
 * One typed span over a message body.
 *
 * @property start first char (inclusive) in the original body.
 * @property end end (exclusive) in the original body.
 * @property text the body text of the span, as written.
 * @property value the canonical value for actions: OTP code (ASCII digits), E.164 phone number, lower-case UPI
 *   handle / email, the id of a reference/PNR/tracking number, the visible digits of an account mask, the URL as
 *   written, or whatever the caller supplied for an [EntityHint] (e.g. an amount's canonical query).
 * @property courier for [EntityType.TRACKING]: a [Couriers] key ("bluedart", "delhivery", ...) when the courier is
 *   named in the message, else null.
 */
public data class EntitySpan(
    val type: EntityType,
    val start: Int,
    val end: Int,
    val text: String,
    val value: String,
    val courier: String? = null,
) {
    /** Inclusive char range into the body. */
    val range: IntRange get() = start until end
}

/**
 * A span the caller already knows (e.g. amounts found by `:finance`'s `MoneyParser`, which `:classify` cannot
 * depend on). [start]/[end] (exclusive) index into the same body passed to [EntityExtractor.extract].
 */
public data class EntityHint(val type: EntityType, val start: Int, val end: Int, val value: String)
