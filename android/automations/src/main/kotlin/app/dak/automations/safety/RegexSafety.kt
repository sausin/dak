package app.dak.automations.safety

/**
 * Static ReDoS screen for user-written automation regexes, which run against sender-controlled message bodies.
 *
 * `java.util.regex` (and Android's ICU engine behind it) backtracks, and on Android it cannot be interrupted or
 * time-limited from Kotlin, so a pattern with exponential backtracking would pin a CPU core for as long as an
 * attacker's crafted message keeps it busy. Patterns are therefore rejected up front when they contain the
 * constructs that cause exponential blow-up:
 *
 * - a repeated group (`*`, `+`, `{n,}`, `{n,m}` with m > 1) whose body contains a backtracking choice (a
 *   variable quantifier or an alternation) — `(a+)+`, `(a*)*`, `(a|aa)+`, `(\w+\s?)*` — unless the outer repeat
 *   is possessive (`++`, `*+`) or the group is atomic (`(?>…)`), which cannot backtrack. Fixed repeats inside are
 *   fine: `(\d{3}-)+`;
 * - backreferences (`\1`, `\k<name>`), which make matching NP-hard;
 * - patterns longer than [MAX_PATTERN_LENGTH].
 *
 * Polynomial cases (`.*a.*b` on long input) remain possible; they are bounded by
 * [app.dak.automations.RuleEngine.MAX_REGEX_INPUT_LENGTH].
 */
public object RegexSafety {
    public const val MAX_PATTERN_LENGTH: Int = 500

    /** Returns null when [pattern] is acceptable, otherwise a short human-readable reason. */
    public fun problem(pattern: String): String? {
        if (pattern.length > MAX_PATTERN_LENGTH) return "pattern longer than $MAX_PATTERN_LENGTH characters"
        return try {
            Scanner(pattern).scan()
        } catch (e: IndexOutOfBoundsException) {
            null // malformed pattern: Pattern.compile reports it with a better message
        }
    }

    /** True when [pattern] passes [problem]. */
    public fun isSafe(pattern: String): Boolean = problem(pattern) == null

    private class Group(val atomic: Boolean) {
        /** Contains a quantifier that gives the engine a choice (`*`, `+`, `?`, `{n,m}` with m > n). */
        var hasChoice = false
        var hasAlternation = false
    }

    private class Scanner(private val p: String) {
        private var i = 0
        private val stack = ArrayList<Group>().apply { add(Group(atomic = false)) }

        fun scan(): String? {
            while (i < p.length) {
                when (p[i]) {
                    '\\' -> {
                        val next = p.getOrNull(i + 1) ?: return null
                        if (next in '1'..'9' || next == 'k') return "backreferences are not allowed"
                        if (next == 'Q') {
                            val end = p.indexOf("\\E", i + 2)
                            i = if (end < 0) p.length else end + 2
                        } else {
                            i += 2
                        }
                        quantifierAfterAtom(null)?.let { return it }
                    }
                    '[' -> {
                        skipClass()
                        quantifierAfterAtom(null)?.let { return it }
                    }
                    '(' -> openGroup()
                    ')' -> {
                        i++
                        val group = if (stack.size > 1) stack.removeAt(stack.size - 1) else Group(atomic = false)
                        quantifierAfterAtom(group)?.let { return it }
                    }
                    '|' -> {
                        stack.last().hasAlternation = true
                        i++
                    }
                    else -> {
                        i++
                        quantifierAfterAtom(null)?.let { return it }
                    }
                }
            }
            return null
        }

        private fun openGroup() {
            i++ // '('
            var atomic = false
            if (p.getOrNull(i) == '?') {
                val kind = p.getOrNull(i + 1)
                when {
                    kind == '>' -> { atomic = true; i += 2 }
                    kind == ':' || kind == '=' || kind == '!' -> i += 2
                    kind == '<' && (p.getOrNull(i + 2) == '=' || p.getOrNull(i + 2) == '!') -> i += 3
                    kind == '<' -> { // named group (?<name>
                        val close = p.indexOf('>', i)
                        i = if (close < 0) p.length else close + 1
                    }
                    else -> { // inline flags (?i) or (?i:...)
                        while (i < p.length && p[i] != ')' && p[i] != ':') i++
                        if (p.getOrNull(i) == ')') { i++; return } // flags only: not a group
                        i++ // ':'
                    }
                }
            }
            stack.add(Group(atomic))
        }

        /** Skips a character class, including nested classes and escapes; `]` first in the class is literal. */
        private fun skipClass() {
            i++ // '['
            if (p.getOrNull(i) == '^') i++
            if (p.getOrNull(i) == ']') i++
            var depth = 1
            while (i < p.length && depth > 0) {
                when (p[i]) {
                    '\\' -> i++
                    '[' -> depth++
                    ']' -> depth--
                }
                i++
            }
        }

        /**
         * Parses an optional quantifier after an atom. [group] is the group that just closed (null for a plain
         * atom). Returns a reason when the quantified group is an exponential-backtracking hazard.
         */
        private fun quantifierAfterAtom(group: Group?): String? {
            val repeats: Boolean
            val choice: Boolean
            when (p.getOrNull(i)) {
                '*', '+' -> { repeats = true; choice = true; i++ }
                '?' -> { repeats = false; choice = true; i++ }
                '{' -> {
                    val close = p.indexOf('}', i)
                    if (close < 0) { i++; return propagate(group, false) }
                    val body = p.substring(i + 1, close)
                    val min = body.substringBefore(',').trim().toIntOrNull() ?: 0
                    val max = if (body.contains(',')) body.substringAfter(',').trim().toIntOrNull() ?: Int.MAX_VALUE else min
                    repeats = max > 1
                    choice = max > min
                    i = close + 1
                }
                else -> return propagate(group, false)
            }
            var possessive = false
            when (p.getOrNull(i)) {
                '?' -> i++
                '+' -> { possessive = true; i++ }
            }
            if (repeats && group != null && !possessive && !group.atomic && (group.hasChoice || group.hasAlternation)) {
                return "nested repetition can take exponential time"
            }
            return propagate(group, choice && !possessive)
        }

        private fun propagate(group: Group?, choice: Boolean): String? {
            val parent = stack.last()
            if (choice) parent.hasChoice = true
            if (group != null && !group.atomic && (group.hasChoice || group.hasAlternation)) parent.hasChoice = true
            return null
        }
    }
}
