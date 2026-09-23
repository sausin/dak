package app.dak.search

import app.dak.core.model.Category

/** Free-text side of a query: terms, quoted phrases, `AND` (implicit), `OR`, and negation. */
sealed class TextExpr {
    data class Term(val value: String) : TextExpr()
    data class Phrase(val value: String) : TextExpr()
    data class And(val left: TextExpr, val right: TextExpr) : TextExpr()
    data class Or(val left: TextExpr, val right: TextExpr) : TextExpr()
    data class Not(val expr: TextExpr) : TextExpr()
}

/** Which virtual folder an `in:` filter refers to. */
enum class Folder { INBOX, ARCHIVE, BIN }

/**
 * Structured (operator) side of a query. All filters in [SearchQuery.filters] combine with AND;
 * [Filter.Not] negates a single filter (`-from:x`).
 */
sealed class Filter {
    data class From(val value: String) : Filter()
    data class CategoryIs(val category: Category) : Filter()

    /** Either a 1-based slot number as text ("1", "2") or a SIM display name. */
    data class Sim(val value: String) : Filter()

    data object HasAttachment : Filter()
    data object HasLink : Filter()
    data object HasOtp : Filter()

    /**
     * Inclusive bounds in minor currency units (paise/cents). `amount:>500` becomes `minMinor =
     * 50001` (500*100 + 1, i.e. "500 rupees exactly" is excluded); `amount:<100` becomes `maxMinor
     * = 9999`; `amount:100..500` sets both bounds inclusively as given; `amount:=250` sets
     * `minMinor == maxMinor == 25000`.
     */
    data class AmountRange(val minMinor: Long?, val maxMinor: Long?) : Filter()

    /** Half-open millis range `[startMillis, endMillis)`; either bound may be null (unbounded). */
    data class DateRange(val startMillis: Long?, val endMillis: Long?) : Filter()

    data class InFolder(val folder: Folder) : Filter()
    data object IsStarred : Filter()
    data object IsUnread : Filter()
    data object IsRead : Filter()

    data class Not(val filter: Filter) : Filter()
}

/** A UI chip for one filter, with a human-readable [label]. */
data class Chip(val label: String, val filter: Filter)

/**
 * The parsed form of a Gmail-style search query: optional free-text expression plus a list of
 * structured filters (AND-combined). Produced by [QueryParser.parse]; serialize back with
 * [toQueryString] so chips remain editable text.
 */
data class SearchQuery(
    val textExpr: TextExpr?,
    val filters: List<Filter> = emptyList(),
) {
    fun withFilter(filter: Filter): SearchQuery =
        if (filters.contains(filter)) this else copy(filters = filters + filter)

    fun withoutFilter(filter: Filter): SearchQuery =
        copy(filters = filters.filterNot { it == filter })

    fun chips(): List<Chip> = filters.map { Chip(chipLabel(it), it) }

    fun toQueryString(): String {
        val parts = mutableListOf<String>()
        textExpr?.let { parts += textExprToString(it) }
        filters.forEach { parts += filterToString(it) }
        return parts.joinToString(" ")
    }

    companion object {
        val EMPTY = SearchQuery(null, emptyList())
    }
}

private fun quoteIfNeeded(s: String): String =
    if (s.isEmpty() || s.any { it.isWhitespace() }) "\"$s\"" else s

private fun textExprToString(expr: TextExpr): String = AmountTokens.literalOfExpansion(expr) ?: when (expr) {
    is TextExpr.Term -> expr.value
    is TextExpr.Phrase -> "\"${expr.value}\""
    is TextExpr.And -> "${textExprToString(expr.left)} ${textExprToString(expr.right)}"
    is TextExpr.Or -> "${textExprToString(expr.left)} OR ${textExprToString(expr.right)}"
    is TextExpr.Not -> "-${textExprToString(expr.expr)}"
}

private fun amountToText(minor: Long): String = AmountTokens.queryText(minor)

internal fun filterToString(filter: Filter): String = when (filter) {
    is Filter.From -> "from:${quoteIfNeeded(filter.value)}"
    is Filter.CategoryIs -> "category:${categoryToWord(filter.category)}"
    is Filter.Sim -> "sim:${quoteIfNeeded(filter.value)}"
    Filter.HasAttachment -> "has:attachment"
    Filter.HasLink -> "has:link"
    Filter.HasOtp -> "has:otp"
    is Filter.AmountRange -> when {
        filter.minMinor != null && filter.maxMinor != null && filter.minMinor == filter.maxMinor ->
            "amount:=${amountToText(filter.minMinor)}"
        filter.minMinor != null && filter.maxMinor != null ->
            "amount:${amountToText(filter.minMinor)}..${amountToText(filter.maxMinor)}"
        filter.minMinor != null -> "amount:>${amountToText(filter.minMinor - 1)}"
        filter.maxMinor != null -> "amount:<${amountToText(filter.maxMinor + 1)}"
        else -> "amount:"
    }
    is Filter.DateRange -> when {
        filter.startMillis != null && filter.endMillis == null -> "after:${filter.startMillis}"
        filter.endMillis != null && filter.startMillis == null -> "before:${filter.endMillis}"
        else -> "during:${filter.startMillis}..${filter.endMillis}"
    }
    is Filter.InFolder -> "in:${filter.folder.name.lowercase()}"
    Filter.IsStarred -> "is:starred"
    Filter.IsUnread -> "is:unread"
    Filter.IsRead -> "is:read"
    is Filter.Not -> "-${filterToString(filter.filter)}"
}

private fun categoryToWord(category: Category): String = when (category) {
    Category.PERSONAL -> "personal"
    Category.TRANSACTION -> "transaction"
    Category.OTP -> "otp"
    Category.PROMOTION -> "promotion"
    Category.SPAM -> "spam"
    Category.UNKNOWN -> "unknown"
}

private fun chipLabel(filter: Filter): String = when (filter) {
    is Filter.From -> "From: ${filter.value}"
    is Filter.CategoryIs -> "Category: ${categoryToWord(filter.category).replaceFirstChar { it.uppercase() }}"
    is Filter.Sim -> "SIM: ${filter.value}"
    Filter.HasAttachment -> "Has attachment"
    Filter.HasLink -> "Has link"
    Filter.HasOtp -> "Has OTP"
    is Filter.AmountRange -> "Amount " + filterToString(filter).removePrefix("amount:")
    is Filter.DateRange -> "Date: " + filterToString(filter).substringAfter(":")
    is Filter.InFolder -> "In: ${filter.folder.name.lowercase().replaceFirstChar { it.uppercase() }}"
    Filter.IsStarred -> "Starred"
    Filter.IsUnread -> "Unread"
    Filter.IsRead -> "Read"
    is Filter.Not -> "Not " + chipLabel(filter.filter)
}
