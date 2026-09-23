package app.dak.classify

import app.dak.classify.text.GatedRegex
import java.net.IDN

/**
 * A URL found in a message body, with its parsed host.
 *
 * @property raw the link as it appears in the body (always `http://`, `https://` or `www.`: other schemes such as
 *   `javascript:`, `intent:`, `content:`, `file:` or `tel:` are never extracted, so they can never become tappable).
 * @property host lower-cased host without `www.` and port, in its Unicode form; null when unparseable.
 * @property asciiHost the IDNA/punycode (`xn--…`) form of [host], or null when it cannot be converted.
 * @property hasUserInfo true for `https://brand.com@evil.example/` style links (the real host is after the `@`).
 */
public data class ExtractedLink(
    val raw: String,
    val host: String?,
    val asciiHost: String? = host,
    val hasUserInfo: Boolean = false,
) {
    /** True when the host is an internationalised domain (non-ASCII, or punycode labels): possible homograph. */
    val isIdn: Boolean
        get() = host?.any { it.code > 0x7F } == true || asciiHost?.split('.')?.any { it.startsWith("xn--") } == true
}

/** Finds URLs embedded in SMS bodies (`http(s)://…` and bare `www.…`). */
public object LinkExtractor {

    /** Links are only looked for in this many leading characters (message bodies can be huge MMS text parts). */
    public const val MAX_SCAN_CHARS: Int = 20_000

    /**
     * Links stop at whitespace and at invisible format characters (bidi overrides/isolates, zero-width), so an
     * RLO-reversed tail ("https://evil.example/‮moc.knabcfdh") cannot hide inside what looks like the link.
     * Linear-time: a single character class, no nested quantifiers.
     */
    internal val urlRegex = GatedRegex("""(?i)\b((?:https?://|www\.)[^\s­؜᠎​-‏‪-‮⁠-⁤⁦-⁯﻿]+)""")

    public fun extract(body: String): List<ExtractedLink> {
        val text = if (body.length > MAX_SCAN_CHARS) body.substring(0, MAX_SCAN_CHARS) else body
        return urlRegex.findAll(text).map { match ->
            val raw = match.groupValues[1].trimEnd('.', ',', ')', ']', '"', '\'', '!', '?', ';', ':')
            parse(raw)
        }.toList()
    }

    private fun parse(raw: String): ExtractedLink {
        val afterScheme = raw.substringAfter("://", missingDelimiterValue = raw)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore('\\')
        val hasUserInfo = authority.contains('@')
        val hostPort = authority.substringAfterLast('@')
        val hostOnly = if (hostPort.startsWith("[")) {
            hostPort.substringBefore(']') + "]"
        } else {
            hostPort.substringBefore(':')
        }
        val host = hostOnly.trimEnd('.').lowercase().removePrefix("www.").takeIf { isPlausibleHost(it) }
        val ascii = host?.let { toAscii(it) }
        return ExtractedLink(raw = raw, host = host, asciiHost = ascii, hasUserInfo = hasUserInfo)
    }

    private fun isPlausibleHost(host: String): Boolean =
        host.isNotEmpty() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() || it == '%' }

    private fun toAscii(host: String): String? = try {
        IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase()
    } catch (_: Exception) {
        null
    }
}
