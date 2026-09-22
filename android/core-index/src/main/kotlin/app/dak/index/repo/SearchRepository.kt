package app.dak.index.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.sqlite.db.SimpleSQLiteQuery
import app.dak.core.model.Message
import app.dak.core.model.MessageKey
import app.dak.index.ContactLookup
import app.dak.index.MessageItem
import app.dak.index.NoContactLookup
import app.dak.index.SearchHit
import app.dak.index.SearchSort
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.IndexJson
import app.dak.index.db.entity.BinEntry
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.SearchHistoryRow
import app.dak.index.enrich.ConversationIds
import app.dak.index.enrich.SenderGrouping
import app.dak.index.sql.SearchSqlBuilder
import app.dak.index.sql.SqlQuery
import app.dak.index.sql.Tables
import app.dak.index.sql.likeContains
import app.dak.index.text.Highlighter
import app.dak.search.Filter
import app.dak.search.SearchQuery
import app.dak.search.Suggestion
import app.dak.search.SuggestionEngine
import app.dak.telephony.SimRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search over the index: FTS4 MATCH for the text part plus structured filters resolved against enrichment columns
 * (see `SearchSqlBuilder`), results grouped by conversation with the matching message and highlight ranges.
 * Parse user input with `app.dak.search.QueryParser.parse(text, ZonedDateTime.now())` first.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val db: DakIndexDatabase,
    private val sims: SimRepository,
    contacts: Optional<ContactLookup>,
) {
    private val messageDao = db.messageDao()
    private val mergeDao = db.senderMergeDao()
    private val binDao = db.binDao()
    private val rawDao = db.rawQueryDao()
    private val savedDao = db.savedSearchDao()
    private val contacts: ContactLookup = contacts.orElse(NoContactLookup)
    private val builder = SearchSqlBuilder()

    /** Paged results for [query]; an empty query yields no results. Live: re-runs when the index changes. */
    fun search(query: SearchQuery, sort: SearchSort = SearchSort.RECENT, pageSize: Int = 30): Flow<PagingData<SearchHit>> {
        if (builder.isEmpty(query)) return flowOf(PagingData.empty())
        return Pager(PagingConfig(pageSize = pageSize, enablePlaceholders = false)) {
            SearchPagingSource(query, sort)
        }.flow
    }

    /**
     * Typed-ahead suggestions from recent queries, senders (brands, merge-group names, addresses) and contacts,
     * ranked by `app.dak.search.SuggestionEngine`.
     */
    suspend fun suggestions(prefix: String, limit: Int = 8): List<Suggestion> = withContext(Dispatchers.IO) {
        val recent = savedDao.recentQueries(HISTORY_SUGGESTIONS)
        val senders = messageDao.recentSenderNames(SENDER_SUGGESTIONS)
        val contactNames = if (prefix.isBlank()) emptyList() else contacts.namesMatching(prefix, limit)
        SuggestionEngine({ recent }, { senders }, { contactNames }).suggest(prefix, limit)
    }

    /** Records an executed query for suggestions (call when the user submits a search). */
    suspend fun recordQuery(queryText: String): Unit = withContext(Dispatchers.IO) {
        val text = queryText.trim()
        if (text.isEmpty()) return@withContext
        val existing = savedDao.history(text)
        savedDao.putHistory(SearchHistoryRow(text, System.currentTimeMillis(), (existing?.useCount ?: 0) + 1))
        savedDao.trimHistory(HISTORY_KEEP)
    }

    suspend fun clearHistory(): Unit = withContext(Dispatchers.IO) {
        savedDao.clearHistory()
    }

    /** Resolves `from:` and `sim:` values that need lookups (contacts, merge-group names, SIM slots/names). */
    private suspend fun resolve(query: SearchQuery): SearchSqlBuilder.Resolved {
        val fromValues = LinkedHashSet<String>()
        val simValues = LinkedHashSet<String>()
        fun collect(filter: Filter) {
            when (filter) {
                is Filter.From -> fromValues += filter.value
                is Filter.Sim -> simValues += filter.value
                is Filter.Not -> collect(filter.filter)
                else -> Unit
            }
        }
        query.filters.forEach(::collect)
        val fromKeys = fromValues.associateWith { value ->
            val keys = LinkedHashSet<String>()
            keys += SenderGrouping.mergeKey(value, emptyMap())
            keys += mergeDao.mergeKeysNamed(likeContains(value.trim()))
            contacts.addressesMatching(value).forEach { keys += SenderGrouping.mergeKey(it, emptyMap()) }
            keys.toList()
        }
        val active = sims.sims.value
        val simIds = simValues.associateWith { value ->
            val v = value.trim()
            active.filter { sim ->
                (sim.slotIndex >= 0 && (sim.slotIndex + 1).toString() == v) ||
                    sim.subId.toString() == v ||
                    sim.displayName.equals(v, ignoreCase = true) ||
                    (sim.carrierName?.equals(v, ignoreCase = true) ?: false)
            }.map { it.subId }
        }
        return SearchSqlBuilder.Resolved(fromKeys, simIds)
    }

    private fun titleOf(row: IndexedMessage): String {
        if (ConversationIds.isMergeGroup(row.conversationId)) return row.canonicalSender ?: row.address
        return contacts.displayName(row.address) ?: row.canonicalSender ?: row.address
    }

    private fun SqlQuery.toSupport(): SimpleSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())

    private inner class SearchPagingSource(
        private val query: SearchQuery,
        private val sort: SearchSort,
    ) : OffsetPagingSource<SearchHit>(db, arrayOf(Tables.MESSAGE, Tables.PREFS, Tables.MERGE_GROUP, Tables.BIN)) {

        private var resolved: SearchSqlBuilder.Resolved? = null
        private val highlightTokens: List<String> = query.textExpr?.let { Highlighter.positiveTokens(it) }.orEmpty()

        override suspend fun loadRange(offset: Int, limit: Int): List<SearchHit> {
            val r = resolved ?: resolve(query).also { resolved = it }
            return if (builder.isBinSearch(query)) binHits(r, offset, limit) else messageHits(r, offset, limit)
        }

        private suspend fun messageHits(r: SearchSqlBuilder.Resolved, offset: Int, limit: Int): List<SearchHit> {
            val rows = rawDao.search(builder.messages(query, sort, r, limit, offset).toSupport())
            if (rows.isEmpty()) return emptyList()
            val byKey = HashMap<MessageKey, IndexedMessage>(rows.size)
            for ((kind, group) in rows.groupBy { it.kind }) {
                messageDao.getAll(kind.name, group.map { it.providerId }).forEach { byKey[MessageKey(kind, it.providerId)] = it }
            }
            val groupNames = HashMap<String, String?>()
            return rows.mapNotNull { hit ->
                val row = byKey[MessageKey(hit.kind, hit.providerId)] ?: return@mapNotNull null
                val title = if (ConversationIds.isMergeGroup(row.conversationId)) {
                    groupNames.getOrPut(row.mergeKey) { mergeDao.group(row.mergeKey)?.displayName } ?: titleOf(row)
                } else {
                    titleOf(row)
                }
                SearchHit(
                    conversationId = hit.conversationId,
                    conversationTitle = title,
                    message = Mappers.messageItem(row),
                    matchCount = hit.matchCount,
                    highlights = Highlighter.ranges(row.body, highlightTokens),
                )
            }
        }

        private suspend fun binHits(r: SearchSqlBuilder.Resolved, offset: Int, limit: Int): List<SearchHit> {
            val ids = rawDao.ids(builder.bin(query, r, limit, offset).toSupport()).map { it.id }
            if (ids.isEmpty()) return emptyList()
            val entries = binDao.getAll(ids).associateBy { it.id }
            return ids.mapNotNull { id -> entries[id]?.let { binHit(it) } }
        }

        private fun binHit(entry: BinEntry): SearchHit {
            val message = runCatching { IndexJson.json.decodeFromString(Message.serializer(), entry.messageJson) }.getOrNull()
            val item: MessageItem = Mappers.providerMessageItem(
                message ?: Message(entry.providerId, entry.kind, entry.threadId, entry.address, entry.body, entry.dateMillis, entry.subId, entry.box, entry.read),
            ).copy(conversationId = entry.conversationId, category = entry.category)
            return SearchHit(
                conversationId = entry.conversationId,
                conversationTitle = contacts.displayName(entry.address) ?: entry.address,
                message = item,
                matchCount = 1,
                highlights = Highlighter.ranges(entry.body, highlightTokens),
                binId = entry.id,
            )
        }
    }

    private companion object {
        const val HISTORY_KEEP = 50
        const val HISTORY_SUGGESTIONS = 20
        const val SENDER_SUGGESTIONS = 200
    }
}
