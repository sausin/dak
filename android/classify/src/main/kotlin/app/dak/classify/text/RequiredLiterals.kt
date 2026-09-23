package app.dak.classify.text

/**
 * Derives, conservatively, a set of literals at least one of which occurs in every match of a regex: if none of them
 * is in a text (compared with [AhoCorasick.fold]), the regex cannot match that text and need not run.
 *
 * Supported: literal characters and escapes, `.`, character classes, the predefined classes (`\d`, `\s`, `\w`,
 * `\p{..}`, ...), anchors and `\b`, groups (capturing, `(?:`, `(?<name>`, `(?>`, flag groups), lookarounds,
 * alternation, and all quantifiers. Anything else (`\Q..\E`, `\x`, `\u`, octal escapes, named backreferences,
 * comments / `x` or Unicode flags, a malformed pattern...) makes [of] return null, which callers treat as "always
 * run the regex". Null is also returned when no safe literal exists (e.g. `\d{4,8}` alone).
 *
 * Why it is safe: every node gets `exact` (all strings it can match, when that is a small finite set, else null) and
 * `factors` (a set such that each match of the node contains one of them, else null). A sequence's match is the
 * concatenation of its items' matches, so it contains any item's factor, and a run of consecutive exact items
 * matches one string of their cross product. An alternation's match is some branch's match, so the union of all
 * branches' factors works only if every branch has one. Optional items (min 0) contribute nothing. Zero-width items
 * (`\b`, `^`, lookarounds) consume no text, so they do not break a run.
 */
public object RequiredLiterals {

    private const val MAX_EXACT = 32
    private const val MAX_EXACT_LENGTH = 48

    /** The required literals of [pattern] (folded), or null when there is no safe set. */
    public fun of(pattern: String): Set<String>? = try {
        val node = Parser(pattern).parseAll()
        node.factors?.takeIf { set -> set.isNotEmpty() && set.none { it.isEmpty() } }
    } catch (e: Unsupported) {
        null
    }

    private class Unsupported : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** `exact`: every string the node matches (small, finite), or null. `factors`: see the object KDoc. */
    private class Node(val exact: Set<String>?, val factors: Set<String>?)

    private val EMPTY = Node(setOf(""), null)
    private val OPAQUE = Node(null, null)

    private fun lit(c: Char): Node {
        val s = AhoCorasick.fold(c).toString()
        return Node(setOf(s), setOf(s))
    }

    /** The more selective of two factor sets: longer shortest literal first, then fewer literals. */
    private fun better(a: Set<String>?, b: Set<String>?): Set<String>? {
        val va = a?.takeIf { s -> s.isNotEmpty() && s.none { it.isEmpty() } }
        val vb = b?.takeIf { s -> s.isNotEmpty() && s.none { it.isEmpty() } }
        if (va == null) return vb
        if (vb == null) return va
        val la = va.minOf { it.length }
        val lb = vb.minOf { it.length }
        return when {
            la != lb -> if (la > lb) va else vb
            else -> if (va.size <= vb.size) va else vb
        }
    }

    private fun cross(a: Set<String>, b: Set<String>): Set<String>? {
        if (a.size * b.size > MAX_EXACT) return null
        val out = LinkedHashSet<String>()
        for (x in a) for (y in b) {
            val s = x + y
            if (s.length > MAX_EXACT_LENGTH) return null
            out += s
        }
        return out
    }

    private fun sequence(items: List<Node>): Node {
        var best: Set<String>? = null
        var run: Set<String> = setOf("")
        var exact: Set<String>? = setOf("")
        for (item in items) {
            best = better(best, item.factors)
            val e = item.exact
            if (e == null) {
                best = better(best, run)
                run = setOf("")
                exact = null
            } else {
                val extended = cross(run, e)
                if (extended == null) {
                    // Too many combinations: keep what the run had so far, and restart it with this item.
                    best = better(best, run)
                    run = e.takeIf { it.size <= MAX_EXACT } ?: setOf("")
                } else {
                    run = extended
                }
                exact = exact?.let { cross(it, e) }
            }
        }
        best = better(best, run)
        return Node(exact, best)
    }

    private fun alternation(branches: List<Node>): Node {
        if (branches.size == 1) return branches[0]
        val exact = if (branches.all { it.exact != null }) {
            branches.flatMapTo(LinkedHashSet()) { it.exact!! }.takeIf { it.size <= MAX_EXACT }
        } else {
            null
        }
        val factors = if (branches.all { b -> better(null, b.factors ?: b.exact) != null }) {
            branches.flatMapTo(LinkedHashSet()) { b -> better(null, b.factors ?: b.exact)!! }
        } else {
            null
        }
        return Node(exact, factors)
    }

    private fun repeat(node: Node, min: Int, max: Int): Node {
        if (min == 0) return if (max == 0) EMPTY else OPAQUE
        val factors = better(node.factors, node.exact)
        val e = node.exact
        val exact = if (e != null && min == max && min <= 8) {
            var acc: Set<String>? = setOf("")
            repeat(min) { acc = acc?.let { cross(it, e) } }
            acc
        } else {
            null
        }
        return Node(exact, factors)
    }

    private class Parser(private val p: String) {
        private var i = 0

        fun parseAll(): Node {
            val node = parseAlternation()
            if (i != p.length) throw Unsupported()
            return node
        }

        private fun parseAlternation(): Node {
            val branches = ArrayList<Node>()
            branches += parseSequence()
            while (i < p.length && p[i] == '|') {
                i++
                branches += parseSequence()
            }
            return alternation(branches)
        }

