package app.dak.index.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.index.sql.Tables

/**
 * A soft-deleted message: a full copy taken *before* the provider delete, so it can be restored into its original
 * thread. [messageJson] is the serialized `app.dak.core.model.Message` (body, attachment metadata, box, date, SIM,
 * read state); the other columns are copies for listing and searching.
 */
@Entity(
    tableName = Tables.BIN,
    indices = [Index(value = ["purgeAt"]), Index(value = ["deletedAt"]), Index(value = ["kind", "providerId"])],
)
data class BinEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: MessageKind,
    /** Provider id at deletion time (no longer valid in the provider). */
    val providerId: Long,
    val threadId: Long,
    val conversationId: String,
    val subId: Int,
    val address: String,
    val mergeKey: String,
    val body: String,
    val dateMillis: Long,
    val box: MessageBox,
    val read: Boolean,
    val hasAttachment: Boolean,
    /** JSON array of `app.dak.core.model.Attachment` metadata. */
    val attachmentsJson: String,
    val category: Category,
    val otpCode: String?,
    /** `manual`, `auto-rule:<name>`, `auto-consumed:<pkg>` or `auto-otp` (see `DeletedBy`). */
    val deletedBy: String,
    val deletedAt: Long,
    /** When the purge job removes it; [Long.MAX_VALUE] = until the user empties the bin. */
    val purgeAt: Long,
    val messageJson: String,
    /** Normalized body + sender for `in:bin` search. */
    val searchText: String,
)
