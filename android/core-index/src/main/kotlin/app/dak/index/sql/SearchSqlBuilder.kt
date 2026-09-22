package app.dak.index.sql

import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.index.SearchSort
import app.dak.search.Filter
import app.dak.search.Folder
import app.dak.search.SearchQuery

/**
 * Pure translation of a parsed [SearchQuery] into SQL over the index.
 *
 * Text goes through the FTS4 table ([FtsMatch]); structured filters resolve against enrichment columns, so
 * `amount:` and `category:` are index lookups, not text scans. Filters combine with AND; `Filter.Not` negates one
 * filter with NULL-safe semantics. Results are grouped by conversation: each row carries the key of the chosen
 * matching message (most recent, or the largest amount for [SearchSort.AMOUNT]) plus the number of matches.
 *
 * `in:bin` switches to searching the recycle bin (LIKE over its normalized text; only filters the bin can answer
 * are applied: from, category, sim, date, has:otp, has:attachment).
 */
internal class SearchSqlBuilder {

    /**
     * Values that need I/O to resolve, looked up before building: `from:` values to extra merge keys (contacts,
     * merge-group names), and `sim:` values to subscription ids. Keys are the raw filter values.
     */
    data class Resolved(
        val fromMergeKeys: Map<String, List<String>> = emptyMap(),
        val simSubIds: Map<String, List<Int>> = emptyMap(),
    )

    fun isBinSearch(query: SearchQuery): Boolean =
        query.filters.any { it is Filter.InFolder && it.folder == Folder.BIN }

    fun isEmpty(query: SearchQuery): Boolean =
        query.textExpr == null && query.filters.isEmpty()

    /**
     * Grouped message search. Result columns: `conversationId`, `kind`, `providerId` (the chosen message),
     * `matchCount`, `sortValue`.
     */
    fun messages(query: SearchQuery, sort: SearchSort, resolved: Resolved, limit: Int, offset: Int): SqlQuery {
        val where = ArrayList<String>()
        val args = ArrayList<Any>()
        query.textExpr?.let { expr ->
            FtsMatch(FtsMatch.Mode.Fts(FTS_PREDICATE)).toSql(expr)?.let { where += "(${it.sql})"; args += it.args }
        }
        for (filter in query.filters) {
            messageFilter(filter, resolved)?.let { where += "(${it.sql})"; args += it.args }
        }
        val agg = if (sort == SearchSort.AMOUNT) "COALESCE(m.amountMinor, -1)" else "m.dateMillis"
        val order = when (sort) {
            SearchSort.RECENT -> "sortValue DESC, conversationId ASC"
            SearchSort.RELEVANCE -> "matchCount DESC, sortValue DESC, conversationId ASC"
            SearchSort.AMOUNT -> "sortValue DESC, conversationId ASC"
        }
        // Exactly one MAX() aggregate: SQLite then takes the bare columns (kind, providerId) from the row that
        // produced the maximum, i.e. the chosen matching message of each conversation.
        val sql = buildString {
            append("SELECT m.conversationId AS conversationId, m.kind AS kind, m.providerId AS providerId, ")
            append("COUNT(*) AS matchCount, MAX($agg) AS sortValue ")
            append("FROM ${Tables.MESSAGE} m ")
            append("LEFT JOIN ${Tables.PREFS} p ON p.conversationId = m.conversationId ")
            append("LEFT JOIN ${Tables.MERGE_GROUP} g ON g.mergeKey = m.mergeKey ")
            append("WHERE ").append(if (where.isEmpty()) "1" else where.joinToString(" AND ")).append(' ')
            append("GROUP BY m.conversationId ")
            append("ORDER BY $order ")
            append("LIMIT ? OFFSET ?")
        }
        return SqlQuery(sql, args + listOf(limit.toLong(), offset.toLong()))
    }

    /** Bin search. Result column: `id` (bin entry id), newest deletion first. */
    fun bin(query: SearchQuery, resolved: Resolved, limit: Int, offset: Int): SqlQuery {
        val where = ArrayList<String>()
        val args = ArrayList<Any>()
        query.textExpr?.let { expr ->
            FtsMatch(FtsMatch.Mode.Like("b.searchText")).toSql(expr)?.let { where += "(${it.sql})"; args += it.args }
        }
        for (filter in query.filters) {
            binFilter(filter, resolved)?.let { where += "(${it.sql})"; args += it.args }
        }
        val sql = "SELECT b.id AS id FROM ${Tables.BIN} b WHERE " +
            (if (where.isEmpty()) "1" else where.joinToString(" AND ")) +
            " ORDER BY b.deletedAt DESC, b.id DESC LIMIT ? OFFSET ?"
        return SqlQuery(sql, args + listOf(limit.toLong(), offset.toLong()))
    }

