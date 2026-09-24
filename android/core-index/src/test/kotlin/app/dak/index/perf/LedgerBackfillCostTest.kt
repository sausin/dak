package app.dak.index.perf

import app.dak.index.BackfillReason
import app.dak.index.IndexSchedule
import app.dak.index.sync.IndexMaintenance
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much ledger work a first-run backfill causes (docs/performance.md, "Ledger and inbox"), counted with SQLite
 * triggers on the ledger tables (see [IndexHarness.takeCost]), so no production hook is needed:
 *
 * - account rebuilds: `ledger_account` rows written, one per recompute pass of an account;
 * - rows read: the index rows each pass had to load (the account's own, merged and linked-card rows);
 * - entry writes / deletes: `ledger_entry` rows inserted / deleted.
 *
 * Numbers are printed per stage-2 batch and in total (search the test output for `LEDGER-COST`), next to the cost of
 * one full `recomputeAll()` over the finished index, the minimum a backfill needs. Size: `-Ddak.bench.size=20000`
 * (default 5,000 messages, two hours apart, 40% of them transactions over 13 accounts).
 *
 * The assertions bound what the ledger may cost; they are the regression guard for the deferred recompute.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerBackfillCostTest {

    private val size = System.getProperty("dak.bench.size")?.toIntOrNull() ?: 5_000

    @Test
    fun stagedInitialBackfillLedgerCost(): Unit = runBlocking {
        val corpus = LedgerCorpus.generate(size = size, seed = 11L, slotMillis = 2 * 60 * 60_000L)
        IndexHarness().use { h ->
            h.load(corpus)
            val t0 = System.nanoTime()
            val recent = h.runStageOne()
            val stageOne = h.takeCost()
            val t1 = System.nanoTime()
            h.markStageTwoPending(recent.minOf { it.dateMillis } + 1, BackfillReason.INITIAL)
            val perCall = ArrayList<LedgerCost>()
            h.reader.beforeBatch = { perCall += h.takeCost() }
            val outcome = h.maintenance.runStageTwo(IndexSchedule.NOW) { _, _ -> }
            perCall += h.takeCost() // work after the last provider call: what the pass does once it is DONE
            val t2 = System.nanoTime()
            h.reader.beforeBatch = null
            assertEquals(IndexMaintenance.StageTwoOutcome.DONE, outcome)

            // perCall[0] is the work before the first batch (none); perCall[k] is batch k; the last is the DONE step.
            val batches = perCall.drop(1).dropLast(1)
            val done = perCall.last()
            val stageTwo = perCall.fold(LedgerCost.ZERO) { a, b -> a + b }
            val snapshot = h.ledgerSnapshot()
            val accounts = snapshot.accounts.size

            val t3 = System.nanoTime()
            h.ledger.recomputeAll()
            val full = h.takeCost()
            val t4 = System.nanoTime()
            assertLedgerUnchanged(snapshot, h.ledgerSnapshot())

            val report = buildString {
                appendLine("LEDGER-COST corpus=${corpus.size} msgs, transactions=${corpus.count { it.enrichment.transaction != null }}, accounts=$accounts")
                appendLine("LEDGER-COST stage 1 (${recent.size} msgs, chunks of 200): $stageOne, ${(t1 - t0) / 1_000_000} ms")
                batches.forEachIndexed { i, c -> appendLine("LEDGER-COST stage 2 batch ${i + 1}: $c") }
                appendLine("LEDGER-COST stage 2 DONE step: $done")
                appendLine("LEDGER-COST stage 2 total (${batches.size} batches): $stageTwo, ${(t2 - t1) / 1_000_000} ms including ingest")
                appendLine("LEDGER-COST one recomputeAll() over the finished index (the minimum): $full, ${(t4 - t3) / 1_000_000} ms")
                append(
                    "LEDGER-COST stage 2 / minimum: rebuilds x%.1f, rows read x%.1f, entry writes x%.1f".format(
                        stageTwo.accountRebuilds.toDouble() / full.accountRebuilds.coerceAtLeast(1),
                        stageTwo.rowsRead.toDouble() / full.rowsRead.coerceAtLeast(1),
                        stageTwo.entryWrites.toDouble() / full.entryWrites.coerceAtLeast(1),
                    ),
                )
            }
            println(report)

            // The counters must see the work, or the numbers above mean nothing. Entries are written while the backfill
            // builds the ledger; the full rebuild afterwards writes none, because rebuilds only write entries that
            // changed (LedgerRepository) and the ledger is already complete.
            assertTrue(full.accountRebuilds >= accounts && full.rowsRead > 0, "counters: $full\n$report")
            assertTrue(stageOne.entryWrites + stageTwo.entryWrites > 0, "the backfill wrote no ledger entries\n$report")
            assertEquals(0, full.entryWrites, "a rebuild of a complete ledger rewrote entries\n$report")
            assertTrue(batches.size >= 5, "the stage-2 pass should take several batches: ${batches.size}\n$report")
            assertTrue(stageOne.accountRebuilds + stageTwo.accountRebuilds > 0, report)

            // The deferred rebuild (IndexMaintenance.LEDGER_FLUSH_BATCHES): batches rebuild nothing except every 20th,
            // and the whole pass costs at most one full rebuild per 20 batches plus the one at the end.
            val flushes = batches.size / 20
            batches.forEachIndexed { i, c ->
                if ((i + 1) % 20 != 0) assertEquals(0, c.accountRebuilds, "batch ${i + 1} rebuilt accounts: $c\n$report")
            }
            assertTrue(done.accountRebuilds >= accounts, "the end of the pass rebuilds every account: $done\n$report")
            assertTrue(stageTwo.accountRebuilds <= full.accountRebuilds * (1 + flushes), "rebuilds: $stageTwo vs $full\n$report")
            assertTrue(stageTwo.rowsRead <= full.rowsRead * (1 + flushes), "rows read: $stageTwo vs $full\n$report")
        }
    }

    /** A read / seen-only re-ingest (what the reconcile does after "mark all read") and what the ledger does with it. */
    @Test
    fun readOnlyReingestLedgerCost(): Unit = runBlocking {
        val corpus = LedgerCorpus.generate(size = 2_000, seed = 12L, slotMillis = 2 * 60 * 60_000L)
        IndexHarness().use { h ->
            h.load(corpus)
            h.ingestor.ingest(corpus.map { it.message })
            val before = h.ledgerSnapshot()
            h.takeCost()
            val t0 = System.nanoTime()
            h.ingestor.ingest(corpus.map { it.message.copy(read = true, seen = true) })
            val cost = h.takeCost()
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("LEDGER-COST read/seen-only re-ingest of ${corpus.size} msgs: $cost, $ms ms")
            assertLedgerUnchanged(before, h.ledgerSnapshot())
            assertEquals(LedgerCost.ZERO, cost, "a read / seen change touches no ledger input")
        }
    }

    private fun assertLedgerUnchanged(expected: LedgerSnapshot, actual: LedgerSnapshot) {
        assertTrue(expected == actual, expected.diff(actual))
    }
}
