package app.dak.index.perf

import app.dak.classify.scam.ScamLabels
import app.dak.core.model.Classification
import app.dak.core.model.DeliveryStatus
import app.dak.core.model.InstrumentType
import app.dak.core.model.MessageBox
import app.dak.core.model.TransactionDirection
import app.dak.index.BackfillReason
import app.dak.index.IndexSchedule
import app.dak.index.db.entity.AccountAliasRow
import app.dak.index.db.entity.AccountTypeOverrideRow
import app.dak.index.enrich.Enrichment
import app.dak.index.sync.IndexMaintenance
import app.dak.finance.ledger.AccountType
import app.dak.finance.money.Money
import app.dak.finance.passbook.AccountFacts
import app.dak.finance.passbook.AccountGroups
import app.dak.index.repo.AccountGroupItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins what the ledger tables (`ledger_account`, `ledger_entry`) contain, however the index was filled, so the ledger
 * can be recomputed less often (docs/performance.md, "Ledger and inbox") without changing a single value:
 *
 * - After a staged first-run backfill (stage 1, then stage-2 batches of 500), after one big ingest, and after
 *   message-by-message ingests, the ledger equals [ReferenceLedger], a from-scratch rebuild with the finance functions.
 *   This is the invariant that makes deferring the recompute to the end of a backfill safe.
 * - A backfill stopped mid-pass and resumed, and a re-index, end in the same reference ledger.
 * - A live (single) ingest after the backfill updates the ledger at once.
 * - Provider-only changes (read / seen, delivery reports) leave the ledger untouched; a changed date, a changed
 *   transaction, a new scam label and a deletion do change it, exactly as the reference says.
 * - User actions (statement day, manual type, merge / unmerge) give the reference ledger.
 *
 * Row-level `updatedAt` is ignored (a wall-clock stamp). The corpus and the enricher are scripted (see
 * [LedgerCorpus]), so the classifier and the transaction parser can change without touching these expectations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerEquivalenceTest {

    private val corpus = LedgerCorpus.generate(size = 3_000, seed = 42L, slotMillis = 3 * 60 * 60_000L)

    private suspend fun seedUserData(h: IndexHarness) {
        h.db.accountAliasDao().put(AccountAliasRow(LedgerCorpus.ALIAS_FROM, LedgerCorpus.ALIAS_TO, same = true, decidedAt = 1L))
        h.db.ledgerDao().putTypeOverride(AccountTypeOverrideRow(LedgerCorpus.OVERRIDE_ID, InstrumentType.CREDIT_CARD, 1L))
    }

    private fun assertLedger(expected: LedgerSnapshot, actual: LedgerSnapshot, what: String) {
        assertTrue(expected == actual, "$what: ${expected.diff(actual)}")
    }

    private suspend fun assertReference(h: IndexHarness, what: String, statementDays: Map<String, Int> = emptyMap()) {
        assertLedger(ReferenceLedger.compute(h, statementDays), h.ledgerSnapshot(), what)
    }

    @Test
    fun stagedInitialBackfillBuildsTheReferenceLedger(): Unit = runBlocking {
        IndexHarness().use { h ->
            seedUserData(h)
            h.load(corpus)
            assertEquals(IndexMaintenance.StageTwoOutcome.DONE, h.initialBackfill())
            assertTrue(h.reader.calls >= 4, "the stage-2 pass should take several batches, took ${h.reader.calls} calls")
            val actual = h.ledgerSnapshot()
            assertLedger(ReferenceLedger.compute(h), actual, "staged initial backfill")

            // Sanity of the corpus and the reference (independent of how the ledger is computed).
            assertTrue(actual.accounts.size >= 12, "accounts: ${actual.accounts.keys}")
            assertFalse(LedgerCorpus.ALIAS_FROM in actual.accounts, "a merged alias has no ledger of its own")
            assertTrue(LedgerCorpus.ALIAS_TO in actual.accounts)
            assertTrue(actual.entries.keys.any { it.first == LedgerCorpus.ALIAS_TO } && corpus.any { it.enrichment.transaction?.maskedNumber == "XX40065" })
            assertEquals(InstrumentType.CREDIT_CARD, actual.accounts.getValue(LedgerCorpus.OVERRIDE_ID).instrument)
            assertTrue(LedgerCorpus.AXIS_LINKED_BANK_ID in actual.accounts, "a bank account known only through its loan's SMS")
            val sbiBank = LedgerCorpus.SBI_BANK.id
            val sbiDebit = LedgerCorpus.SBI_DEBIT.id
            assertEquals(sbiBank, actual.accounts.getValue(sbiDebit).linkedAccountId)
            assertTrue(actual.entries.values.any { it.accountId == sbiBank && it.viaAccountId == sbiDebit }, "card spends post to the bank account")

            val excluded = corpus.filter { ScamLabels.excludedFromLedger(it.enrichment.classification.labels) }.map { it.message.key.toString() }.toSet()
            assertTrue(excluded.isNotEmpty())
            assertTrue(actual.entries.keys.none { it.second in excluded }, "likely fake credits never post")
            val dismissed = corpus.filter { ScamLabels.DISMISSED in it.enrichment.classification.labels }.map { it.message.key.toString() }
            assertTrue(dismissed.isNotEmpty() && dismissed.all { (LedgerCorpus.HDFC.id to it) in actual.entries }, "dismissed warnings post")

            val card = actual.entries.values.filter { it.accountId == LedgerCorpus.ICICI_CARD.id }
            assertTrue(card.any { it.originalCurrency == "USD" && it.settled && it.markupPercent != null }, "foreign spends are reconciled")
        }
    }

    @Test
    fun batchingAndOrderDoNotChangeTheLedger(): Unit = runBlocking {
        val staged = IndexHarness().use { h ->
            seedUserData(h)
            h.load(corpus)
            h.initialBackfill()
            h.ledgerSnapshot()
        }
        val oneShot = IndexHarness().use { h ->
            seedUserData(h)
            h.load(corpus)
            h.ingestor.ingest(corpus.map { it.message })
            val snapshot = h.ledgerSnapshot()
            h.ledger.recomputeAll()
            assertLedger(snapshot, h.ledgerSnapshot(), "recomputeAll after one big ingest changes nothing")
            snapshot
        }
        assertLedger(oneShot, staged, "staged backfill vs one ingest")

        // Smaller corpus, harder orders: one message at a time oldest first, and odd-sized batches newest first.
        val small = LedgerCorpus.generate(size = 700, seed = 7L, slotMillis = 6 * 60 * 60_000L)
        val single = IndexHarness().use { h ->
            seedUserData(h)
            h.load(small)
            for (item in small) h.ingestor.ingest(listOf(item.message))
            assertReference(h, "message by message, oldest first")
            h.ledgerSnapshot()
        }
        val odd = IndexHarness().use { h ->
            seedUserData(h)
            h.load(small)
            for (batch in small.asReversed().chunked(37)) h.ingestor.ingest(batch.map { it.message })
            assertReference(h, "batches of 37, newest first")
            h.ledgerSnapshot()
        }
        assertLedger(single, odd, "single vs odd batches")
    }

    @Test
    fun interruptedBackfillResumesToTheSameLedger(): Unit = runBlocking {
        IndexHarness().use { h ->
            seedUserData(h)
            h.load(corpus)
            val recent = h.runStageOne()
            h.markStageTwoPending(recent.minOf { it.dateMillis } + 1, BackfillReason.INITIAL)
            h.reader.failOnCall = 3
            var stopped = false
            try {
                h.maintenance.runStageTwo(IndexSchedule.NOW) { _, _ -> }
            } catch (e: IllegalStateException) {
                stopped = true
            }
            assertTrue(stopped, "the simulated stop must interrupt the pass")
            h.reader.failOnCall = -1
            assertEquals(IndexMaintenance.StageTwoOutcome.DONE, h.maintenance.runStageTwo(IndexSchedule.NOW) { _, _ -> })
            assertReference(h, "stopped after two batches, then resumed")
        }
    }

    @Test
    fun reindexPassBuildsTheReferenceLedger(): Unit = runBlocking {
        IndexHarness().use { h ->
            seedUserData(h)
            h.load(corpus)
            h.initialBackfill()
            val before = h.ledgerSnapshot()
            // A new enricher version: some amounts change, some transactions move to another account, some credits are
            // now flagged as likely fakes, some messages are no longer transactions.
            val rnd = Random(3)
            h.enricher.currentVersion = 2
            for (item in corpus) {
                val t = item.enrichment.transaction ?: continue
                val c = item.enrichment.classification
                val changed = when (rnd.nextInt(100)) {
                    in 0..14 -> Enrichment(c, t.copy(amountMinor = t.amountMinor * 2 + 1))
                    in 15..17 -> Enrichment(c, t.copy(maskedNumber = "XXXX9999", last4 = "9999"))
                    in 18..20 -> if (t.direction == TransactionDirection.CREDIT) Enrichment(c.copy(labels = setOf(ScamLabels.LIKELY)), t) else null
                    in 21..22 -> Enrichment(Classification(c.category, c.confidence, c.source, canonicalSender = c.canonicalSender), null)
                    else -> null
                } ?: continue
                h.enricher.put(item.message.key, changed)
            }
            h.markStageTwoPending(Long.MAX_VALUE, BackfillReason.REINDEX)
            assertEquals(IndexMaintenance.StageTwoOutcome.DONE, h.maintenance.runStageTwo(IndexSchedule.NOW) { _, _ -> })
            assertReference(h, "after a re-index")
            assertNotEquals(before, h.ledgerSnapshot(), "the re-index must have changed the ledger")
        }
    }

    @Test
    fun liveIngestAfterTheBackfillUpdatesTheLedgerAtOnce(): Unit = runBlocking {
        IndexHarness().use { h ->
            seedUserData(h)
            h.load(corpus)
            h.initialBackfill()
            val newest = corpus.last().message
            var id = newest.providerId + 1
            val date = newest.dateMillis + 60_000L
            val live = listOf(
                LedgerCorpus.single(LedgerCorpus.HDFC, id++, date, 12_345L, TransactionDirection.DEBIT, balance = 777_777L),
                LedgerCorpus.single(LedgerCorpus.SBI_DEBIT, id++, date + 60_000L, 54_321L, TransactionDirection.DEBIT, balance = 999_999L, linked = "XXXX5555"),
                LedgerCorpus.single(LedgerCorpus.AXIS_LOAN, id++, date + 120_000L, 1_000_000L, TransactionDirection.DEBIT, balance = null, linked = "XXXX8888"),
                LedgerCorpus.single(LedgerCorpus.HDFC_WIDE_ALIAS, id, date + 180_000L, 4_200L, TransactionDirection.CREDIT, balance = 31_337L),
            )
            for (item in live) {
                h.enricher.put(item.message.key, item.enrichment)
                h.ingestor.ingest(listOf(item.message), allowCloud = true)
                assertReference(h, "right after the live ingest of ${item.message.key}")
            }
            val snapshot = h.ledgerSnapshot()
            assertEquals(777_777L, snapshot.accounts.getValue(LedgerCorpus.HDFC.id).balanceMinor)
            assertEquals(999_999L, snapshot.accounts.getValue(LedgerCorpus.SBI_BANK.id).balanceMinor, "a linked card spend moves the bank balance")
            assertEquals(31_337L, snapshot.accounts.getValue(LedgerCorpus.ALIAS_TO).balanceMinor, "an alias's SMS moves the merged account")
        }
    }

    @Test
    fun providerOnlyChangesLeaveTheLedgerAloneAndRealChangesFollowTheReference(): Unit = runBlocking {
        IndexHarness().use { h ->
            seedUserData(h)
            h.load(corpus)
            h.initialBackfill()
            val before = h.ledgerSnapshot()
            h.takeCost()

            // Read / seen and delivery-report changes (what the reconcile and "mark read" produce).
            val refreshed = corpus.map { item ->
                val m = item.message
                if (m.box == MessageBox.SENT) m.copy(read = true, seen = true, deliveryStatus = DeliveryStatus.fromCode(0), deliveredAtMillis = m.dateMillis + 5_000L)
                else m.copy(read = !m.read, seen = true)
            }
            h.ingestor.ingest(refreshed)
            val readCost = h.takeCost()
            println("LEDGER-COST read/seen-only re-ingest of ${refreshed.size} messages: $readCost")
            assertLedger(before, h.ledgerSnapshot(), "read/seen/delivery changes")

            // A provider date change of transaction messages moves their entries (and may change balances).
            val txnItems = corpus.filter { it.enrichment.transaction != null }
            val redated = txnItems.filterIndexed { i, _ -> i % 97 == 5 }.map { it.message.copy(dateMillis = it.message.dateMillis + 7) }
            assertTrue(redated.size >= 5)
            h.ingestor.ingest(redated)
            assertReference(h, "after a date change")
            assertNotEquals(before, h.ledgerSnapshot())

            // Deleted messages leave the ledger.
            val gone = txnItems.filterIndexed { i, _ -> i % 89 == 3 }.map { it.message.key }
            h.ingestor.remove(gone)
            assertReference(h, "after deletions")
            assertTrue(h.ledgerSnapshot().entries.keys.none { key -> gone.any { it.toString() == key.second } })

            // A forced re-enrichment (e.g. a scam verdict dismissed) changes labels only.
            val flagged = txnItems.filter { it.enrichment.transaction?.direction == TransactionDirection.CREDIT }.take(4)
            for (item in flagged) {
                val e = item.enrichment
                h.enricher.put(item.message.key, e.copy(classification = e.classification.copy(labels = setOf(ScamLabels.LIKELY))))
            }
            h.ingestor.ingest(flagged.map { it.message }, force = true)
            assertReference(h, "after new scam labels")
        }
    }

    @Test
    fun userActionsGiveTheReferenceLedger(): Unit = runBlocking {
        IndexHarness().use { h ->
            seedUserData(h)
            h.load(corpus)
            h.initialBackfill()
            val card = LedgerCorpus.ICICI_CARD.id
            h.ledger.setStatementDay(card, 15)
            val days = mapOf(card to 15)
            assertReference(h, "statement day set", days)

            // A later spend on the card keeps the statement day.
            val newest = corpus.last().message
            val spend = LedgerCorpus.single(LedgerCorpus.ICICI_CARD, newest.providerId + 1, newest.dateMillis + 60_000L, 250_000L, TransactionDirection.DEBIT, null)
            h.enricher.put(spend.message.key, spend.enrichment)
            h.ingestor.ingest(listOf(spend.message))
            assertEquals(15, h.ledgerSnapshot().accounts.getValue(card).statementDay)
            assertReference(h, "statement day kept across a recompute", days)

            h.ledger.setAccountType(LedgerCorpus.HDFC_UPI.id, InstrumentType.WALLET)
            assertReference(h, "manual type", days)
            h.ledger.setAccountType(LedgerCorpus.HDFC_UPI.id, null)
            assertReference(h, "manual type cleared", days)

            h.ledger.mergeAccounts(LedgerCorpus.HDFC_UPI.id, LedgerCorpus.HDFC.id)
            assertReference(h, "merged", days)
            assertTrue(h.ledger.unmergeAccount(LedgerCorpus.HDFC_UPI.id))
            assertReference(h, "unmerged", days)
        }
    }

    /**
     * The Passbook's sections (`LedgerRepository.accountGroups`) equal what the per-account reads give: each card's
     * cycle outstanding as `cardOutstanding` computes it, each account's spend this month from its entries, the linked
     * account, hidden accounts left out. Pins the per-card entry reads the sections make, so they can be batched.
     */
    @Test
    fun passbookSectionsEqualThePerAccountReads(): Unit = runBlocking {
        IndexHarness().use { h ->
            seedUserData(h)
            h.load(corpus)
            h.initialBackfill()
            h.ledger.setStatementDay(LedgerCorpus.ICICI_CARD.id, 15)
            h.ledger.setStatementDay(LedgerCorpus.OVERRIDE_ID, 5)
            h.ledger.setStatementDay(LedgerCorpus.CHASE.id, 28)
            h.ledger.setAccountHidden(LedgerCorpus.PAYTM.id, true)
            val now = corpus.last().message.dateMillis + DAY_MILLIS
            val monthStart = AccountGroups.monthStartUtc(now)

            val snapshot = h.ledgerSnapshot()
            val summaries = h.ledger.accounts().first().filterNot { it.account.id == LedgerCorpus.PAYTM.id }
            val byId = summaries.associateBy { it.account.id }
            val items = summaries.map { s ->
                val day = s.account.statementDay
                val outstanding = if (s.account.type == AccountType.CREDIT_CARD && day != null) h.ledger.cardOutstanding(s.account.id, now).first() else null
                val spend = AccountGroups.sum(
                    snapshot.entries.values
                        .filter { it.accountId == s.account.id && it.direction == TransactionDirection.DEBIT && !it.transfer && it.dateMillis >= monthStart }
                        .map { Money(it.originalMinor, it.originalCurrency) },
                )
                AccountGroupItem(s, spend, outstanding, s.account.linkedAccountId?.let { byId[it] })
            }
            val expected = AccountGroups.group(
                items,
                facts = { AccountFacts(it.summary.account, it.summary.balance, it.spentThisMonth, it.outstanding) },
                lastActivity = { it.summary.lastActivityMillis },
            )
            val actual = h.ledger.accountGroups(now).first()
            assertEquals(expected, actual)
            val cards = actual.flatMap { it.items }.filter { it.summary.account.type == AccountType.CREDIT_CARD }
            assertTrue(cards.count { it.outstanding != null } >= 3, "three cards with a statement day: $cards")
            assertTrue(actual.flatMap { it.items }.any { it.linked != null } && actual.flatMap { it.items }.any { it.spentThisMonth.isNotEmpty() })
        }
    }
}
