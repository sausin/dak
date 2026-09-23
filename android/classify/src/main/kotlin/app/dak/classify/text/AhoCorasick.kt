package app.dak.classify.text

import java.util.BitSet

/**
 * A small Aho-Corasick automaton: finds which of a fixed set of literal [patterns] occur in a text, in one pass over
 * the text, whatever the number of patterns. Pure Kotlin, immutable after construction, thread-safe.
 *
 * Matching is case-insensitive in the same (superset) sense as a `Regex` compiled with `IGNORE_CASE` on the JVM
 * (Kotlin adds `UNICODE_CASE`): every character, of patterns and text alike, is folded with [fold], so two
 * characters that such a regex treats as equal always fold to the same character. The automaton may therefore
 * report a pattern the regex would not match (never the reverse), which is what a prefilter needs.
 *
 * Implementation: the trie is turned into a full DFA over a compressed alphabet (only characters that occur in some
 * pattern get their own class; every other character maps to class 0, which leads back to the root), so scanning is
 * one table lookup per character.
 */
public class AhoCorasick(patterns: List<String>) {

    /** Number of patterns. */
    public val size: Int = patterns.size

    private val asciiClass = IntArray(128)
    private val otherClass = HashMap<Char, Int>()
    private val classCount: Int
    private val next: IntArray
    private val outputs: Array<IntArray?>
    private val anyOutput: BooleanArray

    init {
        require(patterns.none { it.isEmpty() }) { "empty pattern" }
        // Alphabet classes.
        var classes = 1
        for (p in patterns) {
            for (raw in p) {
                val c = fold(raw)
                if (c.code < 128) {
                    if (asciiClass[c.code] == 0) asciiClass[c.code] = classes++
                } else if (c !in otherClass) {
                    otherClass[c] = classes++
                }
            }
        }
        classCount = classes

        // Trie (goto function), children stored densely per node.
        val gotoTable = ArrayList<IntArray>()
        val out = ArrayList<MutableList<Int>?>()
        gotoTable += IntArray(classCount) { -1 }
        out += null
        patterns.forEachIndexed { index, p ->
            var node = 0
            for (raw in p) {
                val cls = classOf(fold(raw))
                var child = gotoTable[node][cls]
                if (child < 0) {
                    child = gotoTable.size
                    gotoTable[node][cls] = child
                    gotoTable += IntArray(classCount) { -1 }
                    out += null
                }
                node = child
            }
            (out[node] ?: ArrayList<Int>().also { out[node] = it }) += index
        }

        // Failure links, breadth first, folded into a complete transition table.
        val nodes = gotoTable.size
        val fail = IntArray(nodes)
        next = IntArray(nodes * classCount)
        val queue = IntArray(nodes)
        var head = 0
        var tail = 0
        for (cls in 0 until classCount) {
            val child = gotoTable[0][cls]
            if (child > 0) {
                fail[child] = 0
                next[cls] = child
                queue[tail++] = child
            } else {
                next[cls] = 0
            }
        }
        while (head < tail) {
            val node = queue[head++]
            val f = fail[node]
            out[f]?.let { inherited -> (out[node] ?: ArrayList<Int>().also { out[node] = it }).addAll(inherited) }
            for (cls in 0 until classCount) {
                val child = gotoTable[node][cls]
                if (child > 0) {
                    fail[child] = next[f * classCount + cls]
                    next[node * classCount + cls] = child
                    queue[tail++] = child
                } else {
                    next[node * classCount + cls] = next[f * classCount + cls]
                }
            }
        }
        outputs = Array(nodes) { n -> out[n]?.distinct()?.toIntArray() }
        anyOutput = BooleanArray(nodes) { outputs[it] != null }
    }

    /** Indexes of the patterns that occur in the first [limit] characters of [text]. */
    public fun matches(text: CharSequence, limit: Int = text.length): BitSet {
        val found = BitSet(size)
        var state = 0
        val end = minOf(limit, text.length)
        for (i in 0 until end) {
            state = next[state * classCount + classOf(fold(text[i]))]
            if (anyOutput[state]) outputs[state]!!.forEach { found.set(it) }
        }
        return found
    }

    /** True when at least one pattern occurs in the first [limit] characters of [text] (stops at the first hit). */
    public fun containsAny(text: CharSequence, limit: Int = text.length): Boolean {
        var state = 0
        val end = minOf(limit, text.length)
        for (i in 0 until end) {
            state = next[state * classCount + classOf(fold(text[i]))]
            if (anyOutput[state]) return true
        }
        return false
    }

    private fun classOf(c: Char): Int = if (c.code < 128) asciiClass[c.code] else otherClass[c] ?: 0

    public companion object {
        /**
         * Case fold used for patterns and text. ASCII letters are lower-cased directly; any other character becomes
         * `lowercase(uppercase(c))`, which maps every pair a Unicode case-insensitive regex treats as equal (`K` and
         * the Kelvin sign, `s` and long s, `i` and dotted capital I, ...) to one character.
         */
        public fun fold(c: Char): Char {
            if (c.code < 128) return if (c in 'A'..'Z') c + 32 else c
            return Character.toLowerCase(Character.toUpperCase(c))
        }
    }
}
