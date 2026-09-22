package app.dak.index.sync

import app.dak.classify.TemplateBundle
import app.dak.index.BackfillProgress
import app.dak.index.BackfillReason
import app.dak.index.BackfillStage
import app.dak.index.IndexSchedule
import app.dak.index.crypto.IndexDatabaseFactory
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.BackfillStateRow
import app.dak.index.enrich.DefaultMessageEnricher
import app.dak.index.enrich.MessageEnricher
import app.dak.index.repo.AuditLogRepository
import app.dak.index.repo.LedgerRepository
import app.dak.telephony.ProviderReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-stage backfill, re-index and rebuild of the index.
 *
 * - Stage 1 ([runStageOne]) runs in-process on first start: the most recent messages (last 30 days or 1,000,
 *   whichever is larger), so the app is usable within seconds.
 * - Stage 2 runs in [BackfillWorker] under the user's [IndexSchedule], newest to oldest in batches of 500 via
 *   `ProviderReader.messagesBefore`, persisting its cursor so it resumes after any stop.
 * - The same machinery re-indexes after a template update or restore ([requestReindex]) and rebuilds ([rebuild]).
 */
@Singleton
class IndexMaintenance @Inject constructor(
    private val db: DakIndexDatabase,
    private val opened: IndexDatabaseFactory.OpenedIndex,
    private val reader: ProviderReader,
    private val ingestor: IndexIngestor,
    private val enricher: MessageEnricher,
    private val scheduler: BackfillScheduler,
    private val ledger: LedgerRepository,
    private val audit: AuditLogRepository,
) {
    private val stateDao = db.backfillStateDao()
    private val messageDao = db.messageDao()
    private val mutex = Mutex()

    /** Outcome of one stage-2 run. */
    enum class StageTwoOutcome { DONE, WINDOW_CLOSED, NOTHING_TO_DO }

    private data class Snapshot(val state: BackfillStateRow?, val done: Int)

    /** Backfill / re-index progress for the settings row. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val progress: Flow<BackfillProgress> =
        stateDao.observe().flatMapLatest { state ->
            if (state == null) {
                flowOf(Snapshot(null, 0))
            } else {
                messageDao.observeCountAtVersion(state.enricherVersion).map { done -> Snapshot(state, done) }
            }
        }.combine(scheduler.running) { snapshot, running ->
            toProgress(snapshot.state, snapshot.done, running)
        }.flowOn(Dispatchers.IO)

    /** Current schedule choice (defaults to "When plugged in" until the user picks one). */
    val schedule: IndexSchedule get() = scheduler.schedule

    /**
     * Called by `IndexSync.start()`: runs stage 1 when nothing is indexed yet (first run, or the database had to
     * be recreated), resumes a pending stage 2, and starts a re-index when the enricher version changed.
     */
    suspend fun ensureStarted(): Unit = withContext(Dispatchers.IO) {
        val state = stateDao.get()
        when {
            state == null -> {
                val reason = if (opened.recreated && scheduler.hasChosenSchedule) BackfillReason.REBUILD else BackfillReason.INITIAL
                if (reason == BackfillReason.REBUILD) audit.log("index", "index.recreated", detail = "database recreated; rebuilding")
                runStageOne(reason)
            }
            state.stage == BackfillStage.STAGE1.name -> runStageOne(reasonOf(state))
            state.stage == BackfillStage.STAGE2.name -> scheduler.enqueueBackfill(scheduleOf(state), replace = false)
            state.stage == BackfillStage.DONE.name && state.enricherVersion != enricher.version ->
                requestReindex(BackfillReason.REINDEX)
            else -> Unit
        }
    }

    /** Records the onboarding choice and (re)schedules stage 2 with it if a pass is pending. */
    suspend fun chooseSchedule(schedule: IndexSchedule): Unit = withContext(Dispatchers.IO) {
        scheduler.schedule = schedule
        val pending = stateDao.get()?.stage == BackfillStage.STAGE2.name
        // Stop a running worker first so it releases the lock and cannot overwrite the state written below.
        if (pending) scheduler.cancelBackfill()
        mutex.withLock {
            val state = stateDao.get() ?: return@withLock
            stateDao.put(state.copy(schedule = schedule.name, updatedAt = System.currentTimeMillis()))
        }
        if (pending) scheduler.enqueueBackfill(schedule, replace = true)
    }

    /**
     * Re-indexes everything, newest first, under [schedule] (default: the user's choice). Rows keep showing while
     * they are re-enriched. Use after a template-bundle update ([BackfillReason.REINDEX]) or a backup restore
     * ([BackfillReason.RESTORE]).
     */
    suspend fun requestReindex(reason: BackfillReason, schedule: IndexSchedule? = null): Unit = withContext(Dispatchers.IO) {
        val chosen = schedule ?: scheduler.schedule
        val now = System.currentTimeMillis()
        scheduler.cancelBackfill()
        val total = runCatching { reader.totalMessageCount() }.getOrDefault(0)
        mutex.withLock {
            stateDao.put(
                BackfillStateRow(
                    stage = BackfillStage.STAGE2.name,
                    cursorMillis = Long.MAX_VALUE,
                    total = total,
                    schedule = chosen.name,
                    reason = reason.name,
                    enricherVersion = enricher.version,
                    startedAt = now,
                    updatedAt = now,
                    finishedAt = null,
                ),
            )
        }
        audit.log("index", "index.reindex", detail = reason.name)
        scheduler.enqueueBackfill(chosen, replace = true)
    }

    /**
     * Installs a verified OTA template bundle (`TemplateBundle.parse(json, verifier)`) and, when the enricher
     * version changed, re-indexes under the user's schedule. Returns false if the active enricher does not support
     * template updates.
     */
    suspend fun installTemplates(bundle: TemplateBundle): Boolean {
        val updatable = enricher as? DefaultMessageEnricher ?: return false
        val before = withContext(Dispatchers.IO) { enricher.version }
        val after = updatable.installTemplates(bundle)
        if (after != before) requestReindex(BackfillReason.REINDEX)
        return true
    }

    /** Wipes all derived index data (user data such as prefs, bin and rules is kept) and backfills again. */
    suspend fun rebuild(): Unit = withContext(Dispatchers.IO) {
        scheduler.cancelBackfill()
        mutex.withLock {
            messageDao.clear()
            db.ledgerDao().clearEntries()
        }
        audit.log("user", "index.rebuild")
        runStageOne(BackfillReason.REBUILD)
    }

    /** Stage 1: index the most recent messages right away, then hand the rest to the stage-2 worker. */
    suspend fun runStageOne(reason: BackfillReason): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val version = enricher.version
            val schedule = scheduler.schedule
            val base = BackfillStateRow(
                stage = BackfillStage.STAGE1.name,
                cursorMillis = Long.MAX_VALUE,
                total = 0,
                schedule = schedule.name,
                reason = reason.name,
                enricherVersion = version,
                startedAt = now,
                updatedAt = now,
                finishedAt = null,
            )
            stateDao.put(base)
            val recent = reader.recentMessages(now - STAGE1_WINDOW_MILLIS, STAGE1_MIN_COUNT)
            for (chunk in recent.chunked(STAGE1_CHUNK)) ingestor.ingest(chunk)
            val total = runCatching { reader.totalMessageCount() }.getOrDefault(recent.size)
            val oldest = recent.minOfOrNull { it.dateMillis }
            val finished = System.currentTimeMillis()
            if (oldest == null || recent.size >= total) {
                stateDao.put(base.copy(stage = BackfillStage.DONE.name, total = total, updatedAt = finished, finishedAt = finished))
            } else {
                // +1 so messages sharing the boundary timestamp are fetched again (upserts are idempotent).
                stateDao.put(base.copy(stage = BackfillStage.STAGE2.name, cursorMillis = oldest + 1, total = total, updatedAt = finished))
                scheduler.enqueueBackfill(schedule, replace = true)
            }
        }
    }

    /**
     * Stage 2 (called by [BackfillWorker]): batches of [STAGE2_BATCH] older messages until the provider is
     * exhausted, or, for [IndexSchedule.TONIGHT], until the night window closes.
     */
    suspend fun runStageTwo(
        schedule: IndexSchedule,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): StageTwoOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            var state = stateDao.get() ?: return@withLock StageTwoOutcome.NOTHING_TO_DO
            if (state.stage != BackfillStage.STAGE2.name) return@withLock StageTwoOutcome.NOTHING_TO_DO
            val total = runCatching { reader.totalMessageCount() }.getOrDefault(state.total)
            state = state.copy(total = total)
            stateDao.put(state)
            var cursor = state.cursorMillis
            var outcome: StageTwoOutcome? = null
            while (outcome == null) {
                if (schedule == IndexSchedule.TONIGHT && !scheduler.insideNightWindow(ZonedDateTime.now())) {
                    outcome = StageTwoOutcome.WINDOW_CLOSED
                    continue
                }
                val batch = reader.messagesBefore(cursor, STAGE2_BATCH)
                if (batch.isEmpty()) {
                    val now = System.currentTimeMillis()
                    stateDao.put(state.copy(stage = BackfillStage.DONE.name, cursorMillis = cursor, updatedAt = now, finishedAt = now))
                    if (state.reason != BackfillReason.INITIAL.name) ledger.recomputeAll()
                    audit.log("index", "index.backfill.done", detail = state.reason)
                    outcome = StageTwoOutcome.DONE
                    continue
                }
                ingestor.ingest(batch)
                cursor = BackfillCursor.next(cursor, batch.minOf { it.dateMillis })
                state = state.copy(cursorMillis = cursor, updatedAt = System.currentTimeMillis())
                stateDao.put(state)
                onProgress(messageDao.countAtVersion(state.enricherVersion), total)
            }
            outcome
        }
    }

    private fun scheduleOf(state: BackfillStateRow): IndexSchedule =
        IndexSchedule.entries.firstOrNull { it.name == state.schedule } ?: scheduler.schedule

    private fun reasonOf(state: BackfillStateRow): BackfillReason =
        BackfillReason.entries.firstOrNull { it.name == state.reason } ?: BackfillReason.INITIAL

    private fun toProgress(state: BackfillStateRow?, done: Int, running: Boolean): BackfillProgress {
        if (state == null) {
            return BackfillProgress(BackfillStage.NOT_STARTED, 0, 0, scheduler.schedule, BackfillReason.INITIAL, waiting = false)
        }
        val stage = BackfillStage.entries.firstOrNull { it.name == state.stage } ?: BackfillStage.NOT_STARTED
        return BackfillProgress(
            stage = stage,
            done = if (stage == BackfillStage.DONE) maxOf(done, state.total) else done,
            total = maxOf(state.total, done),
            schedule = scheduleOf(state),
            reason = reasonOf(state),
            waiting = stage == BackfillStage.STAGE2 && !running,
        )
    }

    internal companion object {
        const val STAGE1_WINDOW_MILLIS = 30L * 24 * 60 * 60_000L
        const val STAGE1_MIN_COUNT = 1000
        const val STAGE1_CHUNK = 200
        const val STAGE2_BATCH = 500
    }
}
