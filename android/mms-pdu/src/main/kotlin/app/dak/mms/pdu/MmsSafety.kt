package app.dak.mms.pdu

import java.net.URI

/**
 * Hard resource limits applied while decoding attacker-supplied PDUs (anyone can send a WAP push, and the
 * retrieved message comes from whatever server the notification pointed at). Exceeding a structural limit makes
 * [MmsPduDecoder.decode] return [PduError.Malformed]; cosmetic limits truncate.
 */
object MmsLimits {
    /** Largest PDU the decoder accepts. Carriers cap MMS at 300 KiB–5 MiB; anything far above is hostile. */
    const val MAX_PDU_BYTES: Int = 16 * 1024 * 1024

    /** Total body parts after nested multiparts are flattened. Real messages carry a SMIL part and a few media. */
    const val MAX_PARTS: Int = 256

    /** To + Cc + Bcc addresses kept per PDU (the rest are dropped): bounds thread creation and addr rows. */
    const val MAX_ADDRESSES: Int = 100

    /** Subject / retrieve-text / response-text are cut to this many chars (they end up in notifications). */
    const val MAX_HEADER_TEXT_CHARS: Int = 1024

    /**
     * Longest address (From / To / Cc / Bcc) kept. E.164 numbers have at most 15 digits and e-mail addresses at most
     * 254 characters; a longer value is hostile and would become a thread's canonical address. Longer To/Cc/Bcc
     * values are dropped, a longer From becomes "unknown sender".
     */
    const val MAX_ADDRESS_CHARS: Int = 256

    /**
     * Longest identifier-like header or part header kept (Transaction-ID, Message-ID, Content-Location,
     * Message-Class, part Content-ID / Content-Location / file names, content-type parameters). Longer values are
     * treated as absent: they are stored in provider columns and sent back in m-notifyresp-ind, so a multi-megabyte
     * value would make the provider insert (a Binder transaction, 1 MB) fail and the message be lost.
     */
    const val MAX_TOKEN_CHARS: Int = 1024

    /** Longest media type kept (RFC 6838 allows 127 + 1 + 127); a longer one becomes `application/octet-stream`. */
    const val MAX_MIME_TYPE_CHARS: Int = 255

    /**
     * Longest text/plain or SMIL part stored inline in the provider's `part.text` column. Carriers cap whole MMS at
     * 300 KiB–5 MiB, and the text column travels over Binder (1 MB per transaction) and through a CursorWindow
     * (2 MB per row) on every read: a larger inline text would either fail to insert (message lost, re-downloaded
     * five times) or make every later read of the conversation throw. Longer text is truncated.
     */
    const val MAX_INLINE_TEXT_CHARS: Int = 32 * 1024

    /**
     * Total inline text kept across all parts of one message: the joined body is what notifications, the index, the
     * classifiers and the conversation view (one Compose text layout) process. The longest concatenated SMS is ~39k
     * characters, so this is well above anything legitimate.
     */
    const val MAX_MESSAGE_TEXT_CHARS: Int = 64 * 1024
}

/**
 * Validation and sanitisation for PDU values that leave the codec and reach the network, the file system or the
 * UI. Pure functions, no I/O.
 */
object MmsSafety {
    /** Longest content location we hand to the platform MMS service. */
    const val MAX_CONTENT_LOCATION_CHARS: Int = 1024

    /** Longest file name produced by [safeFileName]. */
    const val MAX_FILE_NAME_CHARS: Int = 128

