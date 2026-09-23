package app.dak.automations.broadcast

import kotlinx.serialization.Serializable

/**
 * One person on a [BroadcastList]. [contactId] is the Contacts row when the member was picked from contacts (null for
 * a typed number); [displayName] is the name shown and used for `{name}` / `{firstName}` (blank for a raw number).
 */
@Serializable
public data class Member(
    val contactId: Long? = null,
    val displayName: String = "",
    val address: String,
)

/**
 * A saved broadcast list: the same message goes to every [members] entry as an individual SMS, into that person's own
 * 1:1 thread, so replies come back 1:1. Lists are capped at [BroadcastLimits.HARD_MAX_RECIPIENTS] members.
 */
@Serializable
public data class BroadcastList(
    val id: String,
    val name: String,
    val members: List<Member>,
    val createdAt: Long,
)

/** Lifecycle of one recipient's copy of a broadcast, as recorded by the app (live ticks come from the provider). */
@Serializable
public enum class RecipientStatus {
    /** Queued as a scheduled send, not handed to the platform yet. */
    SCHEDULED,

    /** Handed to the platform ([BroadcastRecipient.messageKey] is set); ticks come from that message. */
    SENT,

    /** The send could not be handed to the platform (see the scheduled send's failure reason). */
    FAILED,

    /** Cancelled by the user before it went out. */
    CANCELLED,
}

/**
 * One recipient's copy of a sent broadcast.
 *
 * @property text the rendered text this person received (placeholders filled).
 * @property scheduledSendId the app's scheduled-send row that carries this copy.
 * @property messageKey the provider message key (`sms:<id>`) once sent, for ticks and the 1:1 thread.
 */
@Serializable
public data class BroadcastRecipient(
    val address: String,
    val displayName: String = "",
    val contactId: Long? = null,
    val text: String,
    val sendAtMillis: Long,
    val scheduledSendId: Long? = null,
    val messageKey: String? = null,
    val status: RecipientStatus = RecipientStatus.SCHEDULED,
)

/**
 * A broadcast that was sent (or scheduled once) from a list. One-shot by design: there is no recurrence field, and
 * automation rules cannot create one.
 */
@Serializable
public data class BroadcastRecord(
    val id: String,
    val listId: String,
    val listName: String,
    /** The text as typed, with placeholders. */
    val template: String,
    val subId: Int,
    val createdAt: Long,
    /** The user's chosen send time, or null for "send now". */
    val scheduledAtMillis: Long? = null,
    val recipients: List<BroadcastRecipient>,
) {
    /** When the first copy goes out. */
    val startsAtMillis: Long get() = recipients.minOfOrNull { it.sendAtMillis } ?: (scheduledAtMillis ?: createdAt)

    /** Copies that count against the daily quota (everything not cancelled). */
    val countedMessages: Int get() = recipients.count { it.status != RecipientStatus.CANCELLED }
}
