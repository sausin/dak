package app.dak.search

import app.dak.core.model.Category
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Parses the Gmail-style query language described in the build plan's "Search and filtering"
 * section into a [SearchQuery]. Never throws: anything it cannot make sense of falls back to a
 * free-text term (["Unknown operators become free text"]), and extra/irregular whitespace is
 * tolerated.
 */
object QueryParser {

    private val OPERATOR_KEYS = setOf(
        "from", "category", "sim", "has", "amount", "before", "after", "during", "in", "is",
    )

    /**
     * @param now used to resolve relative dates (`during:"last week"`, `before:yesterday`, ...).
     * @param locale decides how a numeric `before:03/04/2026` is read: month first where the locale writes dates
     *   that way (en-US), else day first (en-IN, en-GB, most of the world). A date that is only valid the other way
     *   round (`25/12/2026` in the US) is still accepted.
     */
    fun parse(input: String, now: ZonedDateTime, locale: Locale = Locale.getDefault()): SearchQuery {
        val tokens = tokenize(input)
        val filters = mutableListOf<Filter>()
        // Alternating list of text atoms and the connector ("AND" implicit, or "OR") before them.
        val textAtoms = mutableListOf<Pair<String?, TextExpr>>() // connector to previous atom, atom
        var pendingOr = false

        for (rawToken in tokens) {
            if (rawToken.isBlank()) continue

            val negated = rawToken.startsWith("-") && rawToken.length > 1
            val token = if (negated) rawToken.substring(1) else rawToken

            if (!negated && token == "OR") {
                if (textAtoms.isNotEmpty()) {
                    // Mark that the *next* atom should be OR-joined; encoded by temporarily
                    // pushing a sentinel handled below.
                    pendingOr = true
                }
                continue
            }

            val operatorMatch = OPERATOR_REGEX.matchEntire(token)
            val filter: Filter? = if (operatorMatch != null) {
                val key = operatorMatch.groupValues[1].lowercase()
                if (key in OPERATOR_KEYS) {
                    parseOperator(key, unquote(operatorMatch.groupValues[2]), now, locale)
                } else null
            } else null

            if (filter != null) {
                filters += if (negated) Filter.Not(filter) else filter
                continue
            }

            // Free text: term or quoted phrase.
            val isPhrase = token.length >= 2 && token.startsWith("\"") && token.endsWith("\"")
            var atom: TextExpr = if (isPhrase) {
                TextExpr.Phrase(unquote(token))
            } else {
                val term = stripTrailingQuote(token)
                // "500000", "5,00,000", "₹5,00,000.00": also match every other spelling of the amount.
                (if (negated) null else AmountTokens.expandTerm(term)) ?: TextExpr.Term(term)
            }
            if (negated) atom = TextExpr.Not(atom)

            val connector = if (pendingOr) "OR" else if (textAtoms.isEmpty()) null else "AND"
            pendingOr = false
            textAtoms += connector to atom
        }

        val textExpr = textAtoms.fold<Pair<String?, TextExpr>, TextExpr?>(null) { acc, (connector, atom) ->
            when {
                acc == null -> atom
                connector == "OR" -> TextExpr.Or(acc, atom)
                else -> TextExpr.And(acc, atom)
            }
        }

        return SearchQuery(textExpr, filters)
    }

    private val OPERATOR_REGEX = Regex("""^([A-Za-z]+):(.*)$""", RegexOption.DOT_MATCHES_ALL)

    private fun unquote(s: String): String =
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

