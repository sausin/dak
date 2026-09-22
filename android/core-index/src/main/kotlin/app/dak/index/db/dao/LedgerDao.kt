package app.dak.index.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.dak.index.db.entity.AccountRow
import app.dak.index.db.entity.LedgerEntryRow
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_account ORDER BY lastActivityMillis DESC")
    fun observeAccounts(): Flow<List<AccountRow>>

    @Query("SELECT * FROM ledger_account WHERE id = :accountId")
    fun observeAccount(accountId: String): Flow<AccountRow?>

    @Query("SELECT * FROM ledger_account WHERE id = :accountId")
    suspend fun account(accountId: String): AccountRow?

    @Query("SELECT id FROM ledger_account")
    suspend fun accountIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAccount(row: AccountRow)

    @Query("DELETE FROM ledger_account WHERE id = :accountId")
    suspend fun deleteAccount(accountId: String): Int

    @Query("UPDATE ledger_account SET statementDay = :statementDay WHERE id = :accountId")
    suspend fun setStatementDay(accountId: String, statementDay: Int?): Int

    @Query("SELECT * FROM ledger_entry WHERE accountId = :accountId ORDER BY dateMillis DESC")
    fun observeEntries(accountId: String): Flow<List<LedgerEntryRow>>

    @Query("SELECT * FROM ledger_entry WHERE accountId = :accountId ORDER BY dateMillis ASC")
    suspend fun entries(accountId: String): List<LedgerEntryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntries(rows: List<LedgerEntryRow>)

    @Query("DELETE FROM ledger_entry WHERE accountId = :accountId")
    suspend fun deleteEntries(accountId: String): Int

    @Query("DELETE FROM ledger_entry")
    suspend fun clearEntries(): Int
}
