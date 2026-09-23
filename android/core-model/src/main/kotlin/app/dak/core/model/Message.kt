package app.dak.core.model

import kotlinx.serialization.Serializable

/** Which Telephony provider box a message lives in. Mirrors `Telephony.TextBasedSmsColumns.MESSAGE_TYPE_*`. */
@Serializable
enum class MessageBox(val providerType: Int) {
    INBOX(1), SENT(2), DRAFT(3), OUTBOX(4), FAILED(5), QUEUED(6);

    companion object {
        fun fromProviderType(type: Int): MessageBox = entries.firstOrNull { it.providerType == type } ?: INBOX
    }
}

@Serializable
enum class MessageKind { SMS, MMS }

/** Sentinel for "subscription unknown" (matches `SubscriptionManager.INVALID_SUBSCRIPTION_ID`). */
const val NO_SUB_ID: Int = -1

/**
 * A platform-neutral snapshot of one message as the Telephony provider holds it.
 * The provider stays canonical; this type is only a read model.
 */
@Serializable
data class Message(
    val providerId: Long,
    val kind: MessageKind,
    val threadId: Long,
    /** Raw address as stored by the provider (sender for inbox, recipient(s) for sent; MMS: space-joined). */
    val address: String,
    val body: String,
    val dateMillis: Long,
    val subId: Int = NO_SUB_ID,
    val box: MessageBox = MessageBox.INBOX,
    val read: Boolean = false,
    val seen: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    /** Delivery-report state (outgoing only; the second tick). Always [DeliveryStatus.NONE] for incoming. */
    val deliveryStatus: DeliveryStatus = DeliveryStatus.NONE,
    /** When the delivery report arrived, if the provider recorded it (SMS `date_sent` of a delivered message). */
    val deliveredAtMillis: Long? = null,
) {
    /** Stable key across SMS/MMS tables, which have overlapping `_id` spaces. */
    val key: MessageKey get() = MessageKey(kind, providerId)
}

@Serializable
data class MessageKey(val kind: MessageKind, val providerId: Long) {
    override fun toString(): String = "${kind.name.lowercase()}:$providerId"

    companion object {
        fun parse(s: String): MessageKey? {
            val (k, id) = s.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            val kind = MessageKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) } ?: return null
            return id.toLongOrNull()?.let { MessageKey(kind, it) }
        }
    }
}

@Serializable
data class Attachment(
    val mimeType: String,
    /** Content URI or file name; platform-specific. */
    val uri: String,
    val name: String? = null,
    val sizeBytes: Long? = null,
)
