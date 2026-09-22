package app.dak.search

import kotlinx.serialization.Serializable
import kotlin.math.min

/** A user-named, re-runnable query. Pinned to the inbox as a virtual folder; can drive automations. */
@Serializable
data class SavedSearch(val id: String, val name: String, val query: String)

/** One typed-ahead suggestion: the text to offer, and where it came from. */
data class Suggestion(val text: String, val source: Source) {
    enum class Source { RECENT_QUERY, SENDER, CONTACT }
}

/**
 * Ranks typed-ahead suggestions from recent queries, sender names and contacts against a
 * user-typed prefix. Prefix matches (case/diacritic-insensitive via [TextNormalizer]) rank above
 * fuzzy (edit-distance) matches; within a tier, shorter/earlier matches rank first.
 */
class SuggestionEngine(
    private val recentQueries: () -> List<String>,
    private val senderNames: () -> List<String>,
    private val contactNames: () -> List<String>,
) {
    /** Max edit distance (on the normalized strings) still considered a fuzzy match. */
    private val maxFuzzyDistance = 2

    fun suggest(prefix: String, limit: Int = 8): List<Suggestion> {
        val normalizedPrefix = TextNormalizer.normalize(prefix)
        if (normalizedPrefix.isBlank()) {
            return recentQueries().distinct().take(limit).map { Suggestion(it, Suggestion.Source.RECENT_QUERY) }
        }

        val candidates = buildList {
            recentQueries().forEach { add(it to Suggestion.Source.RECENT_QUERY) }
            senderNames().forEach { add(it to Suggestion.Source.SENDER) }
            contactNames().forEach { add(it to Suggestion.Source.CONTACT) }
        }

        data class Scored(val suggestion: Suggestion, val rank: Int, val distance: Int)

        val scored = candidates.mapNotNull { (text, source) ->
            val normalized = TextNormalizer.normalize(text)
            when {
                normalized.startsWith(normalizedPrefix) -> Scored(Suggestion(text, source), 0, 0)
                normalized.contains(normalizedPrefix) -> Scored(Suggestion(text, source), 1, 0)
                else -> {
                    val distance = damerauLevenshtein(normalizedPrefix, normalized.take(normalizedPrefix.length + maxFuzzyDistance))
                    if (distance <= maxFuzzyDistance) Scored(Suggestion(text, source), 2, distance) else null
                }
            }
        }

        return scored
            .distinctBy { it.suggestion.text to it.suggestion.source }
            .sortedWith(compareBy({ it.rank }, { it.distance }, { it.suggestion.text.length }))
            .take(limit)
            .map { it.suggestion }
    }

    companion object {
        /** Token-based Damerau-Levenshtein edit distance (insert/delete/substitute/transpose). */
        fun damerauLevenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            val da = HashMap<Char, Int>()
            val maxDist = a.length + b.length
            val d = Array(a.length + 2) { IntArray(b.length + 2) }
            d[0][0] = maxDist
            for (i in 0..a.length) {
                d[i + 1][0] = maxDist
                d[i + 1][1] = i
            }
            for (j in 0..b.length) {
                d[0][j + 1] = maxDist
                d[1][j + 1] = j
            }

            for (i in 1..a.length) {
                var db = 0
                for (j in 1..b.length) {
                    val i1 = da.getOrDefault(b[j - 1], 0)
                    val j1 = db
                    val cost: Int
                    if (a[i - 1] == b[j - 1]) {
                        cost = 0
                        db = j
                    } else {
                        cost = 1
                    }
                    d[i + 1][j + 1] = min(
                        min(d[i][j] + cost, d[i + 1][j] + 1),
                        min(d[i][j + 1] + 1, d[i1][j1] + (i - i1 - 1) + 1 + (j - j1 - 1)),
                    )
                }
                da[a[i - 1]] = i
            }
            return d[a.length + 1][b.length + 1]
        }
    }
}
