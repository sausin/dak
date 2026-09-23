package app.dak.index

import app.dak.core.model.Attachment
import app.dak.core.model.Category
import app.dak.core.model.DeliveryStatus
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.TransactionDirection

/** One row of the conversation list. */
data class ConversationSummary(
    /** `t:<threadId>` or `m:<mergeKey>`; pass back to [app.dak.index.repo.ConversationRepository.messages]. */
    val conversationId: String,
    /** Contact name, merge-group name, canonical brand ("HDFC Bank") or the raw address, in that order. */
    val title: String,
    /** Raw address of the newest message (for avatars / contact lookup). */
    val address: String,
    val snippet: String,
    val dateMillis: Long,
    val unreadCount: Int,
    val messageCount: Int,
    /** Category of the newest message shown (within the tab). */
    val category: Category,
    /** Distinct SIM subscription ids used in the conversation. */
    val subIds: Set<Int>,
    /** Provider threads behind this conversation (several for a merge group). */
    val threadIds: Set<Long>,
    val isMergedSender: Boolean,
    val pinned: Boolean,
    val muted: Boolean,
    val archived: Boolean,
    val starred: Boolean,
    val lastBox: MessageBox,
    val hasAttachment: Boolean,
    /**
     * False for provider threads the backfill has not reached yet: shown straight from the provider without
     * category, SIM or finance enrichment.
     */
    val enriched: Boolean,
    /** How many copies the snippet's message has (see [MessageItem.repeatCount]); 1 when it is not repeated. */
    val snippetRepeatCount: Int = 1,
    /** Delivery state of the snippet's message when it is outgoing ([lastBox]); ticks in the inbox row. */
    val lastDeliveryStatus: DeliveryStatus = DeliveryStatus.NONE,
)

/** OTP details on a message. */
data class OtpItem(
    val code: String,
    /** Package that auto-read it via SMS Retriever / WebOTP, if detected. */
    val consumedBy: String?,
    val webOtpDomain: String?,
    /** The same code arrived again shortly after; the UI may collapse this older copy. */
    val repeatedLater: Boolean,
)

/** Transaction details extracted from a message (amount exactly as written, never converted). */
data class TransactionItem(
    val direction: TransactionDirection,
    val amountMinor: Long,
    val currency: String,
    val instrumentLast4: String?,
    val merchant: String?,
    val accountId: String?,
)

/** One message bubble. */
data class MessageItem(
    val key: MessageKey,
    val conversationId: String,
    val threadId: Long,
    val address: String,
    val body: String,
    val dateMillis: Long,
    val box: MessageBox,
    val read: Boolean,
    val subId: Int,
    val attachments: List<Attachment>,
    val category: Category,
    val confidence: Float,
    val canonicalSender: String?,
    val labels: Set<String>,
    val otp: OtpItem?,
    val transaction: TransactionItem?,
    val hasLink: Boolean,
    val starred: Boolean,
    val archived: Boolean,
    /** False when read straight from the provider (not indexed yet). */
    val enriched: Boolean,
    /**
     * Copies of this message in its repeat group (exact duplicate within 24 h, or the same OTP code within 10 min;
     * see `app.dak.index.enrich.RepeatRules`), including itself; 1 when not repeated. Thread pages return only the
     * newest copy of a group; [app.dak.index.repo.ConversationRepository.repeatsOf] lists all of them.
     */
    val repeatCount: Int = 1,
    /** For an older copy listed by `repeatsOf`: the key of the newest copy (the one the thread shows); else null. */
    val repeatOf: MessageKey? = null,
    /**
     * Sender channel (`SenderId.mergeKey` of [address]: `HDFCBK` for `VM-HDFCBK`), for per-bubble channel chips and
     * the channel filter of a folded conversation. Null for group-MMS address lists.
     */
    val channel: String? = null,
    /**
     * Delivery-report state of an outgoing message (the second tick; see [DeliveryStatus]); NONE for incoming. For a
     * group MMS it is DELIVERED only once every recipient was delivered.
     */
    val deliveryStatus: DeliveryStatus = DeliveryStatus.NONE,
    /** When the delivery report arrived, if known. */
    val deliveredAtMillis: Long? = null,
)

/** One search result: a conversation with its chosen matching message. */
data class SearchHit(
    val conversationId: String,
    val conversationTitle: String,
    val message: MessageItem,
    /** Number of matching messages in the conversation. */
    val matchCount: Int,
    /** Inclusive char ranges in [MessageItem.body] to highlight. */
    val highlights: List<IntRange>,
    /** Set when the hit is a recycle-bin entry (`in:bin`); [message] then describes the deleted copy. */
    val binId: Long? = null,
)

/** One recycle-bin entry. */
data class BinItem(
    val id: Long,
    /** Key the message had before deletion (no longer valid in the provider). */
    val originalKey: MessageKey,
    val conversationId: String,
    val threadId: Long,
    val address: String,
    val body: String,
    val dateMillis: Long,
    val subId: Int,
    val category: Category,
    val attachments: List<Attachment>,
    /** Raw `deletedBy` value; decode with [app.dak.index.bin.DeletedBy.decode]. */
    val deletedBy: String,
    val deletedAtMillis: Long,
    /** [Long.MAX_VALUE] when kept until the user empties the bin. */
    val purgeAtMillis: Long,
)

/** User's choice for when the stage-2 backfill (and re-indexing) may run. */
enum class IndexSchedule {
    /** Right away, no constraints ("uses battery"). */
    NOW,

    /** Only while charging. */
    WHEN_CHARGING,

    /** 01:00-05:00 local, device idle and battery not low; continues on following nights until done. */
    TONIGHT,
}

enum class BackfillStage {
    /** Nothing indexed yet. */
    NOT_STARTED,

    /** Indexing the most recent messages in the foreground. */
    STAGE1,

    /** Indexing the rest in the background under the chosen [IndexSchedule]. */
    STAGE2,
    DONE,
}

/** Why a backfill pass runs. */
enum class BackfillReason { INITIAL, REINDEX, RESTORE, REBUILD }

/** Progress of the index backfill / re-index, for the settings progress row. */
data class BackfillProgress(
    val stage: BackfillStage,
    /** Messages indexed with the current enricher version. */
    val done: Int,
    /** Messages in the provider (as of the last count). */
    val total: Int,
    val schedule: IndexSchedule,
    val reason: BackfillReason,
    /** True while stage 2 is waiting for its constraints (charging / tonight). */
    val waiting: Boolean,
) {
    val remaining: Int get() = (total - done).coerceAtLeast(0)
    val fraction: Float get() = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
}