    /**
     * True when [contentLocation] of an m-notification-ind may be passed to `downloadMultimediaMessage`: an
     * absolute `http`/`https` URL with a host, no whitespace or control characters, not pointing at the device
     * itself (loopback / unspecified addresses), and at most [MAX_CONTENT_LOCATION_CHARS] long. Anything else
     * (`file:`, `content:`, `javascript:`, relative paths, …) is a spoofed notification and is never fetched.
     */
    fun isDownloadableContentLocation(contentLocation: String?): Boolean {
        if (contentLocation.isNullOrEmpty() || contentLocation.length > MAX_CONTENT_LOCATION_CHARS) return false
        if (contentLocation.any { it.isWhitespace() || it.isISOControl() || it.code > 0x7E }) return false
        val uri = try {
            URI(contentLocation)
        } catch (e: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (host.isEmpty()) return false
        return !isLocalHost(host)
    }

    /**
     * Loopback / unspecified hosts in every spelling an HTTP stack might resolve: `localhost` (with or without a
     * trailing dot), any `127.x` / `0.x` IPv4 literal, the non-canonical IPv4 forms that `inet_aton` accepts
     * (`2130706433`, `0x7f.1`, `0177.0.0.1`, `127.1`) — rejected outright, since no MMSC uses them — and IPv6
     * loopback, unspecified, and IPv4-mapped / IPv4-compatible forms of the above (`::ffff:127.0.0.1`).
     */
    private fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trimEnd('.')
        if (host.isEmpty()) return true
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.contains(':')) return isLocalIpv6(host)
        val labels = host.split('.')
        val numericLike = labels.all { label -> label.isNotEmpty() && (label.all { it.isDigit() } || isHexLabel(label)) }
        if (!numericLike) return false // a DNS name
        val canonical = labels.size == 4 && labels.all { it.all(Char::isDigit) && it.length <= 3 && (it == "0" || !it.startsWith("0")) && it.toInt() <= 255 }
        if (!canonical) return true // decimal / hex / octal / short-form IPv4: only ever used to disguise an address
        val first = labels[0].toInt()
        return first == 127 || first == 0
    }

    private fun isHexLabel(label: String): Boolean =
        label.length > 2 && (label.startsWith("0x") || label.startsWith("0X")) && label.substring(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun isLocalIpv6(host: String): Boolean {
        val address = host.substringBefore('%') // zone id
        val bytes = parseIpv6(address) ?: return true // unparsable literal: never fetch it
        val allZeroPrefix = (0 until 10).all { bytes[it] == 0 }
        val mapped = allZeroPrefix && bytes[10] == 0xFF && bytes[11] == 0xFF
        val compatible = allZeroPrefix && bytes[10] == 0 && bytes[11] == 0
        // ::ffff:a.b.c.d (mapped) and ::a.b.c.d (compatible, which also covers :: and ::1): judge the IPv4 part.
        if (mapped || compatible) {
            val first = bytes[12]
            return first == 127 || first == 0
        }
        return false
    }

    /** 16 octets of an IPv6 literal (with optional embedded IPv4 tail), or null when malformed. */
    private fun parseIpv6(text: String): IntArray? {
        if (text.isEmpty() || text.length > 45) return null
        var body = text
        var tail: List<Int> = emptyList()
        val lastColon = body.lastIndexOf(':')
        if (body.substring(lastColon + 1).contains('.')) {
            val v4 = body.substring(lastColon + 1).split('.')
            if (v4.size != 4 || v4.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) || it.toInt() > 255 }) return null
            tail = v4.map { it.toInt() }
            body = body.substring(0, lastColon + 1) + "0:0"
        }
        val doubleColon = body.indexOf("::")
        if (doubleColon >= 0 && body.indexOf("::", doubleColon + 1) >= 0) return null
        fun groups(s: String): List<Int>? {
            if (s.isEmpty()) return emptyList()
            return s.split(':').map { g ->
                if (g.isEmpty() || g.length > 4 || !g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                g.toInt(16)
            }
        }
        val words: List<Int> = if (doubleColon >= 0) {
            val head = groups(body.substring(0, doubleColon)) ?: return null
            val rest = groups(body.substring(doubleColon + 2)) ?: return null
            if (head.size + rest.size > 7) return null
            head + List(8 - head.size - rest.size) { 0 } + rest
        } else {
            groups(body)?.takeIf { it.size == 8 } ?: return null
        }
        val out = IntArray(16)
        for (i in 0 until 8) {
            out[2 * i] = words[i] shr 8
            out[2 * i + 1] = words[i] and 0xFF
        }
        if (tail.isNotEmpty()) for (i in 0 until 4) out[12 + i] = tail[i]
        return out
    }

    /**
     * [text] cut to at most [maxChars] characters without splitting a surrogate pair. Used for every
     * attacker-sized string that is stored or shown.
     */
    fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var end = maxChars.coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    /**
     * A display/storage-safe file name derived from an attacker-controlled part name (Content-Location,
     * Content-Disposition filename, Content-Type name): path components are dropped (`../../x` → `x`,
     * `C:\a\b.jpg` → `b.jpg`), NUL/control/bidi-override/zero-width characters removed, leading dots stripped (no
     * hidden files or `..`), and the result capped at [MAX_FILE_NAME_CHARS] keeping the extension. Returns null
     * when nothing usable remains.
     */
    fun safeFileName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val lastComponent = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString(lastComponent.length) {
            for (c in lastComponent) {
                if (c.isISOControl() || isInvisibleFormatChar(c) || c == ':' || c == '*' || c == '?' ||
                    c == '"' || c == '<' || c == '>' || c == '|'
                ) {
                    continue
                }
                append(c)
            }
        }.trim().trimStart('.').trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.length <= MAX_FILE_NAME_CHARS) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
        return cleaned.take(MAX_FILE_NAME_CHARS - ext.length) + ext
    }

    /** Bidi embeddings/overrides/isolates, zero-width characters, BOM and other invisible format characters. */
    private fun isInvisibleFormatChar(c: Char): Boolean =
        Character.getType(c) == Character.FORMAT.toInt() || c in '\u2028'..'\u2029' || c == '\uFFFC' || c == '\uFFFD'
}
