package app.dak.core.model

import kotlinx.serialization.Serializable

/**
 * Delivery-report state of an outgoing message (the second tick). Independent of [MessageBox], which says whether
 * the message was sent at all: a SENT message with [NONE] had no report requested (or the network never answers),
 * [PENDING] is waiting for one, [DELIVERED] reached the recipient's phone, [FAILED] was reported undeliverable.
 * Incoming messages are always [NONE].
 *
 * [code] mirrors the SMS provider's `Telephony.Sms.STATUS_*` values (NONE -1, COMPLETE 0, PENDING 32, FAILED 64),
 * which is also how the index stores it.
 */
@Serializable
enum class DeliveryStatus(val code: Int) {
    NONE(-1),
    PENDING(32),
    DELIVERED(0),
    FAILED(64),
    ;

    /** True once no further delivery report can change the state. */
    val isFinal: Boolean get() = this == DELIVERED || this == FAILED

    companion object {
        /** Inverse of [code]; unknown values read as [NONE]. */
        fun fromCode(code: Int): DeliveryStatus = entries.firstOrNull { it.code == code } ?: NONE

        /**
         * Combines per-recipient states into one message state (group MMS): [DELIVERED] only when every recipient
         * was delivered, [FAILED] as soon as one recipient failed, [PENDING] while any report is outstanding or only
         * some recipients answered, [NONE] when no recipient has a report (or [statuses] is empty).
         */
        fun aggregate(statuses: Collection<DeliveryStatus>): DeliveryStatus = when {
            statuses.isEmpty() -> NONE
            statuses.all { it == DELIVERED } -> DELIVERED
            statuses.any { it == FAILED } -> FAILED
            statuses.any { it == PENDING || it == DELIVERED } -> PENDING
            else -> NONE
        }
    }
}

/** True for the boxes our own messages live in (sent, sending, queued, failed). */
val MessageBox.isOutgoing: Boolean
    get() = this == MessageBox.SENT || this == MessageBox.OUTBOX || this == MessageBox.QUEUED || this == MessageBox.FAILED
