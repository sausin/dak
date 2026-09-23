package app.dak.index.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import app.dak.core.model.Category
import app.dak.core.model.ClassifierSource
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.core.model.TransactionDirection
import app.dak.index.sql.Tables

/**
 * One row per provider message (SMS and MMS share `_id` spaces, hence the `(kind, providerId)` key) with its
 * enrichment. Identical across tiers; rebuildable from the provider at any time.
 *
 * The body is stored here (encrypted at rest by SQLCipher) so threads and search never round-trip to the provider.
 * [searchText] / [searchSender] are the normalized copies the [MessageFts] table indexes.
 */
@Entity(
    tableName = Tables.MESSAGE,
    primaryKeys = ["kind", "providerId"],
    indices = [
        Index(value = ["conversationId", "dateMillis"]),
        Index(value = ["threadId", "dateMillis"]),
        Index(value = ["dateMillis"]),
        Index(value = ["mergeKey"]),
        Index(value = ["category", "dateMillis"]),
        Index(value = ["templateVersion"]),
        Index(value = ["accountId"]),
        Index(value = ["repeatGroup"]),
    ],
)
data class IndexedMessage(
    val kind: MessageKind,
    val providerId: Long,
    val threadId: Long,
    val subId: Int,
    val address: String,
    /** Sender merge key (e.g. `HDFCBK` for `VM-HDFCBK`), possibly overridden by a sender alias. */
    val mergeKey: String,
    /** `m:<mergeKey>` for merged alphanumeric senders, else `t:<threadId>`; see `ConversationIds`. */
    val conversationId: String,
    val dateMillis: Long,
    val box: MessageBox,
    val read: Boolean,
    val seen: Boolean,
    val category: Category,
    val confidence: Float,
    val classifierSource: ClassifierSource,
    val canonicalSender: String?,
    val labels: Set<String>,
    val otpCode: String?,
    /** Package that auto-read this OTP (SMS Retriever hash or WebOTP match), if any. */
    val otpConsumedBy: String?,
    val retrieverHash: String?,
    val webOtpDomain: String?,
    val hasLink: Boolean,
    val hasAttachment: Boolean,
    /** Transaction amount in minor units, exactly as written in the SMS (never converted). */
    val amountMinor: Long?,
    /** ISO 4217 code of [amountMinor]. */
    val currency: String?,
    val direction: TransactionDirection?,
    val instrumentLast4: String?,
    val merchant: String?,
    /** Ledger account id (`Account.idFor`) of the extracted transaction, if any. */
    val accountId: String?,
    /** Full `ExtractedTransaction` as JSON (balance, reference, institution...), if any. */
    val transactionJson: String?,
    val starred: Boolean,
    val archived: Boolean,
    val indexedAt: Long,
    val body: String,
    val bodyPreview: String,
    /** JSON array of `app.dak.core.model.Attachment`. */
    val attachmentsJson: String,
    /** Enricher (template bundle / model) version this row was classified with; older rows get re-indexed. */
    val templateVersion: Int,
    val searchText: String,
    val searchSender: String,
    /**
     * Repeat group of an incoming message that arrived several times (exact duplicate body within 24 h, or the same
     * OTP code within 10 min, in one conversation): the key (`MessageKey.toString()`) of the oldest copy, shared by
     * every copy; null when the message is not repeated. Threads show one bubble per group (see
     * `app.dak.index.enrich.RepeatRules`).
     */
    val repeatGroup: String? = null,
    /**
     * Delivery-report state of an outgoing message as `DeliveryStatus.code` (-1 none, 32 pending, 0 delivered,
     * 64 failed; the provider's `Telephony.Sms.STATUS_*` values). Kept fresh for recent outgoing rows by the
     * reconcile (`ProviderReconciler`), since reports can arrive minutes after sending.
     */
    @ColumnInfo(defaultValue = "-1")
    val deliveryStatus: Int = -1,
    /** When the delivery report arrived, if the provider recorded it. */
    val deliveredAtMillis: Long? = null,
) {
    companion object {
        const val PREVIEW_LENGTH = 200
    }
}

/** Provider key of this row. (An extension rather than a property so Room never sees it as a column.) */
fun IndexedMessage.messageKey(): MessageKey = MessageKey(kind, providerId)

/** External-content FTS4 index over [IndexedMessage.searchText] and [IndexedMessage.searchSender]. */
@Fts4(contentEntity = IndexedMessage::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = Tables.MESSAGE_FTS)
data class MessageFts(
    val searchText: String,
    val searchSender: String,
)
