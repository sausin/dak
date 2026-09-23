package app.dak.settings

import app.dak.premium.Entitlements

/** One search hit: the row and where to deep-link (group + key), with its lock state resolved. */
data class SettingsSearchResult(
    val def: SettingDef<*>,
    val group: SettingsGroup,
    val key: String,
    val locked: Boolean,
)

/**
 * Tolerant search over the Settings registry: prefix and fuzzy matching on title, summary and
 * keywords, as the build plan's "Search first" principle asks for. Hidden rows ([SettingDef.visible]
 * false for the given [DeviceContext]) are excluded entirely; premium rows are included with
 * [SettingsSearchResult.locked] set so they still act as the free tier's sales page.
 */
object SettingsSearch {

    private const val MAX_FUZZY_DISTANCE = 2

    /**
     * @param changedKeys keys the user has changed from default; those rows rank higher, per the
     *   "ranks rows the user has changed before higher" requirement.
     */
    fun search(
        query: String,
        deviceContext: DeviceContext,
        entitlements: Entitlements,
        changedKeys: Set<String> = emptySet(),
        settings: List<SettingDef<*>> = DakSettings.all,
    ): List<SettingsSearchResult> {
        val q = query.trim().lowercase()
        val visible = settings.filter { it.visible(deviceContext) }
        if (q.isEmpty()) return emptyList()

        data class Scored(val result: SettingsSearchResult, val rank: Int, val distance: Int)

        val scored = visible.mapNotNull { def ->
            val (rank, distance) = matchScore(q, def) ?: return@mapNotNull null
            Scored(
                SettingsSearchResult(def, def.group, def.key, def.isLocked(entitlements)),
                rank,
                distance,
            )
        }

        return scored
            .sortedWith(
                compareBy(
                    { if (it.result.key in changedKeys) 0 else 1 },
                    { it.rank },
                    { it.distance },
                    { it.result.def.title },
                ),
            )
            .map { it.result }
    }

    /** Lower rank = better match; (rank, editDistance) so callers can sort with both. */
    private fun matchScore(q: String, def: SettingDef<*>): Pair<Int, Int>? {
        val title = def.title.lowercase()
        val summary = def.summary.lowercase()
        val keywords = def.keywords.map { it.lowercase() }

        if (title.startsWith(q)) return 0 to 0
        if (keywords.any { it.startsWith(q) }) return 1 to 0
        if (title.contains(q) || keywords.any { it.contains(q) }) return 2 to 0
        if (summary.contains(q)) return 3 to 0

        // Token-based fuzzy: any word in title/keywords within edit distance of the query.
        val tokens = (title.split(Regex("\\s+")) + keywords.flatMap { it.split(Regex("\\s+")) })
            .filter { it.isNotBlank() }
        val bestDistance = tokens.minOfOrNull { damerauLevenshtein(q, it) } ?: return null
        return if (bestDistance <= MAX_FUZZY_DISTANCE) 4 to bestDistance else null
    }

    private fun damerauLevenshtein(a: String, b: String): Int {
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
                d[i + 1][j + 1] = minOf(
                    d[i][j] + cost,
                    d[i + 1][j] + 1,
                    d[i][j + 1] + 1,
                    d[i1][j1] + (i - i1 - 1) + 1 + (j - j1 - 1),
                )
            }
            da[a[i - 1]] = i
        }
        return d[a.length + 1][b.length + 1]
    }
}