    private fun stripTrailingQuote(s: String): String = s.trim('"')

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in input) {
            when {
                c == '"' -> {
                    inQuotes = !inQuotes
                    sb.append(c)
                }
                c.isWhitespace() && !inQuotes -> {
                    if (sb.isNotEmpty()) {
                        tokens += sb.toString()
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) tokens += sb.toString()
        return tokens
    }

    private fun parseOperator(key: String, value: String, now: ZonedDateTime, locale: Locale): Filter? {
        if (value.isBlank() && key != "amount") return null
        return when (key) {
            "from" -> Filter.From(value)
            "sim" -> Filter.Sim(value)
            "category" -> parseCategory(value)?.let { Filter.CategoryIs(it) }
            "has" -> when (value.lowercase()) {
                "attachment" -> Filter.HasAttachment
                "link" -> Filter.HasLink
                "otp" -> Filter.HasOtp
                else -> null
            }
            "in" -> when (value.lowercase()) {
                "archive" -> Filter.InFolder(Folder.ARCHIVE)
                "bin" -> Filter.InFolder(Folder.BIN)
                "inbox" -> Filter.InFolder(Folder.INBOX)
                else -> null
            }
            "is" -> when (value.lowercase()) {
                "starred" -> Filter.IsStarred
                "unread" -> Filter.IsUnread
                "read" -> Filter.IsRead
                else -> null
            }
            "amount" -> parseAmount(value)
            "before" -> parseSingleDateMillis(value, now, locale)?.let { Filter.DateRange(null, it) }
            "after" -> parseSingleDateMillis(value, now, locale)?.let { Filter.DateRange(it, null) }
            "during" -> parseDuring(value, now)
            else -> null
        }
    }

    private fun parseCategory(value: String): Category? = when (value.lowercase()) {
        "personal" -> Category.PERSONAL
        "transaction", "transactions" -> Category.TRANSACTION
        "otp" -> Category.OTP
        "promo", "promotion", "promotions" -> Category.PROMOTION
        "spam" -> Category.SPAM
        else -> null
    }

    /**
     * `amount:` values: `>N`, `<N`, `a..b`, `=N` (the round-trip form emitted by SearchQuery.toQueryString) and a
     * bare `N` (same as `=N`). N may be written any way [AmountTokens.parseFilterValue] accepts: "500000",
     * "5,00,000", "₹5,00,000.00", "500000.00", "50k", "2.5L", "5lakh", "1cr".
     */
    private fun parseAmount(value: String): Filter.AmountRange? {
        val v = value.trim()
        return when {
            v.startsWith(">") -> minorOf(v.substring(1))?.let { Filter.AmountRange(it + 1, null) }
            v.startsWith("<") -> minorOf(v.substring(1))?.let { Filter.AmountRange(null, it - 1) }
            v.startsWith("=") -> minorOf(v.substring(1))?.let { Filter.AmountRange(it, it) }
            v.contains("..") -> {
                val (a, b) = v.split("..", limit = 2)
                val min = minorOf(a)
                val max = minorOf(b)
                if (min == null || max == null) null else Filter.AmountRange(minOf(min, max), maxOf(min, max))
            }
            else -> minorOf(v)?.let { Filter.AmountRange(it, it) }
        }
    }

    private fun minorOf(s: String): Long? = AmountTokens.parseFilterValue(s)

    private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val DD_MM_YYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val MM_DD_YYYY = DateTimeFormatter.ofPattern("MM/dd/yyyy")

    /** True when [locale]'s short date format puts the month before the day (e.g. `M/d/yy` for en-US). */
    private fun monthFirst(locale: Locale): Boolean {
        val pattern = runCatching {
            (java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, locale) as? java.text.SimpleDateFormat)?.toPattern()
        }.getOrNull() ?: return false
        val month = pattern.indexOf('M')
        val day = pattern.indexOf('d')
        return month >= 0 && day >= 0 && month < day
    }
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    private fun resolveDate(value: String, now: ZonedDateTime, locale: Locale): LocalDate? {
        val v = value.trim().lowercase()
        val today = now.toLocalDate()
        return when (v) {
            "today" -> today
            "yesterday" -> today.minusDays(1)
            else -> try {
                LocalDate.parse(value.trim(), ISO_DATE)
            } catch (e: DateTimeParseException) {
                val order = if (monthFirst(locale)) listOf(MM_DD_YYYY, DD_MM_YYYY) else listOf(DD_MM_YYYY, MM_DD_YYYY)
                order.firstNotNullOfOrNull { format ->
                    try {
                        LocalDate.parse(value.trim(), format)
                    } catch (e2: DateTimeParseException) {
                        null
                    }
                }
            }
        }
    }

    private fun parseSingleDateMillis(value: String, now: ZonedDateTime, locale: Locale): Long? {
        // Support round-tripping our own emitted epoch-millis form ("before:169...").
        value.trim().toLongOrNull()?.let { return it }
        val date = resolveDate(value, now, locale) ?: return null
        return startOfDay(date, now.zone)
    }

    private fun startOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Resolves the `during:` natural-language ranges to a half-open `[start, end)` filter. */
    private fun parseDuring(value: String, now: ZonedDateTime): Filter.DateRange? {
        val v = value.trim().lowercase()
        val zone = now.zone
        val today = now.toLocalDate()

        // Round-trip our own emitted "start..end" millis form.
        if (v.contains("..")) {
            val parts = v.split("..", limit = 2)
            val start = parts[0].toLongOrNull()
            val end = parts.getOrNull(1)?.toLongOrNull()
            if (start != null || end != null) return Filter.DateRange(start, end)
        }

        fun weekStart(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)
        fun range(start: LocalDate, endExclusive: LocalDate) =
            Filter.DateRange(startOfDay(start, zone), startOfDay(endExclusive, zone))

        val lastNDays = Regex("""last\s+(\d+)\s+days?""").matchEntire(v)
        return when {
            v == "today" -> range(today, today.plusDays(1))
            v == "yesterday" -> range(today.minusDays(1), today)
            v == "this week" -> range(weekStart(today), weekStart(today).plusDays(7))
            v == "last week" -> weekStart(today).let { range(it.minusDays(7), it) }
            v == "this month" -> today.withDayOfMonth(1).let { range(it, it.plusMonths(1)) }
            v == "last month" -> today.withDayOfMonth(1).minusMonths(1).let { range(it, it.plusMonths(1)) }
            lastNDays != null -> {
                // Never throws: an N too large for a Long or for the calendar is not a range.
                val n = lastNDays.groupValues[1].toLongOrNull() ?: return null
                try {
                    range(today.minusDays(n), today.plusDays(1))
                } catch (e: java.time.DateTimeException) {
                    null
                } catch (e: ArithmeticException) {
                    null
                }
            }
            v.matches(Regex("""\d{4}""")) -> {
                val year = v.toInt()
                range(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1))
            }
            MONTHS.contains(v) -> {
                val monthIndex = MONTHS.indexOf(v) + 1
                val start = LocalDate.of(today.year, monthIndex, 1)
                range(start, start.plusMonths(1))
            }
            else -> null
        }
    }
}