        private fun parseSequence(): Node {
            val items = ArrayList<Node>()
            while (i < p.length && p[i] != '|' && p[i] != ')') {
                val atom = parseAtom() ?: continue
                items += parseQuantifier(atom)
            }
            return sequence(items)
        }

        /** One atom, or null for a construct that matches nothing and takes no quantifier (a flag group). */
        private fun parseAtom(): Node? {
            val c = p[i]
            return when (c) {
                '\\' -> parseEscape()
                '[' -> { skipClass(); OPAQUE }
                '.' -> { i++; OPAQUE }
                '^', '$' -> { i++; EMPTY }
                '(' -> parseGroup()
                '*', '+', '?', '{' -> throw Unsupported() // dangling quantifier
                else -> { i++; lit(c) }
            }
        }

        private fun parseEscape(): Node {
            if (i + 1 >= p.length) throw Unsupported()
            val c = p[i + 1]
            i += 2
            return when {
                c in "dDsSwWhHvVRX" -> OPAQUE
                c in "bBAzZG" -> EMPTY
                c == 'p' || c == 'P' -> {
                    if (i < p.length && p[i] == '{') {
                        val close = p.indexOf('}', i)
                        if (close < 0) throw Unsupported()
                        i = close + 1
                    } else {
                        i++
                    }
                    OPAQUE
                }
                c == 't' -> lit('\t')
                c == 'n' -> lit('\n')
                c == 'r' -> lit('\r')
                c == 'f' -> lit('\u000C')
                c == 'a' -> lit('\u0007')
                c == 'e' -> lit('\u001B')
                c in '1'..'9' -> {
                    while (i < p.length && p[i].isDigit()) i++
                    OPAQUE // backreference: consumes an unknown string
                }
                c.isLetterOrDigit() -> throw Unsupported() // \Q, \x, \u, \0, \c, \k, \N ...
                else -> lit(c) // escaped punctuation is literal
            }
        }

        private fun skipClass() {
            i++ // '['
            if (i < p.length && p[i] == '^') i++
            if (i < p.length && p[i] == ']') throw Unsupported() // ambiguous leading ']'
            var depth = 1
            while (i < p.length) {
                when (p[i]) {
                    '\\' -> {
                        if (i + 1 < p.length && p[i + 1] in "QEpP") {
                            if (p[i + 1] == 'Q' || p[i + 1] == 'E') throw Unsupported()
                        }
                        i += 2
                        continue
                    }
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            i++
                            return
                        }
                    }
                }
                i++
            }
            throw Unsupported()
        }

        private fun parseGroup(): Node? {
            i++ // '('
            if (i < p.length && p[i] == '?') {
                i++
                if (i >= p.length) throw Unsupported()
                when (p[i]) {
                    ':', '>' -> { i++; return closeGroup(parseAlternation()) }
                    '=', '!' -> { i++; closeGroup(parseAlternation()); return EMPTY }
                    '<' -> {
                        i++
                        if (i >= p.length) throw Unsupported()
                        return when {
                            p[i] == '=' || p[i] == '!' -> { i++; closeGroup(parseAlternation()); EMPTY }
                            p[i].isLetter() -> {
                                while (i < p.length && p[i].isLetterOrDigit()) i++
                                if (i >= p.length || p[i] != '>') throw Unsupported()
                                i++
                                closeGroup(parseAlternation())
                            }
                            else -> throw Unsupported()
                        }
                    }
                    else -> {
                        // Inline flags: (?i) or (?i:...). Comments and Unicode-class/case flags change what matches.
                        val start = i
                        while (i < p.length && p[i] in "idmsuxU-") i++
                        val flags = p.substring(start, i)
                        if (flags.isEmpty() || flags.any { it == 'x' || it == 'u' || it == 'U' }) throw Unsupported()
                        if (i >= p.length) throw Unsupported()
                        return when (p[i]) {
                            ')' -> { i++; null }
                            ':' -> { i++; closeGroup(parseAlternation()) }
                            else -> throw Unsupported()
                        }
                    }
                }
            }
            return closeGroup(parseAlternation())
        }

        private fun closeGroup(inner: Node): Node {
            if (i >= p.length || p[i] != ')') throw Unsupported()
            i++
            return inner
        }

        private fun parseQuantifier(atom: Node): Node {
            if (i >= p.length) return atom
            val (min, max) = when (p[i]) {
                '?' -> { i++; 0 to 1 }
                '*' -> { i++; 0 to Int.MAX_VALUE }
                '+' -> { i++; 1 to Int.MAX_VALUE }
                '{' -> parseBraces()
                else -> return atom
            }
            if (i < p.length && (p[i] == '?' || p[i] == '+')) i++ // lazy / possessive
            if (i < p.length && p[i] in "*+?{") throw Unsupported()
            return repeat(atom, min, max)
        }

        private fun parseBraces(): Pair<Int, Int> {
            val close = p.indexOf('}', i)
            if (close < 0) throw Unsupported()
            val body = p.substring(i + 1, close)
            i = close + 1
            val parts = body.split(',')
            val min = parts[0].trim().toIntOrNull() ?: throw Unsupported()
            val max = when {
                parts.size == 1 -> min
                parts.size == 2 && parts[1].isBlank() -> Int.MAX_VALUE
                parts.size == 2 -> parts[1].trim().toIntOrNull() ?: throw Unsupported()
                else -> throw Unsupported()
            }
            if (min < 0 || max < min) throw Unsupported()
            return min to max
        }
    }
}
