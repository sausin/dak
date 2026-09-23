package app.dak.index.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.dak.index.db.entity.AccountAliasRow
import app.dak.index.db.entity.ConversationAlias
import app.dak.index.db.entity.SenderFold
import kotlinx.coroutines.flow.Flow

@Dao
interface SenderFoldDao {
    @Query("SELECT * FROM sender_fold")
    suspend fun all(): List<SenderFold>

    @Query("SELECT * FROM sender_fold")
    fun observeAll(): Flow<List<SenderFold>>

    @Query("SELECT * FROM sender_fold WHERE channel = :channel")
    suspend fun get(channel: String): SenderFold?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(fold: SenderFold)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(folds: List<SenderFold>)

    @Query("DELETE FROM sender_fold WHERE channel = :channel")
    suspend fun delete(channel: String): Int
}

@Dao
interface ConversationAliasDao {
    @Query("SELECT * FROM conversation_alias WHERE oldId = :oldId")
    suspend fun get(oldId: String): ConversationAlias?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(alias: ConversationAlias)

    @Query("DELETE FROM conversation_alias WHERE oldId = :oldId")
    suspend fun delete(oldId: String): Int

    /** Re-points aliases that led to [fromId] so chains stay one hop long. */
    @Query("UPDATE conversation_alias SET newId = :toId WHERE newId = :fromId")
    suspend fun repoint(fromId: String, toId: String): Int
}

@Dao
interface AccountAliasDao {
    @Query("SELECT * FROM account_alias")
    suspend fun all(): List<AccountAliasRow>

    @Query("SELECT * FROM account_alias")
    fun observeAll(): Flow<List<AccountAliasRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: AccountAliasRow)

    @Query("DELETE FROM account_alias WHERE aliasId = :aliasId AND same = 1")
    suspend fun deleteMergesOf(aliasId: String): Int

    @Query("DELETE FROM account_alias WHERE (aliasId = :a AND canonicalId = :b) OR (aliasId = :b AND canonicalId = :a)")
    suspend fun deletePair(a: String, b: String): Int
}
