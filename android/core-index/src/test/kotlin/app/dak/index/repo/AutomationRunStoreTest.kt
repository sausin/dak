package app.dak.index.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.AutomationRunRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Retention of the automation run log: rows are kept for [AutomationRunStore.RETENTION_MILLIS] and, beyond that, the
 * newest [AutomationRunStore.RETENTION_MIN_ROWS] are kept anyway. Run against Room's real SQL (the trim is a query).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationRunStoreTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DakIndexDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val store = AutomationRunStore(db)
    private val dao = db.automationRunDao()

    @After
    fun close() = db.close()

    private fun run(atMillis: Long, ruleId: String = "r1", outcome: String = "SENT") = AutomationRunRow(
        ruleId = ruleId, ruleName = "Rule", atMillis = atMillis, messageKey = "sms:$atMillis", conversationId = "t:1",
        sourceLabel = "VM-HDFCBK", actionKind = "ForwardSms", destinationLabel = null, destination = "+15551234567",
        outcome = outcome, reason = null, textPreview = "hi",
    )

    private suspend fun remainingTimes(): List<Long> = store.all().first().map { it.atMillis }

    @Test
    fun rowsInsideTheRetentionPeriodAreNeverTrimmed() = runTest {
        val now = 10 * AutomationRunStore.RETENTION_MILLIS
        store.add(run(now - AutomationRunStore.RETENTION_MILLIS)) // exactly one retention period old: kept
        store.add(run(now - 1))
        store.add(run(now))
        assertEquals(0, store.trim(nowMillis = now))
        assertEquals(3, remainingTimes().size)
    }

    @Test
    fun oldRowsGoOnlyBeyondTheNewestKeptRows() = runTest {
        for (t in 1L..5L) dao.insert(run(atMillis = t))
        // Rows older than 4, except the newest 2 overall (5 and 4): 1, 2, 3 go.
        assertEquals(3, dao.trim(beforeMillis = 4, keepNewest = 2))
        assertEquals(listOf(5L, 4L), remainingTimes())
    }

    @Test
    fun theNewestRowsAreKeptHoweverOldTheyAre() = runTest {
        for (t in 1L..5L) dao.insert(run(atMillis = t))
        // Every row is past the cutoff, but the newest 4 stay.
        assertEquals(1, dao.trim(beforeMillis = 100, keepNewest = 4))
        assertEquals(listOf(5L, 4L, 3L, 2L), remainingTimes())
    }

    @Test
    fun tiesAtTheSameInstantKeepTheLaterInsert() = runTest {
        val first = dao.insert(run(atMillis = 1))
        val second = dao.insert(run(atMillis = 1))
        assertEquals(1, dao.trim(beforeMillis = 100, keepNewest = 1))
        assertEquals(listOf(second), store.all().first().map { it.id })
        assertEquals(true, first < second)
    }

    @Test
    fun storeTrimUsesTheOneYearCutoffAndTheMinimumRowCount() = runTest {
        val now = 10 * AutomationRunStore.RETENTION_MILLIS
        store.add(run(now - AutomationRunStore.RETENTION_MILLIS - 1))
        store.add(run(now))
        // Only two rows exist, far below the minimum kept count: nothing goes even though one is past a year.
        assertEquals(0, store.trim(nowMillis = now))
        assertEquals(2, remainingTimes().size)
    }

    @Test
    fun countAndPerRuleHistoryAreScopedToTheRule() = runTest {
        store.add(run(10, ruleId = "a", outcome = "SENT"))
        store.add(run(20, ruleId = "a", outcome = "FAILED"))
        store.add(run(30, ruleId = "a", outcome = "SENT"))
        store.add(run(40, ruleId = "b", outcome = "SENT"))
        assertEquals(2, store.count("a", "SENT", sinceMillis = 0))
        assertEquals(1, store.count("a", "SENT", sinceMillis = 11))
        assertEquals(0, store.count("c", "SENT", sinceMillis = 0))
        assertEquals(listOf(30L, 20L, 10L), store.forRule("a").first().map { it.atMillis })
        assertEquals(listOf(30L), store.forRule("a", limit = 1).first().map { it.atMillis })
    }
}
