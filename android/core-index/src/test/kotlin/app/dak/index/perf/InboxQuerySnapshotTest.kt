package app.dak.index.perf

import androidx.sqlite.db.SimpleSQLiteQuery
import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.index.InboxTab
import app.dak.index.db.dao.ConversationRow
import app.dak.index.db.entity.ConversationPrefs
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.SenderMergeGroup
import app.dak.index.sql.ConversationSqlBuilder
import app.dak.index.sql.SqlQuery
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the inbox conversation list (`ConversationSqlBuilder.page` / `count` as `ConversationRepository`'s paging source
 * runs them) row for row, so the query can be made cheaper (a covering index, a two-step query; docs/performance.md,
 * "Ledger and inbox") without changing what any tab shows:
 *
 * - every page (30 rows, and 7 for the All tab) of every [InboxTab], without a SIM filter, for each SIM, and for a SIM
 *   with no messages, equals an independent Kotlin evaluation of the list's rules ([expected]): filter per message,
 *   group by conversation, bare columns from the conversation's newest matching message, counts and SIM / thread sets
 *   over the matching messages, prefs and merge-group name joined, pinned first, then newest, then id;
 * - the counts equal the reference;
 * - with a date tie for the newest message, the conversation shows one of the tied messages, all its columns from that
 *   same message (SQLite's own choice among them is not pinned).
 *
 * The query plans are printed (search the output for `INBOX-PLAN`), and the timing test prints page and count times
 * on 20,000 rows (`INBOX-BENCH`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxQuerySnapshotTest {

    /** A conversation row as the UI consumes it (SIM and thread lists are sets there, see `Mappers.parseInts`). */
    data class ConvView(
        val conversationId: String,
        val dateMillis: Long,
        val kind: MessageKind,
        val providerId: Long,
        val threadId: Long,
        val address: String,
        val mergeKey: String,
        val canonicalSender: String?,
        val snippet: String,
        val category: Category,
        val box: MessageBox,
        val hasAttachment: Boolean,
        val unreadCount: Int,
        val messageCount: Int,
        val subIds: Set<Int>,
        val threadIds: Set<Long>,
        val pinned: Boolean,
        val muted: Boolean,
        val archived: Boolean,
        val starred: Boolean,
        val incognito: Boolean,
        val groupName: String?,
        val repeatGroup: String?,
        val deliveryStatus: Int,
    )

    private fun view(r: ConversationRow) = ConvView(
        r.conversationId, r.dateMillis, r.kind, r.providerId, r.threadId, r.address, r.mergeKey, r.canonicalSender, r.snippet,
        r.category, r.box, r.hasAttachment, r.unreadCount, r.messageCount,
        r.subIds?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty(),
        r.threadIds?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty(),
        r.pinned, r.muted, r.archived, r.starred, r.incognito, r.groupName, r.repeatGroup, r.deliveryStatus,
    )

    /** The conversation list's rules, evaluated in Kotlin over [data]. */
    private fun expected(data: InboxData, tab: InboxTab, subId: Int?): List<ConvView> {
        val prefs = data.prefs.associateBy { it.conversationId }
        val groups = data.groups.associateBy { it.mergeKey }
        fun matches(m: IndexedMessage): Boolean {
            val p = prefs[m.conversationId]
            val notArchived = !(p?.archived ?: false) && !m.archived
            val tabOk = when (tab) {
                InboxTab.ALL -> notArchived && m.category != Category.SPAM
                InboxTab.PERSONAL -> notArchived && m.category == Category.PERSONAL
                InboxTab.TRANSACTION -> notArchived && m.category == Category.TRANSACTION
                InboxTab.OTP -> notArchived && m.category == Category.OTP
                InboxTab.PROMOTION -> notArchived && m.category == Category.PROMOTION
                InboxTab.SPAM -> m.category == Category.SPAM
                InboxTab.ARCHIVED -> (p?.archived ?: false) || m.archived
                InboxTab.STARRED -> (p?.starred ?: false) || m.starred
            }
            return tabOk && (subId == null || m.subId == subId)
        }
        return data.messages.filter(::matches).groupBy { it.conversationId }.map { (id, rows) ->
            val newest = rows.maxByOrNull { it.dateMillis }!!
            val p: ConversationPrefs? = prefs[id]
            ConvView(
                conversationId = id,
                dateMillis = newest.dateMillis,
                kind = newest.kind,
                providerId = newest.providerId,
                threadId = newest.threadId,
                address = newest.address,
                mergeKey = newest.mergeKey,
                canonicalSender = newest.canonicalSender,
                snippet = newest.bodyPreview,
                category = newest.category,
                box = newest.box,
                hasAttachment = newest.hasAttachment,
                unreadCount = rows.count { !it.read && it.box == MessageBox.INBOX },
                messageCount = rows.size,
                subIds = rows.mapTo(HashSet()) { it.subId },
                threadIds = rows.mapTo(HashSet()) { it.threadId },
                pinned = p?.pinned ?: false,
                muted = p?.muted ?: false,
                archived = p?.archived ?: false,
                starred = p?.starred ?: false,
                incognito = p?.incognitoSince != null,
                groupName = if (newest.conversationId == "m:" + newest.mergeKey) groups[newest.mergeKey]?.displayName else null,
                repeatGroup = newest.repeatGroup,
                deliveryStatus = newest.deliveryStatus,
            )
        }.sortedWith(compareByDescending<ConvView> { it.pinned }.thenByDescending { it.dateMillis }.thenBy { it.conversationId })
    }

    private suspend fun load(h: IndexHarness, data: InboxData) {
        data.messages.chunked(500).forEach { h.db.messageDao().insertIgnore(it) }
        data.prefs.forEach { h.db.conversationPrefsDao().put(it) }
        data.groups.forEach { h.db.senderMergeDao().putGroup(it) }
    }

    private suspend fun page(h: IndexHarness, tab: InboxTab, subId: Int?, limit: Int, offset: Int): List<ConvView> =
        h.db.rawQueryDao().conversations(ConversationSqlBuilder.page(tab, subId, limit, offset).toSupport()).map(::view)

    private suspend fun count(h: IndexHarness, tab: InboxTab, subId: Int?): Int =
        h.db.rawQueryDao().count(ConversationSqlBuilder.count(tab, subId).toSupport())?.n ?: 0

    private fun SqlQuery.toSupport() = SimpleSQLiteQuery(sql, args.toTypedArray())

    private fun plan(h: IndexHarness, q: SqlQuery): List<String> =
        h.sql.query("EXPLAIN QUERY PLAN ${q.sql}", q.args.toTypedArray()).use { c ->
            val detail = c.getColumnIndexOrThrow("detail")
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(detail)
            out
        }

    @Test
    fun everyPageOfEveryTabMatchesTheReference(): Unit = runBlocking {
        val data = InboxCorpus.generate(rows = 3_000, conversations = 180, seed = 1L)
        IndexHarness().use { h ->
            load(h, data)
            var nonEmpty = 0
            for (tab in InboxTab.entries) {
                for (subId in listOf(null, 1, 2, 7)) {
                    val all = expected(data, tab, subId)
                    if (all.isNotEmpty()) nonEmpty++
                    assertEquals(all.size, count(h, tab, subId), "count $tab sim $subId")
                    val sizes = if (tab == InboxTab.ALL && subId == null) listOf(30, 7) else listOf(30)
                    for (size in sizes) {
                        var offset = 0
                        while (offset <= all.size) {
                            val want = all.subList(offset, minOf(all.size, offset + size))
                            assertEquals(want, page(h, tab, subId, size, offset), "$tab sim $subId page size $size offset $offset")
                            offset += size
                        }
                    }
                }
            }
            assertTrue(nonEmpty >= 20, "the corpus should fill most tab / SIM combinations: $nonEmpty")
            // The corpus covers what the list distinguishes.
            val allTab = expected(data, InboxTab.ALL, null)
            assertTrue(allTab.any { it.pinned } && allTab.any { it.muted } && allTab.any { it.incognito } && allTab.any { it.groupName != null })
            assertTrue(allTab.any { it.subIds.size == 2 } && allTab.any { it.threadIds.size == 2 } && allTab.any { it.kind == MessageKind.MMS })
            assertTrue(expected(data, InboxTab.ARCHIVED, null).isNotEmpty() && expected(data, InboxTab.STARRED, null).isNotEmpty())

            for (tab in listOf(InboxTab.ALL, InboxTab.TRANSACTION, InboxTab.SPAM, InboxTab.ARCHIVED, InboxTab.STARRED)) {
                for (subId in listOf(null, 1)) {
                    println("INBOX-PLAN page $tab sim $subId: " + plan(h, ConversationSqlBuilder.page(tab, subId, 30, 0)).joinToString(" | "))
                }
                println("INBOX-PLAN count $tab: " + plan(h, ConversationSqlBuilder.count(tab, null)).joinToString(" | "))
            }
        }
    }

    @Test
    fun aDateTieForTheNewestMessageShowsOneOfTheTiedMessagesConsistently(): Unit = runBlocking {
        val t = START_MILLIS
        fun row(id: Long, date: Long, body: String, sub: Int, read: Boolean) = indexedRow(
            providerId = id, threadId = 9L, address = "VM-TIE", mergeKey = "TIE", conversationId = "m:TIE", dateMillis = date,
            body = body, subId = sub, read = read, category = Category.TRANSACTION, canonicalSender = "Tie",
        )
        val rows = listOf(row(1, t, "older", 1, true), row(2, t + 5, "tied one", 2, false), row(3, t + 5, "tied two", 1, false), row(4, t + 1, "middle", 1, true))
        IndexHarness().use { h ->
            load(h, InboxData(rows, emptyList(), listOf(SenderMergeGroup("TIE", "Tie group", false, null, false, 0L))))
            for (tab in listOf(InboxTab.ALL, InboxTab.TRANSACTION)) {
                val got = page(h, tab, null, 30, 0).single()
                val chosen = rows.single { it.providerId == got.providerId }
                assertTrue(chosen.providerId in setOf(2L, 3L), "one of the tied newest messages: ${got.providerId}")
                assertEquals(chosen.bodyPreview, got.snippet, "every bare column comes from the chosen message")
                assertEquals(t + 5, got.dateMillis)
                assertEquals(4, got.messageCount)
                assertEquals(2, got.unreadCount)
                assertEquals(setOf(1, 2), got.subIds)
                assertEquals("Tie group", got.groupName)
                println("INBOX-TIE $tab chose ${got.providerId}")
            }
        }
    }

    @Test
    fun timingOnTwentyThousandRows(): Unit = runBlocking {
        val data = InboxCorpus.generate(rows = 20_000, conversations = 800, seed = 2L)
        IndexHarness().use { h ->
            load(h, data)
            fun median(block: () -> Unit): Double {
                block() // warm-up
                val times = (1..5).map { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6 }
                return times.sorted()[2]
            }
            val lines = ArrayList<String>()
            for (tab in listOf(InboxTab.ALL, InboxTab.PERSONAL, InboxTab.TRANSACTION, InboxTab.ARCHIVED)) {
                for (subId in listOf(null, 1)) {
                    val first = median { runBlocking { page(h, tab, subId, 30, 0) } }
                    val deep = median { runBlocking { page(h, tab, subId, 30, 300) } }
                    val n = median { runBlocking { count(h, tab, subId) } }
                    lines += "INBOX-BENCH rows=${data.messages.size} $tab sim=$subId first page %.1f ms, page at 300 %.1f ms, count %.1f ms".format(first, deep, n)
                }
            }
            println(lines.joinToString("\n"))
            // Still correct at this size (first pages only; the snapshot test covers every page).
            for (tab in listOf(InboxTab.ALL, InboxTab.ARCHIVED)) {
                assertEquals(expected(data, tab, null).take(30), page(h, tab, null, 30, 0), "$tab first page at 20k rows")
            }
        }
    }
}
