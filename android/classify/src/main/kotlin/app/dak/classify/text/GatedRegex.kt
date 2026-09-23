package app.dak.classify.text

/**
 * A [Regex] behind a literal prefilter: the regex only runs when one of its [RequiredLiterals] occurs in the input
 * (one [AhoCorasick] pass). Results are identical to the plain regex; inputs that cannot match are rejected in
 * linear time instead of being tried at every position by a backtracking alternation.
 *
 * Patterns with no safe literal (or compiled with options that change literal matching, such as `COMMENTS` or
 * `LITERAL`) are never gated, so the wrapper is always safe to use. Immutable and thread-safe.
 */
public class GatedRegex(pattern: String, options: Set<RegexOption> = emptySet()) {

    /** The wrapped regex. */
    public val regex: Regex = Regex(pattern, options)

    /** The literals gating the regex (folded), or null when it always runs. */
    public val literals: Set<String>? =
        if (options.any { it !in GATEABLE_OPTIONS }) null else RequiredLiterals.of(pattern)

    private val gate: AhoCorasick? = literals?.let { AhoCorasick(it.toList()) }

    /** False only when the regex certainly has no match in [input]. */
    public fun mayMatch(input: CharSequence): Boolean = gate == null || gate.containsAny(input)

    public fun containsMatchIn(input: CharSequence): Boolean = mayMatch(input) && regex.containsMatchIn(input)

    public fun find(input: CharSequence, startIndex: Int = 0): MatchResult? =
        if (mayMatch(input)) regex.find(input, startIndex) else null

    public fun findAll(input: CharSequence, startIndex: Int = 0): Sequence<MatchResult> =
        if (mayMatch(input)) regex.findAll(input, startIndex) else emptySequence()

    override fun toString(): String = regex.pattern

    private companion object {
        val GATEABLE_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
    }
}
