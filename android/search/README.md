# :search

Pure-Kotlin (JVM) module implementing the Gmail-style query language, its AST, FTS4 translation,
Unicode normalization and typed-ahead suggestions described in the build plan's "Search and
filtering" section. No Android dependency; depends only on `:core-model`.

## Public API

### `QueryParser` (object)

```kotlin
fun parse(input: String, now: ZonedDateTime): SearchQuery
```

Total (never throws) and whitespace-tolerant. Supported operators: `from:`, `category:`, `sim:`,
`has:attachment|link|otp`, `amount:>N` / `<N` / `a..b` / `=N`, `before:` / `after:` (ISO date,
`dd/MM/yyyy`, `today`, `yesterday`), `during:` (`today`, `yesterday`, `this week`, `last week`,
`this month`, `last month`, `last N days`, a month name, a 4-digit year — quotes allowed around
multi-word values), `in:archive|bin|inbox`, `is:starred|unread|read`, and negation (`-from:x`,
`-word`). Free text combines with implicit `AND`; `OR` joins two text atoms; quoted phrases are
kept intact. `now` (with its `ZoneId`) resolves all relative/natural dates. Unknown operators and
anything unparsable fall back to a free-text term.

### `SearchQuery` (data class)

```kotlin
data class SearchQuery(val textExpr: TextExpr?, val filters: List<Filter> = emptyList())
```

- `toQueryString(): String` — re-serializes to the same operator syntax (round-trips through
  `QueryParser.parse` for every filter kind, including negation and amount/date filters, which
  round-trip through their epoch-millis form).
- `chips(): List<Chip>` — one chip per filter with a human-readable `label`, for UI chip rows.
- `withFilter(filter): SearchQuery` / `withoutFilter(filter): SearchQuery` — structural add/remove
  for chip editing.

`TextExpr` is `Term`, `Phrase`, `And`, `Or`, `Not`. `Filter` is a sealed hierarchy: `From`,
`CategoryIs`, `Sim`, `HasAttachment`, `HasLink`, `HasOtp`, `AmountRange(minMinor?, maxMinor?)`
(inclusive bounds, minor currency units — see KDoc on the class for the exact `>`/`<`/`..`/`=`
encoding), `DateRange(startMillis?, endMillis?)` (half-open `[start, end)`), `InFolder(Folder)`,
`IsStarred`, `IsUnread`, `IsRead`, `Not(Filter)`.

### `FtsMatch` (object)

```kotlin
fun build(textExpr: TextExpr?, prefixLastTerm: Boolean = false): String?
fun sanitizeTerm(raw: String): String
```

Translates the free-text side of a query into an SQLite **FTS4 standard query syntax** `MATCH`
expression (Android's bundled SQLite lacks FTS4's enhanced/parenthesized syntax — this is a
documented assumption, see the KDoc on the object). Implicit `AND` by space, literal `OR`, and `-`
negation that is reordered behind a positive anchor term since a MATCH expression cannot start
with `NOT`; returns `null` if there is no positive anchor (all-negated text) or no text at all.
Structured `Filter`s are never part of this string — they resolve against enrichment columns.
`sanitizeTerm` runs `TextNormalizer.normalize` and strips FTS special characters (`" * : ( ) - ^`).

### `TextNormalizer` (object)

```kotlin
fun normalize(s: String): String
```

Lower-case → NFKC → strip Latin combining diacritics (`U+0300..U+036F`) → NFC. Indic combining
marks (matras, virama, ...) live in their own Unicode blocks and are never touched. Used for both
indexing text and query terms so the two sides of a match agree.

### `SuggestionEngine` / `SavedSearch`

```kotlin
class SuggestionEngine(
    recentQueries: () -> List<String>,
    senderNames: () -> List<String>,
    contactNames: () -> List<String>,
) {
    fun suggest(prefix: String, limit: Int = 8): List<Suggestion>
    companion object { fun damerauLevenshtein(a: String, b: String): Int }
}

data class Suggestion(val text: String, val source: Suggestion.Source) // RECENT_QUERY, SENDER, CONTACT
data class SavedSearch(val id: String, val name: String, val query: String) // @Serializable
```

Prefix matches (normalized) rank above substring matches, which rank above fuzzy
(Damerau-Levenshtein, distance ≤ 2) matches; results are de-duplicated by text+source.
`SavedSearch.query` is the raw query string — round-trip it through `QueryParser.parse` /
`SearchQuery.toQueryString()`.

## Notes / follow-ups for other modules

- `:core-index` (Android) is expected to call `TextNormalizer.normalize` when indexing message
  text into the FTS4 table, and `FtsMatch.build` / `sanitizeTerm` when building the `MATCH`
  argument, so both sides agree.
- `AmountRange`/`DateRange`/`CategoryIs`/`Sim`/`InFolder`/`IsStarred`/`IsUnread`/`IsRead` are meant
  to resolve against the encrypted index's enrichment columns as structured lookups, not text
  scans (per the build plan) — that binding lives in `:core-index`, not here.
- Nothing here is Android-specific; `ZonedDateTime`/`Clock` injection keeps `QueryParser` testable
  and reusable from a ViewModel unchanged.
