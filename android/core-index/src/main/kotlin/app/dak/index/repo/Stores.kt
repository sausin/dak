package app.dak.index.repo

import app.dak.index.SearchSort
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.IndexJson
import app.dak.index.db.entity.AuditLogRow
import app.dak.index.db.entity.AutomationRuleRow
import app.dak.index.db.entity.SavedSearchRow
import app.dak.index.db.entity.ScheduledSendRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Append-only audit trail of automated and destructive actions. */
@Singleton
class AuditLogRepository @Inject constructor(db: DakIndexDatabase) {
    private val dao = db.auditLogDao()

    suspend fun log(actor: String, action: String, target: String? = null, detail: String? = null) {
        dao.insert(AuditLogRow(atMillis = System.currentTimeMillis(), actor = actor, actionName = action, target = target, detail = detail))
    }

    fun recent(limit: Int = 200): Flow<List<AuditLogRow>> = dao.observeRecent(limit)

    /** Drops entries older than [maxAgeMillis] (default 90 days). */
    suspend fun trim(maxAgeMillis: Long = 90L * 24 * 60 * 60_000L): Int =
        dao.deleteOlderThan(System.currentTimeMillis() - maxAgeMillis)
}

/** A saved search as the UI sees it. */
data class SavedSearchItem(
    val id: Long,
    val name: String,
    /** Gmail-style query text; parse with `app.dak.search.QueryParser.parse`. */
    val query: String,
    val sort: SearchSort,
    val pinned: Boolean,
)

/** Saved searches ("Amazon refunds", "SIM 2 OTPs"); pinned ones show in the inbox as virtual folders. */
@Singleton
class SavedSearchRepository @Inject constructor(db: DakIndexDatabase) {
    private val dao = db.savedSearchDao()

    fun all(): Flow<List<SavedSearchItem>> = dao.observeAll().map { rows -> rows.map(::toItem) }

    fun pinned(): Flow<List<SavedSearchItem>> = dao.observePinned().map { rows -> rows.map(::toItem) }

    suspend fun save(name: String, query: String, sort: SearchSort = SearchSort.RECENT, pinned: Boolean = false): Long =
        dao.insert(
            SavedSearchRow(
                name = name,
                queryText = query,
                sort = sort.name,
                pinned = pinned,
                position = dao.maxPosition() + 1,
                createdAt = System.currentTimeMillis(),
            ),
        )

    suspend fun update(item: SavedSearchItem) {
        val row = dao.get(item.id) ?: return
        dao.update(row.copy(name = item.name, queryText = item.query, sort = item.sort.name, pinned = item.pinned))
    }

    suspend fun setPinned(id: Long, pinned: Boolean) {
        val row = dao.get(id) ?: return
        dao.update(row.copy(pinned = pinned))
    }

    /** Reorders saved searches to the given id order. */
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id -> dao.get(id)?.let { dao.update(it.copy(position = index)) } }
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
    }

    private fun toItem(row: SavedSearchRow) = SavedSearchItem(
        id = row.id,
        name = row.name,
        query = row.queryText,
        sort = SearchSort.entries.firstOrNull { it.name == row.sort } ?: SearchSort.RECENT,
        pinned = row.pinned,
    )
}

/** An automation rule as stored: the AST JSON is owned and interpreted by :automations. */
data class StoredRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val json: String,
    val position: Int,
    val updatedAt: Long,
)

/** CRUD for automation rules (opaque JSON). */
@Singleton
class AutomationStore @Inject constructor(db: DakIndexDatabase) {
    private val dao = db.automationRuleDao()

    fun observe(): Flow<List<StoredRule>> = dao.observeAll().map { rows -> rows.map(::toRule) }

    suspend fun all(): List<StoredRule> = dao.all().map(::toRule)

    suspend fun enabled(): List<StoredRule> = dao.enabled().map(::toRule)

    suspend fun get(id: String): StoredRule? = dao.get(id)?.let(::toRule)

