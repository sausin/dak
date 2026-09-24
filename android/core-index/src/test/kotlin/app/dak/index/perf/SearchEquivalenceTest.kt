package app.dak.index.perf

import androidx.sqlite.db.SimpleSQLiteQuery
import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.core.model.SimInfo
import app.dak.index.SearchSort
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.SenderMergeGroup
import app.dak.index.repo.SearchRepository
import app.dak.index.sql.SearchSqlBuilder
import app.dak.index.sql.SqlQuery
import app.dak.index.text.Highlighter
import app.dak.search.Filter
import app.dak.search.SearchQuery
import app.dak.search.SuggestionEngine
import app.dak.search.TextExpr
import app.dak.telephony.SimRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Optional
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins full-text search and sender suggestions, so the FTS table can get a prefix index (`@Fts4(prefix = ...)`, a
 * rebuild migration) and sender names can be loaded once per search session (docs/performance.md, "Ledger and inbox")
 * without changing a result:
 *
 * - raw `MATCH 'xy*'` prefix queries of 2, 3, 5 and more letters, exact one-letter tokens and phrases return exactly
 *   the rows whose FTS text (`searchText` / `searchSender`, as stored) has a matching token;
 * - grouped searches (`SearchSqlBuilder.messages`, as the search paging source runs them) with prefixes, AND, OR,
 *   NOT, phrases and `from:` / `category:` / `is:unread` / `has:otp` filters return, page by page and in both RECENT
 *   and RELEVANCE order, the conversations, chosen messages, match counts and sort values of a Kotlin evaluation;
 * - `MessageDao.recentSenderNames` and `SearchRepository.suggestions` equal their reference.
 *
 * The corpus is ASCII so FTS4's unicode61 tokenizer and the reference tokenizer agree by construction. Timing of
 * prefix searches on 20,000 rows is printed (`SEARCH-BENCH`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchEquivalenceTest {

    private object NoSims : SimRepository {
        override val sims: StateFlow<List<SimInfo>> = MutableStateFlow(emptyList())
        override fun sim(subId: Int): SimInfo? = null
        override fun defaultSmsSubId(): Int = -1
        override fun isRoaming(subId: Int): Boolean = false
        override fun refresh() = Unit
    }

    private val vocabulary = listOf(
        "pay", "paid", "payment", "payments", "paytm", "parcel", "pan", "packed", "order", "ordered", "orders", "otp",
        "one", "online", "hdfc", "hdfcbank", "hello", "help", "delivered", "delivery", "debited", "credited", "credit",
        "card", "cash", "upi", "amazon", "amzn", "offer", "off", "refund", "reward", "ref", "a", "statement", "shipped",
    )
    /** (address, merge key, brand): four merge groups (Amazon on two headers) and 30 one-to-one threads. */
    private val senders: List<Triple<String, String, String?>> = listOf(
        Triple("VM-AMAZON", "AMAZON", "Amazon"),
        Triple("JD-AMAZON", "AMAZON", "Amazon"),
        Triple("AD-HDFCBK", "HDFCBK", "HDFC Bank"),
        Triple("JD-PAYTMB", "PAYTMB", "Paytm"),
        Triple("VK-FLPKRT", "FLPKRT", null),
        Triple("+14155550123", "+14155550123", null),
    ) + (1..29).map { n -> "+9198765%05d".format(n).let { Triple(it, it, null) } }

    private fun corpus(rows: Int, seed: Long): List<IndexedMessage> {
        val rnd = Random(seed)
        val dates = (0 until rows).map { START_MILLIS + it * 61_000L + rnd.nextLong(60_000L) }.shuffled(rnd)
        return (0 until rows).map { i ->
            val (address, mergeKey, canonical) = senders[rnd.nextInt(senders.size)]
            val phone = address.startsWith("+")
            val conversationId = if (phone) "t:${7_000 + senders.indexOfFirst { it.first == address }}" else "m:$mergeKey"
            val words = List(4 + rnd.nextInt(8)) { vocabulary[rnd.nextInt(vocabulary.size)] }.toMutableList()
            if (rnd.nextInt(6) == 0) words.add(rnd.nextInt(words.size), "order delivered")
            if (rnd.nextInt(5) == 0) words += "Rs ${1 + rnd.nextInt(9)},${100 + rnd.nextInt(900)}.00"
            val body = words.joinToString(if (rnd.nextBoolean()) " " else ", ") + " #$i"
            indexedRow(
                providerId = i + 1L,
                threadId = 7_000L + senders.indexOfFirst { it.first == address },
                address = address,
                mergeKey = mergeKey,
                conversationId = conversationId,
                dateMillis = dates[i],
                body = body,
                subId = 1 + rnd.nextInt(2),
                box = if (phone && rnd.nextInt(3) == 0) MessageBox.SENT else MessageBox.INBOX,
                read = rnd.nextBoolean(),
                category = if (phone) Category.PERSONAL else listOf(Category.TRANSACTION, Category.OTP, Category.PROMOTION)[rnd.nextInt(3)],
                canonicalSender = canonical,
                otpCode = if (rnd.nextInt(10) == 0) "${100_000 + rnd.nextInt(900_000)}" else null,
            )
        }
    }

    private val groups = listOf(SenderMergeGroup("FLPKRT", "Flipkart Deals", false, null, false, 0L))

    private suspend fun load(h: IndexHarness, rows: List<IndexedMessage>) {
        rows.chunked(500).forEach { h.db.messageDao().insertIgnore(it) }
        groups.forEach { h.db.senderMergeDao().putGroup(it) }
    }

    // ---- reference ----

    private fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (c in text) {
            if (Highlighter.isWordChar(c)) current.append(c) else if (current.isNotEmpty()) { out += current.toString(); current.clear() }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private class Doc(val row: IndexedMessage, val columns: List<List<String>>)

    private fun docs(rows: List<IndexedMessage>) = rows.map { Doc(it, listOf(tokens(it.searchText), tokens(it.searchSender))) }

    private fun phrase(doc: Doc, words: List<String>): Boolean = doc.columns.any { col ->
        (0..col.size - words.size).any { start -> words.indices.all { col[start + it] == words[it] } }
    }

    /** What `FtsSqlRenderer` asks FTS for: a 2+ letter single token is a prefix, a shorter one exact, several a phrase. */
    private fun textMatches(expr: TextExpr, doc: Doc): Boolean = when (expr) {
        is TextExpr.Term -> {
            val t = Highlighter.tokenize(expr.value)
            when {
                t.size == 1 && t[0].length >= 2 -> doc.columns.any { col -> col.any { it.startsWith(t[0]) } }
                else -> phrase(doc, t)
            }
        }
        is TextExpr.Phrase -> phrase(doc, Highlighter.tokenize(expr.value))
        is TextExpr.And -> textMatches(expr.left, doc) && textMatches(expr.right, doc)
        is TextExpr.Or -> textMatches(expr.left, doc) || textMatches(expr.right, doc)
        is TextExpr.Not -> !textMatches(expr.expr, doc)
    }

    private fun filterMatches(f: Filter, m: IndexedMessage): Boolean = when (f) {
        is Filter.From -> {
            val v = f.value.trim()
            m.mergeKey == v.uppercase() || m.address.contains(v, ignoreCase = true) ||
                (m.canonicalSender?.contains(v, ignoreCase = true) ?: false) ||
                (groups.firstOrNull { it.mergeKey == m.mergeKey }?.displayName?.contains(v, ignoreCase = true) ?: false)
        }
        is Filter.CategoryIs -> m.category == f.category
        Filter.IsUnread -> !m.read && m.box == MessageBox.INBOX
        Filter.HasOtp -> m.otpCode != null
        else -> error("not used by this test: $f")
    }

    private data class Hit(val conversationId: String, val kind: MessageKind, val providerId: Long, val matchCount: Int, val sortValue: Long?)

    private fun expected(docs: List<Doc>, query: SearchQuery, sort: SearchSort): List<Hit> {
        val matching = docs.filter { d ->
            (query.textExpr?.let { textMatches(it, d) } ?: true) && query.filters.all { filterMatches(it, d.row) }
        }.map { it.row }
        val hits = matching.groupBy { it.conversationId }.map { (id, rows) ->
            val newest = rows.maxByOrNull { it.dateMillis }!!
            Hit(id, newest.kind, newest.providerId, rows.size, newest.dateMillis)
        }
        return when (sort) {
            SearchSort.RELEVANCE -> hits.sortedWith(compareByDescending<Hit> { it.matchCount }.thenByDescending { it.sortValue }.thenBy { it.conversationId })
            else -> hits.sortedWith(compareByDescending<Hit> { it.sortValue }.thenBy { it.conversationId })
        }
    }

    private fun SqlQuery.toSupport() = SimpleSQLiteQuery(sql, args.toTypedArray())

    private suspend fun search(h: IndexHarness, query: SearchQuery, sort: SearchSort, limit: Int, offset: Int): List<Hit> =
        h.db.rawQueryDao().search(SearchSqlBuilder().messages(query, sort, SearchSqlBuilder.Resolved(), limit, offset).toSupport())
            .map { Hit(it.conversationId, it.kind, it.providerId, it.matchCount, it.sortValue) }

    private fun matchKeys(h: IndexHarness, match: String): Set<MessageKey> =
        h.sql.query(
            "SELECT kind, providerId FROM indexed_message WHERE rowid IN (SELECT rowid FROM message_fts WHERE message_fts MATCH ?)",
            arrayOf<Any?>(match),
        ).use { c ->
            val out = HashSet<MessageKey>()
            while (c.moveToNext()) out += MessageKey(MessageKind.valueOf(c.getString(0)), c.getLong(1))
            out
        }

    private val queries: List<SearchQuery> = listOf(
        SearchQuery(TextExpr.Term("pa")),
        SearchQuery(TextExpr.Term("pay")),
        SearchQuery(TextExpr.Term("payme")),
        SearchQuery(TextExpr.Term("payments")),
        SearchQuery(TextExpr.Term("or")),
        SearchQuery(TextExpr.Term("ord")),
        SearchQuery(TextExpr.Term("hd")),
        SearchQuery(TextExpr.Term("hdfcb")),
        SearchQuery(TextExpr.Term("a")),
        SearchQuery(TextExpr.Term("amt")),
        SearchQuery(TextExpr.Term("PAY")),
        SearchQuery(TextExpr.Phrase("order delivered")),
        SearchQuery(TextExpr.Term("e-mail")),
        SearchQuery(TextExpr.And(TextExpr.Term("pay"), TextExpr.Term("ca"))),
        SearchQuery(TextExpr.Or(TextExpr.Term("ref"), TextExpr.Term("shi"))),
        SearchQuery(TextExpr.And(TextExpr.Term("paytm"), TextExpr.Not(TextExpr.Term("off")))),
        SearchQuery(TextExpr.Term("de"), listOf(Filter.From("amazon"))),
        SearchQuery(TextExpr.Term("cr"), listOf(Filter.CategoryIs(Category.TRANSACTION))),
        SearchQuery(TextExpr.Term("on"), listOf(Filter.IsUnread)),
        SearchQuery(TextExpr.Term("o"), listOf(Filter.HasOtp)),
        SearchQuery(TextExpr.Term("st"), listOf(Filter.From("flipkart"))),
        SearchQuery(TextExpr.Term("98765")),
        SearchQuery(null, listOf(Filter.From("HDFC"), Filter.IsUnread)),
    )

    @Test
    fun rawPrefixAndPhraseMatchesEqualTheReference(): Unit = runBlocking {
        val rows = corpus(2_500, seed = 5L)
        val docs = docs(rows)
        IndexHarness().use { h ->
            load(h, rows)
            val prefixes = listOf("pa", "pay", "paym", "payme", "or", "ord", "orde", "hd", "hdf", "hdfcb", "de", "del", "deliv", "am", "amz", "vm", "98", "987")
            for (p in prefixes) {
                val want = docs.filter { d -> d.columns.any { col -> col.any { it.startsWith(p) } } }.mapTo(HashSet()) { MessageKey(it.row.kind, it.row.providerId) }
                assertTrue(want.isNotEmpty(), "prefix $p should match something")
                assertEquals(want, matchKeys(h, "$p*"), "MATCH '$p*'")
            }
            for (exact in listOf("a", "pay", "order", "hdfcbank")) {
                val want = docs.filter { d -> d.columns.any { exact in it } }.mapTo(HashSet()) { MessageKey(it.row.kind, it.row.providerId) }
                assertEquals(want, matchKeys(h, "\"$exact\""), "MATCH exact $exact")
            }
            val phraseWant = docs.filter { phrase(it, listOf("order", "delivered")) }.mapTo(HashSet()) { MessageKey(it.row.kind, it.row.providerId) }
            assertTrue(phraseWant.isNotEmpty())
            assertEquals(phraseWant, matchKeys(h, "\"order delivered\""))
        }
    }

    @Test
    fun groupedSearchesEqualTheReferencePageByPage(): Unit = runBlocking {
        val rows = corpus(2_500, seed = 6L)
        val docs = docs(rows)
        IndexHarness().use { h ->
            load(h, rows)
            var nonEmpty = 0
            for (query in queries) {
                for (sort in listOf(SearchSort.RECENT, SearchSort.RELEVANCE)) {
                    val all = expected(docs, query, sort)
                    if (all.isNotEmpty()) nonEmpty++
                    var offset = 0
                    while (offset <= all.size) {
                        assertEquals(all.subList(offset, minOf(all.size, offset + 3)), search(h, query, sort, 3, offset), "$query $sort offset $offset")
                        offset += 3
                    }
                }
            }
            assertTrue(nonEmpty >= queries.size * 2 - 8, "most queries should find something: $nonEmpty")
        }
    }

    @Test
    fun recentSenderNamesAndSuggestionsEqualTheReference(): Unit = runBlocking {
        val rows = corpus(1_500, seed = 8L)
        IndexHarness().use { h ->
            load(h, rows)
            val names = rows.groupBy { it.canonicalSender ?: it.address }
                .map { (name, rs) -> name to rs.maxOf { it.dateMillis } }
                .sortedByDescending { it.second }
                .map { it.first }
            assertEquals(names, h.db.messageDao().recentSenderNames(200))
            assertEquals(names.take(3), h.db.messageDao().recentSenderNames(3))

            val repo = SearchRepository(h.db, NoSims, Optional.empty())
            repo.recordQuery("payment")
            repo.recordQuery("hdfc statement")
            val recent = h.db.savedSearchDao().recentQueries(20)
            for (prefix in listOf("", "a", "am", "hd", "HDFC", "pay", "+91", "flp", "zz", "amazn")) {
                val want = SuggestionEngine({ recent }, { names }, { emptyList() }).suggest(prefix, 8)
                assertEquals(want, repo.suggestions(prefix), "suggestions for '$prefix'")
            }
            // A new sender shows up in the next suggestions.
            h.db.messageDao().insertIgnore(
                listOf(indexedRow(providerId = 99_999L, threadId = 1L, address = "VM-ZOMATO", mergeKey = "ZOMATO", conversationId = "m:ZOMATO", dateMillis = START_MILLIS + 400 * DAY_MILLIS, body = "order", canonicalSender = "Zomato")),
            )
            assertEquals("Zomato", repo.suggestions("zom").first().text)
        }
    }

    @Test
    fun timingOfPrefixSearchesOnTwentyThousandRows(): Unit = runBlocking {
        val rows = corpus(20_000, seed = 9L)
        IndexHarness().use { h ->
            load(h, rows)
            fun median(block: () -> Unit): Double {
                block()
                return (1..5).map { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6 }.sorted()[2]
            }
            val lines = ArrayList<String>()
            for (p in listOf("pa", "pay", "payme", "or", "hdf")) {
                val raw = median { matchKeys(h, "$p*") }
                val grouped = median { runBlocking { search(h, SearchQuery(TextExpr.Term(p)), SearchSort.RECENT, 30, 0) } }
                lines += "SEARCH-BENCH rows=${rows.size} prefix '$p': raw MATCH %.1f ms, first grouped page %.1f ms (%d rows match)".format(raw, grouped, matchKeys(h, "$p*").size)
            }
            val names = median { runBlocking { h.db.messageDao().recentSenderNames(200) } }
            lines += "SEARCH-BENCH rows=${rows.size} recentSenderNames(200) %.1f ms (runs on every suggestions() call)".format(names)
            val q = SearchSqlBuilder().messages(SearchQuery(TextExpr.Term("pa")), SearchSort.RECENT, SearchSqlBuilder.Resolved(), 30, 0)
            val plan = h.sql.query("EXPLAIN QUERY PLAN ${q.sql}", q.args.toTypedArray()).use { c ->
                val d = c.getColumnIndexOrThrow("detail")
                val out = ArrayList<String>()
                while (c.moveToNext()) out += c.getString(d)
                out
            }
            lines += "SEARCH-PLAN " + plan.joinToString(" | ")
            println(lines.joinToString("\n"))
        }
    }
}
