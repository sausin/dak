package app.dak.classify

import app.dak.classify.text.AhoCorasick
import app.dak.classify.text.RequiredLiterals
import java.util.BitSet
import java.util.IdentityHashMap

/**
 * Single-pass keyword prefilter for template rules: one [AhoCorasick] scan of a body finds every rule keyword at
 * once, and a rule's regex only runs when one of its required literals ([RequiredLiterals]) is present. Rules whose
 * pattern yields no safe literal always run. Results are identical to trying every regex (a regex cannot match a
 * text that lacks all of its required literals); `RulePrefilterTest` checks that over the benchmark corpus.
 *
 * Immutable and thread-safe.
 */
internal class RulePrefilter(rules: List<TemplateRule>) {

    /** Literal indexes per rule; a rule mapped to null always runs. Keyed by identity (rules are bundle objects). */
    private val literalsByRule = IdentityHashMap<TemplateRule, IntArray?>()
    private val automaton: AhoCorasick?

    init {
        val literals = ArrayList<String>()
        val indexOf = HashMap<String, Int>()
        for (rule in rules) {
            val required = RequiredLiterals.of(rule.pattern)
            literalsByRule[rule] = required?.map { lit -> indexOf.getOrPut(lit) { literals.size.also { literals += lit } } }?.toIntArray()
        }
        automaton = if (literals.isEmpty()) null else AhoCorasick(literals)
    }

    /** The keywords present in [body] (pass to [mayMatch]). */
    fun scan(body: CharSequence): BitSet = automaton?.matches(body) ?: BitSet(0)

    /** False only when [rule]'s regex certainly does not match the body that produced [hits]. */
    fun mayMatch(rule: TemplateRule, hits: BitSet): Boolean {
        if (!literalsByRule.containsKey(rule)) return true // not from this bundle: no information
        val indexes = literalsByRule[rule] ?: return true
        for (index in indexes) if (hits.get(index)) return true
        return false
    }

    /** Number of rules gated by at least one literal (for diagnostics and tests). */
    val gatedRuleCount: Int get() = literalsByRule.values.count { it != null }
}
