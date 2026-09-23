package app.dak.index.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.dak.index.sql.Tables

/** A saved search ("Amazon refunds", "SIM 2 OTPs"), optionally pinned to the inbox as a virtual folder. */
@Entity(tableName = Tables.SAVED_SEARCH)
data class SavedSearchRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Query in the Gmail-style text form (`app.dak.search.SearchQuery.toQueryString()`). */
    val queryText: String,
    /** `app.dak.index.SearchSort` name. */
    val sort: String,
    val pinned: Boolean,
    val position: Int,
    val createdAt: Long,
)

/** A recently executed query, for typed-ahead suggestions. */
@Entity(tableName = Tables.SEARCH_HISTORY)
data class SearchHistoryRow(
    @PrimaryKey val queryText: String,
    val lastUsedAt: Long,
    val useCount: Int,
)

/** A message scheduled to be sent later (alarm/WorkManager side lives in :app / :automations). */
@Entity(tableName = Tables.SCHEDULED_SEND, indices = [Index(value = ["sendAtMillis"]), Index(value = ["status"])])
data class ScheduledSendRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String?,
    /** JSON array of recipient addresses. */
    val addressesJson: String,
    val body: String,
    val subId: Int,
    val sendAtMillis: Long,
    /** `PENDING`, `SENT`, `FAILED`, `CANCELLED` (see `ScheduledSendStatus`). */
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Automation rule that created it, if any. */
    val ruleId: String?,
    val failureReason: String?,
)

/** An automation rule; the rule AST is owned by :automations and stored as opaque JSON. */
@Entity(tableName = Tables.AUTOMATION_RULE)
data class AutomationRuleRow(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val json: String,
    val position: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Append-only audit trail of automated and destructive actions (rule fired, auto-delete, restore, rebuild...). */
@Entity(tableName = Tables.AUDIT_LOG, indices = [Index(value = ["atMillis"])])
data class AuditLogRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    /** Who acted: `user`, `rule:<id>`, `otp-lifecycle`, `index`... */
    val actor: String,
    val actionName: String,
    /** Message key (`sms:12`) or conversation id the action touched, if any. */
    val target: String?,
    val detail: String?,
)

/**
 * Durable per-rule run log of automations: one row per outbound action (forward, reply, webhook, relay...) that ran or
 * was skipped for a message, so the user can check exactly what left the phone. Kept apart from [AuditLogRow] because
 * it has its own, longer retention (see `AutomationRunStore`), and keyed by the stable rule id with a name snapshot so
 * runs of a deleted rule stay readable.
 */
@Entity(
    tableName = Tables.AUTOMATION_RUN,
    indices = [Index(value = ["ruleId", "atMillis"]), Index(value = ["atMillis"])],
)
data class AutomationRunRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: String,
    val ruleName: String,
    val atMillis: Long,
    /** Source message key (`sms:12`), when the run was for a message. */
    val messageKey: String?,
    /** Display conversation of the source message, to open it from the history. */
    val conversationId: String?,
    /** Who sent the source message (address or sender header). */
    val sourceLabel: String?,
    /** `ForwardSms`, `Webhook`, ... (the action type's name). */
    val actionKind: String,
    /** What the user calls the destination (contact name), if known. */
    val destinationLabel: String?,
    /** Normalised destination: phone number, webhook host, relay channel and recipient. */
    val destination: String?,
    /** `SENT`, `FAILED` or `SKIPPED`. */
    val outcome: String,
    /** Why it was skipped or failed (a short code), if it was. */
    val reason: String?,
    /** One-line preview of what was (or would have been) sent, OTP codes masked. */
    val textPreview: String?,
)

/** SMS Retriever hash of an installed package's signing certificate (one row per certificate). */
@Entity(
    tableName = Tables.APP_SIGNATURE,
    primaryKeys = ["packageName", "hash"],
    indices = [Index(value = ["hash"])],
)
data class AppSignatureRow(
    val packageName: String,
    /** 11-char SMS Retriever app hash. */
    val hash: String,
    val isBrowser: Boolean,
    /** `PackageInfo.lastUpdateTime` when computed, to skip unchanged packages on refresh. */
    val packageUpdatedAt: Long,
    val computedAt: Long,
)

/** Single-row progress of the index backfill / re-index (id is always [SINGLETON_ID]). */
@Entity(tableName = Tables.BACKFILL_STATE)
data class BackfillStateRow(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** `app.dak.index.BackfillStage` name. */
    val stage: String,
    /** Stage 2 continues with messages strictly older than this. */
    val cursorMillis: Long,
    val total: Int,
    /** `app.dak.index.IndexSchedule` name chosen for stage 2. */
    val schedule: String,
    /** Why the current pass runs: `INITIAL`, `REINDEX`, `RESTORE`, `REBUILD`. */
    val reason: String,
    /** Enricher version this pass indexes with (progress counts rows at this version). */
    val enricherVersion: Int,
    val startedAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
