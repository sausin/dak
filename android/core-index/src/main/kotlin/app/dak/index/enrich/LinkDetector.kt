package app.dak.index.enrich

/** Detects whether a message body contains a link (backs the `has:link` filter). */
object LinkDetector {
    private val link = Regex(
        """(?i)(?:\bhttps?://\S+|\bwww\.\S+|\b[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:com|in|org|net|io|app|ly|me|gl|info|biz|co|gov\.in|co\.in)(?:/\S*)?(?![a-z0-9]))""",
    )

    fun containsLink(body: String): Boolean = link.containsMatchIn(body)
}
