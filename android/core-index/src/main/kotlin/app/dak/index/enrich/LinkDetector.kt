package app.dak.index.enrich

import app.dak.classify.LinkPresence

/**
 * Detects whether a message body contains a link (backs the `has:link` filter).
 *
 * The implementation lives in `:classify` ([LinkPresence], a linear scan with no backtracking regex) so the JVM
 * indexing benchmark can exercise it; this object keeps the index's existing call sites unchanged.
 */
object LinkDetector {
    fun containsLink(body: String): Boolean = LinkPresence.containsLink(body)
}
