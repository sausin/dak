package app.dak.classify

import app.dak.classify.text.KeywordPrefilter
import java.util.BitSet
import java.util.IdentityHashMap

/**
 * Single-pass keyword prefilter for template rules: one Aho-Corasick scan of a body finds every rule keyword at once,
 * and a rule's regex only runs when one of its required literals (`RequiredLiterals`) is present. Rules whose
 * pattern yields no safe literal always run. Results are identical to trying every regex (a regex cannot match a
 * text that lacks all of its required literals); `PipelineEquivalenceTest` checks that over the benchmark corpus.
 *
 * Immutable and thread-safe.
 */
internal class RulePrefilter(rules: List<TemplateRule>) {

    private val prefilter = KeywordPrefilter(rules.map { it.pattern })

    /** Position of each rule in [prefilter]; keyed by identity (rules are the bundle's own objects). */
    private val indexOf = IdentityHashMap<TemplateRule, Int>().apply { rules.forEachIndexed { i, r -> put(r, i) } }

    /** The keywords present in [body] (pass to [mayMatch]). */
    fun scan(body: CharSequence): BitSet = prefilter.scan(body)

    /** False only when [rule]'s regex certainly does not match the body that produced [hits]. */
    fun mayMatch(rule: TemplateRule, hits: BitSet): Boolean {
        val index = indexOf[rule] ?: return true // not from this bundle: no information
        return prefilter.mayMatch(index, hits)
    }

    /** Number of rules gated by at least one literal (for diagnostics and tests). */
    val gatedRuleCount: Int get() = prefilter.gatedCount
}
