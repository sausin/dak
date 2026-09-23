package app.dak.index.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.dak.index.db.entity.AppSignatureRow
import app.dak.index.db.entity.AuditLogRow
import app.dak.index.db.entity.AutomationRuleRow
import app.dak.index.db.entity.AutomationRunRow
import app.dak.index.db.entity.BackfillStateRow
import app.dak.index.db.entity.SavedSearchRow
import app.dak.index.db.entity.ScheduledSendRow
import app.dak.index.db.entity.SearchHistoryRow
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedSearchDao {
    @Query("SELECT * FROM saved_search ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<SavedSearchRow>>

    @Query("SELECT * FROM saved_search WHERE pinned = 1 ORDER BY position ASC, id ASC")
    fun observePinned(): Flow<List<SavedSearchRow>>

    @Query("SELECT * FROM saved_search WHERE id = :id")
    suspend fun get(id: Long): SavedSearchRow?

    @Insert
    suspend fun insert(row: SavedSearchRow): Long

    @Update
    suspend fun update(row: SavedSearchRow): Int

    @Query("DELETE FROM saved_search WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM saved_search")
    suspend fun maxPosition(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHistory(row: SearchHistoryRow)

    @Query("SELECT * FROM search_history WHERE queryText = :queryText")
    suspend fun history(queryText: String): SearchHistoryRow?

    @Query("SELECT queryText FROM search_history ORDER BY lastUsedAt DESC LIMIT :limit")
    suspend fun recentQueries(limit: Int): List<String>

    @Query("DELETE FROM search_history WHERE queryText NOT IN (SELECT queryText FROM search_history ORDER BY lastUsedAt DESC LIMIT :keep)")
    suspend fun trimHistory(keep: Int): Int

    @Query("DELETE FROM search_history")
    suspend fun clearHistory(): Int
}

@Dao
interface ScheduledSendDao {
    @Query("SELECT * FROM scheduled_send WHERE status = 'PENDING' ORDER BY sendAtMillis ASC")
    fun observePending(): Flow<List<ScheduledSendRow>>

    @Query("SELECT * FROM scheduled_send WHERE conversationId = :conversationId AND status = 'PENDING' ORDER BY sendAtMillis ASC")
    fun observePendingFor(conversationId: String): Flow<List<ScheduledSendRow>>

    @Query("SELECT * FROM scheduled_send WHERE status = 'PENDING' AND sendAtMillis <= :nowMillis ORDER BY sendAtMillis ASC")
    suspend fun due(nowMillis: Long): List<ScheduledSendRow>

    @Query("SELECT * FROM scheduled_send WHERE id = :id")
    suspend fun get(id: Long): ScheduledSendRow?

    @Insert
    suspend fun insert(row: ScheduledSendRow): Long

    @Update
    suspend fun update(row: ScheduledSendRow): Int

    @Query("DELETE FROM scheduled_send WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
interface AutomationRuleDao {
    @Query("SELECT * FROM automation_rule ORDER BY position ASC, createdAt ASC")
    fun observeAll(): Flow<List<AutomationRuleRow>>

    @Query("SELECT * FROM automation_rule WHERE enabled = 1 ORDER BY position ASC, createdAt ASC")
    suspend fun enabled(): List<AutomationRuleRow>

    @Query("SELECT * FROM automation_rule ORDER BY position ASC, createdAt ASC")
    suspend fun all(): List<AutomationRuleRow>

    @Query("SELECT * FROM automation_rule WHERE id = :id")
    suspend fun get(id: String): AutomationRuleRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: AutomationRuleRow)

    @Query("UPDATE automation_rule SET enabled = :enabled, updatedAt = :nowMillis WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, nowMillis: Long): Int

    @Query("DELETE FROM automation_rule WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM automation_rule")
    suspend fun maxPosition(): Int
}

@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(row: AuditLogRow): Long

    @Query("SELECT * FROM audit_log ORDER BY atMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogRow>>

    @Query("DELETE FROM audit_log WHERE atMillis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long): Int
}

@Dao
interface AutomationRunDao {
    @Insert
    suspend fun insert(row: AutomationRunRow): Long

    @Query("SELECT * FROM automation_run WHERE ruleId = :ruleId ORDER BY atMillis DESC, id DESC LIMIT :limit")
    fun observeForRule(ruleId: String, limit: Int): Flow<List<AutomationRunRow>>

    @Query("SELECT * FROM automation_run ORDER BY atMillis DESC, id DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<AutomationRunRow>>

    @Query("SELECT COUNT(*) FROM automation_run WHERE ruleId = :ruleId AND outcome = :outcome AND atMillis >= :sinceMillis")
    suspend fun count(ruleId: String, outcome: String, sinceMillis: Long): Int

    /** Deletes rows older than [beforeMillis] except the newest [keepNewest] rows overall. */
    @Query(
        "DELETE FROM automation_run WHERE atMillis < :beforeMillis AND id NOT IN " +
            "(SELECT id FROM automation_run ORDER BY atMillis DESC, id DESC LIMIT :keepNewest)",
    )
    suspend fun trim(beforeMillis: Long, keepNewest: Int): Int
}

@Dao
interface AppSignatureDao {
    @Query("SELECT * FROM app_signature")
    suspend fun all(): List<AppSignatureRow>

    @Query("SELECT packageName FROM app_signature WHERE hash = :hash ORDER BY packageName LIMIT 1")
    suspend fun packageForHash(hash: String): String?

    @Query("SELECT DISTINCT packageName FROM app_signature WHERE isBrowser = 1 ORDER BY packageName")
    suspend fun browserPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<AppSignatureRow>)

    @Query("DELETE FROM app_signature WHERE packageName IN (:packageNames)")
    suspend fun deletePackages(packageNames: List<String>): Int
}

@Dao
interface BackfillStateDao {
    @Query("SELECT * FROM backfill_state WHERE id = 1")
    suspend fun get(): BackfillStateRow?

    @Query("SELECT * FROM backfill_state WHERE id = 1")
    fun observe(): Flow<BackfillStateRow?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: BackfillStateRow)
}
