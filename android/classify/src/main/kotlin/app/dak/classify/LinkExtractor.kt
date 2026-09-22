package app.dak.classify

import java.net.URI

/** A URL found in a message body, with its parsed host. */
public data class ExtractedLink(
    val raw: String,
    val host: String?,
)

/** Finds URLs embedded in SMS bodies (`http(s)://…` and bare `www.…`). */
public object LinkExtractor {

    private val urlRegex = Regex("""(?i)\b((?:https?://|www\.)[^\s]+)""")

    public fun extract(body: String): List<ExtractedLink> =
        urlRegex.findAll(body).map { match ->
            val raw = match.groupValues[1].trimEnd('.', ',', ')', ']', '"', '\'')
            ExtractedLink(raw, hostOf(raw))
        }.toList()

    private fun hostOf(raw: String): String? {
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        return try {
            URI(normalized).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }
}
