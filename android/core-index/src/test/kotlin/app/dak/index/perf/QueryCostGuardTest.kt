package app.dak.index.perf

import app.dak.core.model.SimInfo
import app.dak.index.InboxTab
import app.dak.index.db.IndexMigrations
import app.dak.index.repo.SearchRepository
import app.dak.index.sql.ConversationSqlBuilder
import app.dak.index.sql.SqlQuery
import app.dak.search.SuggestionEngine
import app.dak.telephony.SimRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the query-cost changes of docs/performance.md ("Ledger and inbox") that the equivalence tests cannot see:
 * the inbox list's filter-and-group step reads only the covering index, and suggestions can reuse sender names loaded
 * once per search session with the same result as loading them per call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueryCostGuardTest {

    private object NoSims : SimRepository {
        override val sims: StateFlow<List<SimInfo>> = MutableStateFlow(emptyList())
        override fun sim(subId: Int): SimInfo? = null
        override fun defaultSmsSubId(): Int = -1
        override fun isRoaming(subId: Int): Boolean = false
        override fun refresh() = Unit
    }

    private fun plan(h: IndexHarness, q: SqlQuery): String =
        h.sql.query("EXPLAIN QUERY PLAN ${q.sql}", q.args.toTypedArray()).use { c ->
            val detail = c.getColumnIndexOrThrow("detail")
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(detail)
            out.joinToString(" | ")
        }

    @Test
    fun theAllTabGroupsFromTheCoveringIndexAndReadsOnlyThePageRows(): Unit = runBlocking {
        val data = InboxCorpus.generate(rows = 500, conversations = 40, seed = 4L)
        IndexHarness().use { h ->
            data.messages.chunked(500).forEach { h.db.messageDao().insertIgnore(it) }
            for (tab in listOf(InboxTab.ALL, InboxTab.ARCHIVED, InboxTab.STARRED)) {
                val page = plan(h, ConversationSqlBuilder.page(tab, null, 30, 0))
                println("INBOX-PLAN (guard) $tab: $page")
                assertTrue(page.contains("COVERING INDEX ${IndexMigrations.INBOX_COVERING_INDEX}"), "$tab page: $page")
                assertTrue(page.contains("rowid=?"), "$tab page reads display columns by rowid: $page")
                val count = plan(h, ConversationSqlBuilder.count(tab, null))
                assertTrue(count.contains("COVERING INDEX"), "$tab count: $count")
            }
        }
    }

    @Test
    fun suggestionsWithSessionSenderNamesEqualPerCallLoading(): Unit = runBlocking {
        val data = InboxCorpus.generate(rows = 800, conversations = 60, seed = 5L)
        IndexHarness().use { h ->
            data.messages.chunked(500).forEach { h.db.messageDao().insertIgnore(it) }
            val repo = SearchRepository(h.db, NoSims, Optional.empty())
            repo.recordQuery("brand offer")
            val session = repo.senderNames()
            assertEquals(h.db.messageDao().recentSenderNames(200), session)
            assertTrue(session.isNotEmpty())
            val recent = h.db.savedSearchDao().recentQueries(20)
            for (prefix in listOf("", "b", "br", "Brand 1", "+9198", "vm-", "zz")) {
                val perCall = repo.suggestions(prefix)
                assertEquals(perCall, repo.suggestions(prefix, senders = session), "'$prefix'")
                assertEquals(SuggestionEngine({ recent }, { session }, { emptyList() }).suggest(prefix, 8), perCall, "'$prefix'")
            }
        }
    }
}
