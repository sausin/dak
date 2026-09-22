package app.dak.index.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.dak.index.db.entity.BinEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface BinDao {
    @Insert
    suspend fun insert(entry: BinEntry): Long

    @Query("SELECT * FROM bin_entry WHERE id = :id")
    suspend fun get(id: Long): BinEntry?

    @Query("SELECT * FROM bin_entry WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<BinEntry>

    @Query("SELECT * FROM bin_entry ORDER BY deletedAt DESC, id DESC")
    fun observeAll(): Flow<List<BinEntry>>

    @Query("SELECT COUNT(*) FROM bin_entry")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM bin_entry WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM bin_entry WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>): Int

    @Query("DELETE FROM bin_entry WHERE purgeAt <= :nowMillis")
    suspend fun deleteExpired(nowMillis: Long): Int

    @Query("DELETE FROM bin_entry")
    suspend fun clear(): Int
}
