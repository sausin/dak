package app.dak.index.repo

import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.AutomationRunRow
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The automation run log: what each rule sent (or skipped) for which message. The outcome/reason vocabulary and the
 * UI model belong to :automations (`app.dak.automations.history`); this stores rows as given.
 *
 * Retention is its own (never the audit log's 90 days): rows are kept for [RETENTION_MILLIS] and, beyond that, the
 * newest [RETENTION_MIN_ROWS] are kept anyway, whichever keeps more.
 */
@Singleton
class AutomationRunStore @Inject constructor(db: DakIndexDatabase) {
    private val dao = db.automationRunDao()

    suspend fun add(row: AutomationRunRow): Long = dao.insert(row)

    /** Runs of one rule (by its stable id), newest first. */
    fun forRule(ruleId: String, limit: Int = MAX_ROWS_SHOWN): Flow<List<AutomationRunRow>> = dao.observeForRule(ruleId, limit)

    /** Runs of every rule, including deleted ones, newest first. */
    fun all(limit: Int = MAX_ROWS_SHOWN): Flow<List<AutomationRunRow>> = dao.observeAll(limit)

    /** How many runs of [ruleId] ended with [outcome] since [sinceMillis]. */
    suspend fun count(ruleId: String, outcome: String, sinceMillis: Long): Int = dao.count(ruleId, outcome, sinceMillis)

    /** Applies the retention policy; returns how many rows were removed. */
    suspend fun trim(nowMillis: Long = System.currentTimeMillis()): Int =
        dao.trim(beforeMillis = nowMillis - RETENTION_MILLIS, keepNewest = RETENTION_MIN_ROWS)

    companion object {
        /** Keep every run for at least a year. */
        const val RETENTION_MILLIS: Long = 366L * 24 * 60 * 60 * 1000

        /** ...and at least this many of the newest runs, however old. */
        const val RETENTION_MIN_ROWS: Int = 5_000

        /** Most rows a history screen loads at once. */
        const val MAX_ROWS_SHOWN: Int = 5_000
    }
}
