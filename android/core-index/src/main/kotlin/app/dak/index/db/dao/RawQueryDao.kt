package app.dak.index.db.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind

/** One grouped conversation row produced by `ConversationSqlBuilder.page`. */
data class ConversationRow(
    val conversationId: String,
    val dateMillis: Long,
    val kind: MessageKind,
    val providerId: Long,
    val threadId: Long,
    val address: String,
    val mergeKey: String,
    val canonicalSender: String?,
    val snippet: String,
    val category: Category,
    val box: MessageBox,
    val hasAttachment: Boolean,
    val unreadCount: Int,
    val messageCount: Int,
    /** Comma-separated distinct sub ids. */
    val subIds: String?,
    /** Comma-separated distinct provider thread ids. */
    val threadIds: String?,
    val pinned: Boolean,
    val muted: Boolean,
    val archived: Boolean,
    val starred: Boolean,
    val incognito: Boolean = false,
    val groupName: String?,
    /** Repeat group of the newest message (to show "×3" on the snippet), if any. */
    val repeatGroup: String? = null,
    /** `DeliveryStatus.code` of the newest message (ticks on an outgoing snippet). */
    val deliveryStatus: Int = -1,
)

/** One grouped search row produced by `SearchSqlBuilder.messages`. */
data class SearchRow(
    val conversationId: String,
    val kind: MessageKind,
    val providerId: Long,
    val matchCount: Int,
    val sortValue: Long?,
)

data class CountRow(val n: Int)

data class IdRow(val id: Long)

/** Dynamic SQL built by the pure builders in `app.dak.index.sql`. One-shot (suspend) queries only. */
@Dao
interface RawQueryDao {
    @RawQuery
    suspend fun conversations(query: SupportSQLiteQuery): List<ConversationRow>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): CountRow?

    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<SearchRow>

    @RawQuery
    suspend fun ids(query: SupportSQLiteQuery): List<IdRow>
}
