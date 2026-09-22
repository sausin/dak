package app.dak.index.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.dak.core.model.MessageKind
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.MessageFlag
import kotlinx.coroutines.flow.Flow

/** A provider key as stored in the index. */
data class KeyRow(val kind: MessageKind, val providerId: Long)

/**
 * An index row plus [otpRepeatedLater]: true when the same OTP code arrived again in this conversation within
 * `OtpTiming.REPEAT_WINDOW_MILLIS` (the UI collapses the older duplicate).
 */
data class MessageWithRepeat(
    @Embedded val message: IndexedMessage,
    val otpRepeatedLater: Boolean,
)

/**
 * DAO over [IndexedMessage]. Message kinds are passed as `MessageKind.name` strings in queries.
 * All writes go through the ingestor / repositories; UI reads through the repositories' paging sources.
 */
@Dao
interface MessageDao {
    /**
     * Inserts rows whose key is new; returns the row id per input, `-1` for keys that already exist.
     * Upserts are done as insert-ignore + update (never REPLACE) so the FTS content triggers see an UPDATE and
     * keep the full-text index consistent; see `IndexWriter`.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<IndexedMessage>): List<Long>

    @Update
    suspend fun update(rows: List<IndexedMessage>): Int

    @Query("SELECT * FROM indexed_message WHERE kind = :kind AND providerId = :providerId")
    suspend fun get(kind: String, providerId: Long): IndexedMessage?

    @Query("SELECT * FROM indexed_message WHERE kind = :kind AND providerId = :providerId")
    fun observe(kind: String, providerId: Long): Flow<IndexedMessage?>

    @Query("SELECT * FROM indexed_message WHERE kind = :kind AND providerId IN (:providerIds)")
    suspend fun getAll(kind: String, providerIds: List<Long>): List<IndexedMessage>

    @Query("DELETE FROM indexed_message WHERE kind = :kind AND providerId IN (:providerIds)")
    suspend fun deleteAll(kind: String, providerIds: List<Long>): Int

    @Query("DELETE FROM indexed_message")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM indexed_message")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM indexed_message WHERE templateVersion = :version")
    fun observeCountAtVersion(version: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM indexed_message WHERE templateVersion = :version")
    suspend fun countAtVersion(version: Int): Int

    @Query("SELECT COALESCE(MAX(providerId), 0) FROM indexed_message WHERE kind = :kind")
    suspend fun maxProviderId(kind: String): Long

    @Query("SELECT kind, providerId FROM indexed_message")
    suspend fun allKeys(): List<KeyRow>

    @Query("SELECT DISTINCT threadId FROM indexed_message")
    suspend fun indexedThreadIds(): List<Long>

    @Query(
        "SELECT m.*, EXISTS(SELECT 1 FROM indexed_message o WHERE o.conversationId = m.conversationId " +
            "AND o.otpCode = m.otpCode AND o.dateMillis > m.dateMillis AND o.dateMillis <= m.dateMillis + 600000) " +
            "AS otpRepeatedLater FROM indexed_message m WHERE m.conversationId = :conversationId " +
            "ORDER BY m.dateMillis DESC, m.providerId DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageByConversation(conversationId: String, limit: Int, offset: Int): List<MessageWithRepeat>

    @Query(
        "SELECT m.*, EXISTS(SELECT 1 FROM indexed_message o WHERE o.threadId = m.threadId " +
            "AND o.otpCode = m.otpCode AND o.dateMillis > m.dateMillis AND o.dateMillis <= m.dateMillis + 600000) " +
            "AS otpRepeatedLater FROM indexed_message m WHERE m.threadId = :threadId " +
            "ORDER BY m.dateMillis DESC, m.providerId DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageByThread(threadId: Long, limit: Int, offset: Int): List<MessageWithRepeat>

    @Query("SELECT COUNT(*) FROM indexed_message WHERE threadId = :threadId")
    suspend fun countInThread(threadId: Long): Int

    @Query("SELECT DISTINCT threadId FROM indexed_message WHERE conversationId = :conversationId")
    suspend fun threadIdsOf(conversationId: String): List<Long>

    @Query("SELECT address FROM indexed_message WHERE conversationId = :conversationId GROUP BY address ORDER BY MAX(dateMillis) DESC")
    suspend fun addressesOf(conversationId: String): List<String>

    @Query("SELECT address FROM indexed_message WHERE threadId = :threadId GROUP BY address ORDER BY MAX(dateMillis) DESC")
    suspend fun addressesInThread(threadId: Long): List<String>

    @Query(
        "SELECT subId FROM indexed_message WHERE conversationId = :conversationId AND box = 'INBOX' " +
            "ORDER BY dateMillis DESC LIMIT 1",
    )
    suspend fun lastIncomingSubId(conversationId: String): Int?

    @Query(
        "SELECT subId FROM indexed_message WHERE threadId = :threadId AND box = 'INBOX' " +
            "ORDER BY dateMillis DESC LIMIT 1",
    )
    suspend fun lastIncomingSubIdInThread(threadId: Long): Int?

    @Query("UPDATE indexed_message SET read = 1, seen = 1 WHERE conversationId = :conversationId AND read = 0")
    suspend fun markConversationRead(conversationId: String): Int

    @Query("UPDATE indexed_message SET read = 1, seen = 1 WHERE threadId = :threadId AND read = 0")
    suspend fun markThreadRead(threadId: Long): Int

    @Query("UPDATE indexed_message SET read = 1, seen = 1 WHERE kind = :kind AND providerId = :providerId")
    suspend fun markRead(kind: String, providerId: Long): Int

    @Query("UPDATE indexed_message SET starred = :starred WHERE kind = :kind AND providerId = :providerId")
    suspend fun setStarred(kind: String, providerId: Long, starred: Boolean): Int

    @Query("UPDATE indexed_message SET archived = :archived WHERE kind = :kind AND providerId = :providerId")
    suspend fun setArchived(kind: String, providerId: Long, archived: Boolean): Int

    @Query("UPDATE indexed_message SET otpConsumedBy = :packageName WHERE kind = :kind AND providerId = :providerId")
    suspend fun setOtpConsumedBy(kind: String, providerId: Long, packageName: String?): Int

    /** Re-groups every row of one raw sender (after a split/merge edit). [addressUpper] is trimmed + upper-cased. */
    @Query(
        "UPDATE indexed_message SET mergeKey = :mergeKey, conversationId = :conversationId " +
            "WHERE UPPER(TRIM(address)) = :addressUpper",
    )
    suspend fun regroupAddress(addressUpper: String, mergeKey: String, conversationId: String): Int

