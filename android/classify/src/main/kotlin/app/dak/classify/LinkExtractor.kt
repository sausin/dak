package app.dak.classify

import app.dak.classify.text.GatedRegex
import app.dak.classify.unicode.Uts46

/**
 * A URL found in a message body, with its parsed host.
 *
 * @property raw the link as it appears in the body: `http://…`, `https://…`, `www.…` or a scheme-less `name.tld/…`
 *   (see [LinkExtractor]). Other schemes such as `javascript:`, `intent:`, `content:`, `file:` or `tel:` are never
 *   extracted, so they can never become tappable.
 * @property host lower-cased host without `www.` and port, as written (Unicode, or `xn--` labels); null when unparseable.
 * @property asciiHost the host a browser resolves: UTS #46 ToASCII of [host] with the WHATWG URL flags (nontransitional,
 *   so `faß.de` is `xn--fa-hia.de`; see [Uts46]), or null when a browser would refuse it (invalid label or Punycode,
 *   Bidi or CONTEXTJ violation, a character that maps to a forbidden host code point).
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
        get() = host?.any { it.code > 0x7F } == true ||
            host?.split('.')?.any { it.startsWith("xn--") } == true ||
            asciiHost?.split('.')?.any { it.startsWith("xn--") } == true

    /** The Unicode form of [asciiHost] (UTS #46 ToUnicode: mapped, NFC, `xn--` labels decoded), or null. */
    val unicodeHost: String?
        get() = asciiHost?.let { ascii -> Uts46.toUnicode(ascii).takeIf { it.ok }?.value }

    /** True when [raw] has an explicit `http://` / `https://` scheme. */
    val hasScheme: Boolean
        get() = raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)

    /** What to open: [raw] when it has a scheme, else `https://` + [raw] (`www.…` and scheme-less links). */
    val url: String
        get() = if (hasScheme) raw else "https://$raw"
}