    private fun messageFilter(filter: Filter, resolved: Resolved): SqlQuery? = when (filter) {
        is Filter.From -> fromClause(filter.value, resolved, address = "m.address", mergeKey = "m.mergeKey", extraLike = listOf("m.canonicalSender", "g.displayName"))
        is Filter.CategoryIs -> SqlQuery("m.category = ?", listOf(filter.category.name))
        is Filter.Sim -> simClause("m.subId", resolved.simSubIds[filter.value].orEmpty())
        Filter.HasAttachment -> SqlQuery("m.hasAttachment = 1", emptyList())
        Filter.HasLink -> SqlQuery("m.hasLink = 1", emptyList())
        Filter.HasOtp -> SqlQuery("m.otpCode IS NOT NULL", emptyList())
        is Filter.AmountRange -> amountClause(filter)
        is Filter.DateRange -> dateClause("m.dateMillis", filter)
        is Filter.InFolder -> when (filter.folder) {
            Folder.INBOX -> SqlQuery("COALESCE(p.archived, 0) = 0 AND m.archived = 0", emptyList())
            Folder.ARCHIVE -> SqlQuery("COALESCE(p.archived, 0) = 1 OR m.archived = 1", emptyList())
            Folder.BIN -> null // handled by switching to bin search
        }
        Filter.IsStarred -> SqlQuery("m.starred = 1 OR COALESCE(p.starred, 0) = 1", emptyList())
        Filter.IsUnread -> SqlQuery("m.read = 0 AND m.box = ?", listOf(MessageBox.INBOX.name))
        Filter.IsRead -> SqlQuery("m.read = 1", emptyList())
        is Filter.Not -> messageFilter(filter.filter, resolved)?.let(::negate)
    }

    private fun binFilter(filter: Filter, resolved: Resolved): SqlQuery? = when (filter) {
        is Filter.From -> fromClause(filter.value, resolved, address = "b.address", mergeKey = "b.mergeKey", extraLike = emptyList())
        is Filter.CategoryIs -> SqlQuery("b.category = ?", listOf(filter.category.name))
        is Filter.Sim -> simClause("b.subId", resolved.simSubIds[filter.value].orEmpty())
        is Filter.DateRange -> dateClause("b.dateMillis", filter)
        Filter.HasOtp -> SqlQuery("b.category = ?", listOf(Category.OTP.name))
        Filter.HasAttachment -> SqlQuery("b.hasAttachment = 1", emptyList())
        is Filter.Not -> binFilter(filter.filter, resolved)?.let(::negate)
        else -> null
    }

    private fun fromClause(
        value: String,
        resolved: Resolved,
        address: String,
        mergeKey: String,
        extraLike: List<String>,
    ): SqlQuery {
        val keys = (listOf(value.trim().uppercase()) + resolved.fromMergeKeys[value].orEmpty()).distinct()
        val like = likeContains(value.trim())
        val parts = ArrayList<String>()
        val args = ArrayList<Any>()
        parts += "$mergeKey IN (${placeholders(keys.size)})"
        args += keys
        parts += "$address LIKE ? ESCAPE '\\'"
        args += like
        for (col in extraLike) {
            parts += "$col LIKE ? ESCAPE '\\'"
            args += like
        }
        return SqlQuery(parts.joinToString(" OR "), args)
    }

    private fun simClause(column: String, subIds: List<Int>): SqlQuery =
        if (subIds.isEmpty()) SqlQuery("0", emptyList())
        else SqlQuery("$column IN (${placeholders(subIds.size)})", subIds.map { it.toLong() })

    private fun amountClause(filter: Filter.AmountRange): SqlQuery {
        val parts = ArrayList<String>()
        val args = ArrayList<Any>()
        parts += "m.amountMinor IS NOT NULL"
        filter.minMinor?.let { parts += "m.amountMinor >= ?"; args += it }
        filter.maxMinor?.let { parts += "m.amountMinor <= ?"; args += it }
        return SqlQuery(parts.joinToString(" AND "), args)
    }

    private fun dateClause(column: String, filter: Filter.DateRange): SqlQuery {
        val parts = ArrayList<String>()
        val args = ArrayList<Any>()
        filter.startMillis?.let { parts += "$column >= ?"; args += it }
        filter.endMillis?.let { parts += "$column < ?"; args += it }
        return if (parts.isEmpty()) SqlQuery("1", emptyList()) else SqlQuery(parts.joinToString(" AND "), args)
    }

    /** NULL-safe negation: a filter that evaluates to NULL (e.g. a missing amount) counts as "not matching". */
    private fun negate(q: SqlQuery): SqlQuery = SqlQuery("NOT COALESCE((${q.sql}), 0)", q.args)

    private fun placeholders(n: Int): String = List(n) { "?" }.joinToString(", ")

    companion object {
        const val FTS_PREDICATE: String =
            "m.rowid IN (SELECT rowid FROM ${Tables.MESSAGE_FTS} WHERE ${Tables.MESSAGE_FTS} MATCH ?)"
    }
}
