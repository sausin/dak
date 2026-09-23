package app.dak.classify.text

import java.util.BitSet

/**
 * One [AhoCorasick] pass that prefilters a whole list of regex [patterns] at once: [scan] finds every keyword present
 * in a text, then [mayMatch] says whether pattern `i` can possibly match it (false only when none of its
 * [RequiredLiterals] occurs). Patterns without a safe literal always may match. Immutable and thread-safe.
 */
public class KeywordPrefilter(patterns: List<String>) {

    /** Literal indexes per pattern; null means "always run". */
    private val literalIndexes: Array<IntArray?>
    private val automaton: AhoCorasick?

    init {
        val literals = ArrayList<String>()
        val indexOf = HashMap<String, Int>()
        literalIndexes = Array(patterns.size) { i ->
            RequiredLiterals.of(patterns[i])?.map { lit -> indexOf.getOrPut(lit) { literals.size.also { literals += lit } } }?.toIntArray()
        }
        automaton = if (literals.isEmpty()) null else AhoCorasick(literals)
    }

    /** Number of patterns gated by at least one literal. */
    public val gatedCount: Int get() = literalIndexes.count { it != null }

    /** The keywords present in [text], for [mayMatch]. */
    public fun scan(text: CharSequence): BitSet = automaton?.matches(text) ?: EMPTY

    /** False only when pattern [index] certainly has no match in the text that produced [hits]. */
    public fun mayMatch(index: Int, hits: BitSet): Boolean {
        val indexes = literalIndexes[index] ?: return true
        for (i in indexes) if (hits.get(i)) return true
        return false
    }

    private companion object {
        val EMPTY = BitSet(0)
    }
}
