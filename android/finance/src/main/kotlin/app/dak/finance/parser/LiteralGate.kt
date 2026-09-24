package app.dak.finance.parser

/**
 * A cheap literal prefilter for the parser's case-insensitive regexes: a regex that can only match text containing one
 * of its [literals] is not run on text that contains none of them. Most SMS contain only a few of the parser's
 * keywords, so most of its regex passes (each tried at every position of the body) are skipped. Same idea as
 * `:classify`'s `GatedRegex`, without the dependency.
 *
 * Literals are compared on [fold]ed text (per character `toLowerCase(toUpperCase(c))`, the same equivalence as the
 * regexes' Unicode case-insensitive matching, and length-preserving), which callers compute once per body.
 */
internal class GatedPattern(pattern: String, literals: List<String>) {
    val regex: Regex = Regex(pattern, RegexOption.IGNORE_CASE)
    private val folded: List<String> = literals.map { fold(it) }

    init {
        require(folded.isNotEmpty() && folded.none { it.isEmpty() }) { "a gated pattern needs non-empty literals" }
    }

    /** False only when the regex certainly cannot match the text whose [fold] is [foldedText]. */
    fun mayMatch(foldedText: String): Boolean = folded.any { foldedText.contains(it) }

    fun containsMatchIn(text: String, foldedText: String): Boolean = mayMatch(foldedText) && regex.containsMatchIn(text)

    fun findAll(text: String, foldedText: String): Sequence<MatchResult> =
        if (mayMatch(foldedText)) regex.findAll(text) else emptySequence()

    companion object {
        /** [s] case-folded character by character (same length as [s]). */
        fun fold(s: String): String {
            var changed = false
            val chars = CharArray(s.length)
            for (i in s.indices) {
                val c = s[i]
                val f = Character.toLowerCase(Character.toUpperCase(c))
                if (f != c) changed = true
                chars[i] = f
            }
            return if (changed) String(chars) else s
        }
    }
}
