package app.dak.classify

import app.dak.core.model.Category
import app.dak.core.model.Classification

/**
 * Template-hash result cache for [ClassifierPipeline]: bank alerts, OTPs and promotions are a few hundred templates
 * filled in with amounts, dates, codes and masked accounts, so the *template-dependent* part of classification (which
 * rule fires, its category / confidence / labels, and the on-device model's scores) is computed once per template
 * and reused. Per-message parts (OTP code, the unknown-sender-link label, the contact / local-mobile boost, the
 * cloud stage) are always computed from the actual message.
 *
 * ### Why a hit gives exactly the uncached result
 * The key is the body with every ASCII digit replaced by `0` (same length, same positions), plus everything else the
 * template and model stages read: the region, the rule group of the sender, the sender's table entry and its DLT
 * traffic type, and verbatim every digit-bearing token the model has a weight for.
 * - Template regexes: accepted only when no pattern contains a digit outside a `{m,n}` quantifier and none uses a
 *   named backreference ([isDigitBlind]). Such a regex cannot tell one ASCII digit from another (`\d`, `\w`, `\b`,
 *   classes and properties treat them alike), and the masked body keeps every other character and every length.
 * - Model: tokens without an ASCII digit are unchanged; digit-bearing tokens are either in the key verbatim (known
 *   to the model) or unknown, and every unknown token adds the same per-class term. The scores are therefore the same
 *   doubles summed in the same order.
 * Bodies longer than [MAX_KEY_CHARS] are not cached (they are rare and would make the cache heavy).
 *
 * Bounded LRU ([capacity] entries), synchronized; the per-entry model scores are filled lazily and idempotently.
 */
internal class TemplateCache(private val capacity: Int) {

    /** What is cached per template. [modelScores] is filled the first time a message of this template needs stage 2. */
    class Entry(val template: Classification?) {
        @Volatile
        var modelScores: Map<Category, Float>? = null
    }

    private val map = object : LinkedHashMap<String, Entry>(capacity * 4 / 3 + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > capacity
    }

    @Volatile var hits: Long = 0
        private set
    @Volatile var misses: Long = 0
        private set

    fun get(key: String): Entry? = synchronized(map) {
        map[key].also { if (it != null) hits++ else misses++ }
    }

    fun put(key: String, entry: Entry): Unit = synchronized(map) { map[key] = entry }

    fun size(): Int = synchronized(map) { map.size }

    companion object {
        const val MAX_KEY_CHARS = 640

        /**
         * The cache key, or null when [body] is not cacheable. [context] identifies the non-body inputs (region, rule
         * group, sender entry, traffic type); [knownToken] is the model's vocabulary test.
         */
        fun keyOf(context: String, body: String, knownToken: (String) -> Boolean): String? {
            if (body.length > MAX_KEY_CHARS) return null
            val masked = CharArray(body.length)
            var suffix: StringBuilder? = null
            val token = StringBuilder()
            var tokenHasAsciiDigit = false
            fun endToken() {
                if (tokenHasAsciiDigit && token.isNotEmpty()) {
                    val t = token.toString()
                    if (knownToken(t)) (suffix ?: StringBuilder().also { suffix = it }).append('\u0000').append(t)
                }
                token.setLength(0)
                tokenHasAsciiDigit = false
            }
            for (i in body.indices) {
                val ch = body[i]
                masked[i] = if (ch in '0'..'9') '0' else ch
                // Token boundaries exactly as Tokenizer.tokenize draws them.
                when {
                    Character.isLetter(ch) || Character.isDigit(ch) || Tokenizer.isCombiningMark(ch) -> {
                        token.append(Character.toLowerCase(ch))
                        if (ch in '0'..'9') tokenHasAsciiDigit = true
                    }
                    Tokenizer.isJoiner(ch) -> Unit
                    else -> endToken()
                }
            }
            endToken()
            return buildString(context.length + body.length + 16 + (suffix?.length ?: 0)) {
                append(context).append('\u0001').append(body.length).append(':')
                append(masked)
                suffix?.let { append(it) }
            }
        }

        /**
         * True when no rule pattern can distinguish one ASCII digit from another: no digit outside `{m,n}` quantifier
         * braces (literal digits, `[0-9]` ranges, `\1` backreferences, `\x30` escapes all contain one) and no named
         * backreference (`\k<name>`).
         */
        fun isDigitBlind(patterns: List<String>): Boolean = patterns.all { p ->
            if (p.contains("\\k<")) return@all false
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '\\' -> {
                        if (i + 1 < p.length && p[i + 1] in '0'..'9') return@all false
                        i += 2
                        continue
                    }
                    c == '{' -> {
                        val close = p.indexOf('}', i)
                        val inner = if (close > i) p.substring(i + 1, close) else null
                        if (inner != null && QUANTIFIER.matches(inner)) {
                            i = close + 1
                            continue
                        }
                    }
                    c in '0'..'9' -> return@all false
                }
                i++
            }
            true
        }

        private val QUANTIFIER = Regex("""\d+(,\d*)?""")
    }
}