    /** Inserts or replaces a rule; returns its id (generated when [id] is null). */
    suspend fun put(name: String, json: String, enabled: Boolean = true, id: String? = null): String {
        val now = System.currentTimeMillis()
        val existing = id?.let { dao.get(it) }
        val ruleId = id ?: UUID.randomUUID().toString()
        dao.put(
            AutomationRuleRow(
                id = ruleId,
                name = name,
                enabled = enabled,
                json = json,
                position = existing?.position ?: (dao.maxPosition() + 1),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        return ruleId
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled, System.currentTimeMillis())
    }

    suspend fun reorder(idsInOrder: List<String>) {
        idsInOrder.forEachIndexed { index, id -> dao.get(id)?.let { dao.put(it.copy(position = index)) } }
    }

    suspend fun delete(id: String) {
        dao.delete(id)
    }

    private fun toRule(row: AutomationRuleRow) =
        StoredRule(row.id, row.name, row.enabled, row.json, row.position, row.updatedAt)
}

/** Lifecycle of a scheduled send. */
enum class ScheduledSendStatus { PENDING, SENT, FAILED, CANCELLED }

data class ScheduledSend(
    val id: Long,
    val conversationId: String?,
    val addresses: List<String>,
    val body: String,
    val subId: Int,
    val sendAtMillis: Long,
    val status: ScheduledSendStatus,
    val ruleId: String?,
    val failureReason: String?,
)

/**
 * Persistence for scheduled sends. Alarm scheduling and the actual send (via `MessageSender`) belong to the
 * caller (:app / :automations); this store only records intent and outcome.
 */
@Singleton
class ScheduledSendStore @Inject constructor(db: DakIndexDatabase) {
    private val dao = db.scheduledSendDao()

    fun pending(): Flow<List<ScheduledSend>> = dao.observePending().map { rows -> rows.map(::toItem) }

    fun pendingFor(conversationId: String): Flow<List<ScheduledSend>> =
        dao.observePendingFor(conversationId).map { rows -> rows.map(::toItem) }

    suspend fun due(nowMillis: Long = System.currentTimeMillis()): List<ScheduledSend> = dao.due(nowMillis).map(::toItem)

    suspend fun get(id: Long): ScheduledSend? = dao.get(id)?.let(::toItem)

    suspend fun schedule(
        addresses: List<String>,
        body: String,
        subId: Int,
        sendAtMillis: Long,
        conversationId: String? = null,
        ruleId: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            ScheduledSendRow(
                conversationId = conversationId,
                addressesJson = IndexJson.json.encodeToString(ADDRESSES, addresses),
                body = body,
                subId = subId,
                sendAtMillis = sendAtMillis,
                status = ScheduledSendStatus.PENDING.name,
                createdAt = now,
                updatedAt = now,
                ruleId = ruleId,
                failureReason = null,
            ),
        )
    }

    /** Updates text, time or SIM of a pending send. */
    suspend fun edit(id: Long, body: String? = null, sendAtMillis: Long? = null, subId: Int? = null) {
        val row = dao.get(id) ?: return
        dao.update(
            row.copy(
                body = body ?: row.body,
                sendAtMillis = sendAtMillis ?: row.sendAtMillis,
                subId = subId ?: row.subId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markStatus(id: Long, status: ScheduledSendStatus, failureReason: String? = null) {
        val row = dao.get(id) ?: return
        dao.update(row.copy(status = status.name, failureReason = failureReason, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
    }

    private fun toItem(row: ScheduledSendRow) = ScheduledSend(
        id = row.id,
        conversationId = row.conversationId,
        addresses = runCatching { IndexJson.json.decodeFromString(ADDRESSES, row.addressesJson) }.getOrDefault(emptyList()),
        body = row.body,
        subId = row.subId,
        sendAtMillis = row.sendAtMillis,
        status = ScheduledSendStatus.entries.firstOrNull { it.name == row.status } ?: ScheduledSendStatus.PENDING,
        ruleId = row.ruleId,
        failureReason = row.failureReason,
    )

    private companion object {
        val ADDRESSES = ListSerializer(String.serializer())
    }
}
