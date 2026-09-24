package app.dak.index.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.dak.index.db.entity.ConversationPrefs
import app.dak.index.db.entity.SenderAlias
import app.dak.index.db.entity.SenderMergeGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationPrefsDao {
    @Query("SELECT * FROM conversation_prefs WHERE conversationId = :conversationId")
    suspend fun get(conversationId: String): ConversationPrefs?

    @Query("SELECT * FROM conversation_prefs WHERE conversationId = :conversationId")
    fun observe(conversationId: String): Flow<ConversationPrefs?>

    @Query("SELECT * FROM conversation_prefs WHERE conversationId IN (:conversationIds)")
    suspend fun getAll(conversationIds: List<String>): List<ConversationPrefs>

    @Query("SELECT * FROM conversation_prefs WHERE incognitoSince IS NOT NULL")
    suspend fun incognito(): List<ConversationPrefs>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(prefs: ConversationPrefs)

    @Query("DELETE FROM conversation_prefs WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String): Int
}

@Dao
interface SenderMergeDao {
    @Query("SELECT * FROM sender_merge_group WHERE mergeKey = :mergeKey")
    suspend fun group(mergeKey: String): SenderMergeGroup?

    @Query("SELECT * FROM sender_merge_group WHERE mergeKey = :mergeKey")
    fun observeGroup(mergeKey: String): Flow<SenderMergeGroup?>

    @Query("SELECT * FROM sender_merge_group ORDER BY displayName COLLATE NOCASE")
    fun observeGroups(): Flow<List<SenderMergeGroup>>

    @Query("SELECT * FROM sender_merge_group")
    suspend fun allGroups(): List<SenderMergeGroup>

    @Query("SELECT mergeKey FROM sender_merge_group WHERE displayName LIKE :like ESCAPE '\\' LIMIT 50")
    suspend fun mergeKeysNamed(like: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putGroup(group: SenderMergeGroup)

    @Query("DELETE FROM sender_merge_group WHERE mergeKey = :mergeKey")
    suspend fun deleteGroup(mergeKey: String): Int

    @Query("SELECT * FROM sender_alias")
    suspend fun aliases(): List<SenderAlias>

    @Query("SELECT * FROM sender_alias WHERE address = :addressUpper")
    suspend fun alias(addressUpper: String): SenderAlias?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAlias(alias: SenderAlias)

    @Query("DELETE FROM sender_alias WHERE address = :addressUpper")
    suspend fun deleteAlias(addressUpper: String): Int
}
