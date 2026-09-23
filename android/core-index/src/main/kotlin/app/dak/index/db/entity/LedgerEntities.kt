package app.dak.index.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import app.dak.index.sql.Tables

/**
 * A financial account inferred from SMS (see `app.dak.finance.ledger.Account`), with its cached balance.
 * [statementDay] is user-configured and preserved across ledger recomputation.
 */
@Entity(tableName = Tables.ACCOUNT)
data class AccountRow(
    /** `Account.idFor(institution, instrument, last4)`. */
    @PrimaryKey val id: String,
    val institution: String,
    val instrument: InstrumentType,
    val last4: String?,
    val homeCurrency: String,
    val statementDay: Int?,
    /** `KNOWN`, `UNKNOWN` or `NO_INFO` (see `app.dak.finance.ledger.BalanceState`). */
    val balanceState: String,
    val balanceMinor: Long?,
    val balanceCurrency: String?,
    val balanceAsOfMillis: Long?,
    /** For `UNKNOWN`: date of the unsettled transaction since which the balance is unknown. */
    val unknownSinceMillis: Long?,
    val entryCount: Int,
    val lastActivityMillis: Long,
    val updatedAt: Long,
    /** The number as the bank last showed it (e.g. `XX440065`), if the SMS showed a mask. */
    val maskedNumber: String? = null,
)

/**
 * A user decision about two ledger account ids (from an `AliasSuggestion` card or a manual merge):
 * [same] = true merges [aliasId] into [canonicalId] (the ledger then posts the alias's messages to the canonical
 * account); false records "different accounts" so the pair is never suggested again. User data: survives ledger
 * recomputation and index rebuilds.
 */
@Entity(tableName = Tables.ACCOUNT_ALIAS, primaryKeys = ["aliasId", "canonicalId"])
data class AccountAliasRow(
    val aliasId: String,
    val canonicalId: String,
    val same: Boolean,
    val decidedAt: Long,
)

/**
 * One posted ledger entry (see `app.dak.finance.ledger.LedgerEntry`). Derived from the index; recomputed per
 * account whenever a message of that account is (re)indexed. Decimal values are stored as plain strings.
 */
@Entity(tableName = Tables.LEDGER_ENTRY, indices = [Index(value = ["accountId", "dateMillis"])])
data class LedgerEntryRow(
    /** Source message key, `MessageKey.toString()` form (e.g. `sms:42`). */
    @PrimaryKey val messageKey: String,
    val accountId: String,
    val dateMillis: Long,
    val direction: TransactionDirection,
    val originalMinor: Long,
    val originalCurrency: String,
    val indicativeMinor: Long?,
    val indicativeCurrency: String?,
    val rate: String?,
    val rateDateMillis: Long?,
    val settled: Boolean,
    val markupPercent: String?,
    val balanceAfterMinor: Long?,
    val balanceAfterCurrency: String?,
    val merchant: String?,
    val reference: String?,
)