/**
 * Finds URLs embedded in SMS bodies: `http(s)://…`, bare `www.…`, and scheme-less `name.tld[/path]` links
 * (`bit.ly/x`, `fb.example.in/ffb63`), which Indian senders use constantly to save characters.
 *
 * Scheme-less links are found by a linear scan (no regex) that is deliberately strict, because a false link is
 * tappable text and triggers the unknown-sender-link warning:
 * - the host is ASCII labels (`[a-z0-9-]`, not starting or ending with `-`), at least two of them, and the last one is
 *   a known top-level domain ([STRONG_TLDS]; the English-word-like ones in [WORDLIKE_TLDS] only count with a path, a
 *   third label, or a hyphen or digit in the name: `wa.me/…` is a link, `ok.so` is not);
 * - it starts at a word boundary and not right after `@`, `/`, `.`, `_` and similar (the domain of an e-mail
 *   address, or part of another token), and is not followed by `@` (the local part of an e-mail address) or a letter;
 * - no label after the first is Title-case ("Done.In case of…", a missing space after a full stop), and the name
 *   before the TLD has a letter and at least two characters unless a path follows (`t.co/x`);
 * so "Rs.500", "A/c.No", "1.5GB", "10.30am", "B.Com", "Pvt.Ltd", "e.g." and "name@mail.com" are never links. E-mail
 *   addresses are left to the entity extractor, like before.
 */
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
        val schemed = urlRegex.findAll(text).map { it.groups[1]!!.range }.toList()
        val spans = ArrayList<IntRange>(schemed)
        BareLinks.scan(text, schemed) { start, end -> spans += start until end; true }
        spans.sortBy { it.first }
        return spans.map { range ->
            val raw = text.substring(range.first, range.last + 1).trimEnd(*TRAILING)
            parse(raw)
        }
    }

    private val TRAILING = charArrayOf('.', ',', ')', ']', '"', '\'', '!', '?', ';', ':')

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
        val ascii = host?.let { if (it.startsWith("[")) it else toAscii(it) } // an IPv6 literal needs no IDNA
        return ExtractedLink(raw = raw, host = host, asciiHost = ascii, hasUserInfo = hasUserInfo)
    }

    private fun isPlausibleHost(host: String): Boolean =
        host.isNotEmpty() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() || it == '%' }

    /**
     * UTS #46 ToASCII as the WHATWG URL standard applies it to a host ("domain to ASCII", beStrict=false), then its
     * forbidden-domain-code-point check: what the browser the link opens in would resolve, or null when it would refuse
     * the URL.
     */
    internal fun toAscii(host: String): String? {
        val result = Uts46.toAscii(host, Uts46.BROWSER)
        if (!result.ok) return null
        val ascii = result.value
        if (ascii.isEmpty() || ascii.any { isForbiddenDomainChar(it) }) return null
        return ascii
    }

    /** WHATWG URL "forbidden domain code point" (C0 controls, space, `#%/:<>?@[\]^|`, DEL). */
    private fun isForbiddenDomainChar(c: Char): Boolean =
        c.code <= 0x20 || c.code == 0x7F || c in "#%/:<>?@[\\]^|"

    /**
     * Top-level domains that make `name.tld` a link on their own: the generic ones, India's, the ones SMS phishing
     * and URL shorteners favour, and common country codes that are not English words.
     */
    internal val STRONG_TLDS: Set<String> = setOf(
        "com", "net", "org", "info", "biz", "edu", "gov", "mil", "int", "in", "co", "io", "ly", "gl", "cc", "app", "dev",
        "xyz", "top", "site", "online", "store", "shop", "tech", "club", "buzz", "icu", "vip", "tk", "cf", "gq", "pw",
        "ws", "cloud", "space", "website", "mobi", "asia", "ai", "tv", "gg", "uk", "ca", "au", "nz", "sg", "ae", "sa",
        "qa", "fr", "nl", "ru", "cn", "jp", "kr", "pk", "bd", "lk", "np", "ph", "ng", "ke", "za", "br", "mx", "ch", "pl",
        "eu", "cyou", "cfd", "sbs", "vn",
    )

    /**
     * Top-level domains that are also English or Hinglish words or common abbreviations ("Order.ID", "contact.us",
     * "ho.ga", "500.ml"): they need more evidence (see above).
     */
    internal val WORDLIKE_TLDS: Set<String> = setOf(
        "me", "to", "it", "is", "at", "be", "so", "my", "am", "go", "do", "no", "us", "id", "de", "se", "es", "ga", "ml",
        "fun", "live", "today", "click", "loan", "work", "win", "men", "pro", "link", "one", "page", "run", "life",
        "world", "bet", "fyi", "ink", "rest", "bond", "lat",
    )

    /** The linear scanner for scheme-less links (see [LinkExtractor]). */
    internal object BareLinks {

        private fun isHostChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-'

        /** Characters that glue a host-looking run to the token before it (e-mail domains, paths, query strings). */
        private fun gluedBefore(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_' || c == '@' || c == '/' || c == '\\' || c == '%' || c == '=' || c == '&' ||
                c == '#' || c == '~' || c == '.' || c == '-'

        /** A link's path runs to the next whitespace or invisible format character, like [urlRegex]. */
        private fun isPathChar(c: Char): Boolean =
            !c.isWhitespace() && c != '­' && c != '؜' && c != '᠎' && c !in '​'..'‏' &&
                c !in '‪'..'‮' && c !in '⁠'..'⁤' && c !in '⁦'..'⁯' && c != '﻿'

        /**
         * Calls [onLink] with the `[start, end)` range of every scheme-less link in [text] that does not overlap
         * [taken] (links already found with a scheme or `www.`, in order), in order; stops when [onLink] returns
         * false. Linear: every character is visited a bounded number of times.
         */
        fun scan(text: String, taken: List<IntRange>, onLink: (start: Int, end: Int) -> Boolean) {
            val n = text.length
            var i = 0
            var t = 0 // first span of [taken] that may still contain a later position
            while (i < n) {
                if (!isHostChar(text[i])) {
                    i++
                    continue
                }
                val start = i
                while (i < n && isHostChar(text[i])) i++
                var hostEnd = i
                // Trailing dots / hyphens are punctuation ("Visit example.com.").
                while (hostEnd > start && (text[hostEnd - 1] == '.' || text[hostEnd - 1] == '-')) hostEnd--
                if (hostEnd <= start) continue
                if (start > 0 && gluedBefore(text[start - 1])) continue
                while (t < taken.size && taken[t].last < start) t++
                if (t < taken.size && start >= taken[t].first) continue
                val next = if (hostEnd < n) text[hostEnd] else ' '
                if (hostEnd == i) {
                    // The run ended on a non-host character: a letter (non-ASCII), '_' or '@' glues it to a word.
                    if (next == '@' || next == '_' || next.isLetterOrDigit()) continue
                }
                val hasPath = hostEnd == i && (next == '/' || next == '?' || next == '#' ||
                    (next == ':' && hostEnd + 1 < n && text[hostEnd + 1] in '0'..'9'))
                if (!isLinkHost(text, start, hostEnd, hasPath)) continue
                var end = hostEnd
                if (hasPath) {
                    while (end < n && isPathChar(text[end])) end++
                    i = maxOf(i, end)
                }
                if (!onLink(start, end)) return
            }
        }

        private fun isLinkHost(text: String, start: Int, end: Int, hasPath: Boolean): Boolean {
            if (end - start > 253) return false
            val host = text.substring(start, end)
            if (host.startsWith("www.", ignoreCase = true)) return false // the scheme/www regex owns these
            val labels = host.split('.')
            if (labels.size < 2) return false
            for ((index, label) in labels.withIndex()) {
                if (label.isEmpty() || label.length > 63 || label.first() == '-' || label.last() == '-') return false
                // "Done.In case…": a Title-case label after a dot is a new sentence, not a domain.
                if (index > 0 && label.length >= 2 && label[0].isUpperCase() && label[1].isLowerCase()) return false
            }
            val tld = labels.last().lowercase()
            val name = labels[labels.size - 2]
            if (!hasPath && (name.length < 2 || name.none { it.isLetter() })) return false
            return when (tld) {
                in STRONG_TLDS -> true
                in WORDLIKE_TLDS -> hasPath || labels.size >= 3 || name.any { it == '-' || it.isDigit() }
                else -> false
            }
        }
    }
}
