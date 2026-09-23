package app.dak.index.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.dak.core.model.MessageKind
import app.dak.index.sql.Tables

/**
 * Display name of a sender merge group (e.g. `HDFCBK` -> "HDFC Bank"). Display-layer only; the provider keeps the
 * underlying threads distinct. The previous state is kept so the last edit can be undone.
 */
@Entity(tableName = Tables.MERGE_GROUP)
data class SenderMergeGroup(
    @PrimaryKey val mergeKey: String,
    val displayName: String,
    val userEdited: Boolean,
    val previousDisplayName: String?,
    val previousUserEdited: Boolean,
    val updatedAt: Long,
)

/**
 * Overrides the merge key of one raw sender address (used to split a sender out of its group, or to merge it into
 * another). [previousMergeKey] supports undo; null means "no alias before".
 */
@Entity(tableName = Tables.SENDER_ALIAS)
data class SenderAlias(
    /** Raw address, upper-cased and trimmed. */
    @PrimaryKey val address: String,
    val mergeKey: String,
    val previousMergeKey: String?,
    val updatedAt: Long,
)

/**
 * A user fold rule for one sender channel (`SenderId.mergeKey` of an address: `HDFCBK` for `VM-HDFCBK`, the last
 * 10 digits for a number). [groupKey] is the merge key of the group the channel is folded into; a rule whose
 * [groupKey] equals [channel] means "unfolded": the channel stands alone even if the template bundle would fold it
 * into its brand. Display-layer only; the provider is never touched. User data: survives index rebuilds.
 */
@Entity(tableName = Tables.SENDER_FOLD)
data class SenderFold(
    @PrimaryKey val channel: String,
    val groupKey: String,
    val updatedAt: Long,
)

/**
 * A conversation id that no longer has messages because a fold/unfold (or a template update) moved them to
 * [newId]. Lets notification and search deep links that carry the old id still open the right thread.
 */
@Entity(tableName = Tables.CONVERSATION_ALIAS)
data class ConversationAlias(
    @PrimaryKey val oldId: String,
    val newId: String,
    val createdAt: Long,
)

/** Per-conversation user settings. Survives index rebuilds (it is not derived from the provider). */
@Entity(tableName = Tables.PREFS)
data class ConversationPrefs(
    @PrimaryKey val conversationId: String,
    /** Reply SIM chosen by the user; null = SIM of the last incoming message. */
    val replySubId: Int? = null,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val starred: Boolean = false,
    val bubbleColor: Int? = null,
    val fontScale: Float? = null,
    val alwaysTranslate: Boolean = false,
    val updatedAt: Long = 0L,
)

/**
 * User flags on single messages (star, archive), kept apart from [IndexedMessage] so they survive an index rebuild.
 * The same values are denormalized onto the index row for fast filtering.
 */
@Entity(tableName = Tables.MESSAGE_FLAG, primaryKeys = ["kind", "providerId"])
data class MessageFlag(
    val kind: MessageKind,
    val providerId: Long,
    val starred: Boolean,
    val archived: Boolean,
)
