package app.dak.classify

import app.dak.classify.text.GatedRegex

/**
 * Detects whether a message body contains a link (backs the index's `has:link` filter; pure, thread-safe).
 *
 * Same notion of a link as [LinkExtractor] (`http(s)://`, `www.` and scheme-less `name.tld[/path]`), over the whole
 * body. Linear scan, no backtracking regex: an earlier regex used a repeated group over domain labels, which
 * re-scanned the body quadratically and, on the JVM, recursed once per label, so a hostile 50k-char body such as
 * "a.a.a.a…" ended in a StackOverflowError (an Error, not an Exception) inside the indexer.
 */
public object LinkPresence {
    internal val schemeOrWww = GatedRegex("""(?i)\bhttps?://\S|\bwww\.\S""")

    public fun containsLink(body: String): Boolean {
        if (schemeOrWww.containsMatchIn(body)) return true
        var found = false
        LinkExtractor.BareLinks.scan(body, emptyList()) { _, _ -> found = true; false }
        return found
    }
}
