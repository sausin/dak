package app.dak.classify

/**
 * Detects whether a message body contains a link (backs the index's `has:link` filter; pure, thread-safe).
 *
 * Linear scan, no backtracking regex: the previous single regex used a repeated group over domain labels, which
 * re-scanned the body quadratically and, on the JVM, recursed once per label, so a hostile 50k-char body such as
 * "a.a.a.a…" ended in a StackOverflowError (an Error, not an Exception) inside the indexer.
 */
public object LinkPresence {
    private val schemeOrWww = Regex("""(?i)\bhttps?://\S|\bwww\.\S""")

    /** Top-level labels that make a bare `name.tld` count as a link (`gov.in` / `co.in` end in `in`). */
    private val TLDS = setOf("com", "in", "org", "net", "io", "app", "ly", "me", "gl", "info", "biz", "co")

    public fun containsLink(body: String): Boolean {
        if (schemeOrWww.containsMatchIn(body)) return true
        var i = 0
        val n = body.length
        while (i < n) {
            if (!isHostChar(body[i])) {
                i++
                continue
            }
            val start = i
            while (i < n && isHostChar(body[i])) i++
            // A run must start at a word boundary, like `\b` did: not glued to a preceding letter/digit/underscore.
            val boundary = start == 0 || !(body[start - 1].isLetterOrDigit() || body[start - 1] == '_')
            if (boundary && runHasDomain(body, start, i)) return true
        }
        return false
    }

    /** True when some label after a non-empty, alphanumeric-bearing label is a known TLD (optionally `-suffixed`). */
    private fun runHasDomain(body: String, start: Int, end: Int): Boolean {
        val labels = body.substring(start, end).lowercase().split('.')
        for (k in 1 until labels.size) {
            val previous = labels[k - 1]
            if (previous.isEmpty() || previous.none { it in 'a'..'z' || it in '0'..'9' }) continue
            val label = labels[k]
            if (TLDS.any { tld -> label == tld || label.startsWith("$tld-") }) return true
        }
        return false
    }

    private fun isHostChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-'
}