    @Query("SELECT * FROM indexed_message WHERE UPPER(TRIM(address)) = :addressUpper LIMIT 1")
    suspend fun anyFromAddress(addressUpper: String): IndexedMessage?

    @Query("SELECT * FROM indexed_message WHERE accountId = :accountId ORDER BY dateMillis ASC")
    suspend fun byAccount(accountId: String): List<IndexedMessage>

    @Query("SELECT DISTINCT accountId FROM indexed_message WHERE accountId IS NOT NULL")
    suspend fun accountIds(): List<String>

    /** Recent distinct sender display strings (brand if known, else raw address), for suggestions. */
    @Query(
        "SELECT name FROM (SELECT COALESCE(canonicalSender, address) AS name, MAX(dateMillis) AS lastDate " +
            "FROM indexed_message GROUP BY name) ORDER BY lastDate DESC LIMIT :limit",
    )
    suspend fun recentSenderNames(limit: Int): List<String>

    /** Merge keys whose brand or raw address contains [like] (a LIKE pattern), for resolving `from:`. */
    @Query(
        "SELECT DISTINCT mergeKey FROM indexed_message WHERE canonicalSender LIKE :like ESCAPE '\\' " +
            "OR address LIKE :like ESCAPE '\\' LIMIT 50",
    )
    suspend fun mergeKeysMatching(like: String): List<String>

    // ---- per-message user flags (survive rebuilds) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFlag(flag: MessageFlag)

    @Query("SELECT * FROM message_flag WHERE kind = :kind AND providerId IN (:providerIds)")
    suspend fun flags(kind: String, providerIds: List<Long>): List<MessageFlag>

    @Query("SELECT * FROM message_flag WHERE kind = :kind AND providerId = :providerId")
    suspend fun flag(kind: String, providerId: Long): MessageFlag?

    @Query("DELETE FROM message_flag WHERE kind = :kind AND providerId IN (:providerIds)")
    suspend fun deleteFlags(kind: String, providerIds: List<Long>): Int
}
