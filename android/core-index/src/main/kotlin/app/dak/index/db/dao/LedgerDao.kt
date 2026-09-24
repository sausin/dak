package app.dak.index.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.dak.index.db.entity.AccountRow
import app.dak.index.db.entity.AccountHiddenRow
import app.dak.index.db.entity.AccountTypeOverrideRow
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

    /** Entries of [accountId] dated in `[fromMillis, toMillisExclusive)`, oldest first (a card's billing cycle). */
    @Query(
        "SELECT * FROM ledger_entry WHERE accountId = :accountId AND dateMillis >= :fromMillis " +
            "AND dateMillis < :toMillisExclusive ORDER BY dateMillis ASC",
    )
    suspend fun entriesBetween(accountId: String, fromMillis: Long, toMillisExclusive: Long): List<LedgerEntryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntries(rows: List<LedgerEntryRow>)

    /** Deletes the given entries of one account (see `LedgerRepository`'s diff write). */
    @Query("DELETE FROM ledger_entry WHERE accountId = :accountId AND messageKey IN (:messageKeys)")
    suspend fun deleteEntriesOf(accountId: String, messageKeys: List<String>): Int

    @Query("DELETE FROM ledger_entry WHERE accountId = :accountId")
    suspend fun deleteEntries(accountId: String): Int

    @Query("DELETE FROM ledger_entry")
    suspend fun clearEntries(): Int

    /** Debit cards / loans whose linked bank account is one of [accountIds]. */
    @Query("SELECT id FROM ledger_account WHERE linkedAccountId IN (:accountIds)")
    suspend fun accountsLinkedTo(accountIds: List<String>): List<String>

    /**
     * Spending dated on/after [sinceMillis], summed per account and original currency (for Passbook group totals):
     * debits, except own-account / investment transfers (a SIP debit is money moved, not spent; see
     * `app.dak.finance.passbook.AccountGroups.spentSince`).
     */
    @Query(
        "SELECT accountId, originalCurrency AS currency, SUM(originalMinor) AS totalMinor FROM ledger_entry " +
            "WHERE direction = 'DEBIT' AND transfer = 0 AND dateMillis >= :sinceMillis GROUP BY accountId, originalCurrency",
    )
    fun observeDebitsSince(sinceMillis: Long): Flow<List<AccountCurrencyTotalRow>>

    // ---- manual account types (user data) ----

    @Query("SELECT * FROM account_type_override")
    fun observeTypeOverrides(): Flow<List<AccountTypeOverrideRow>>

    @Query("SELECT * FROM account_type_override")
    suspend fun typeOverrides(): List<AccountTypeOverrideRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTypeOverride(row: AccountTypeOverrideRow)

    @Query("DELETE FROM account_type_override WHERE accountId = :accountId")
    suspend fun deleteTypeOverride(accountId: String): Int

    // ---- accounts removed from the Passbook (user data) ----

    @Query("SELECT * FROM account_hidden")
    fun observeHidden(): Flow<List<AccountHiddenRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHidden(row: AccountHiddenRow)

    @Query("DELETE FROM account_hidden WHERE accountId = :accountId")
    suspend fun deleteHidden(accountId: String): Int
}

/** One `(account, currency)` total of [LedgerDao.observeDebitsSince]. */
data class AccountCurrencyTotalRow(
    val accountId: String,
    val currency: String,
    val totalMinor: Long,
)
