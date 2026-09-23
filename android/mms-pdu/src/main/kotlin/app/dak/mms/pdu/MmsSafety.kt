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

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host == "0.0.0.0" || host.startsWith("127.")) return true
        if (host == "::1" || host == "::" || host == "0:0:0:0:0:0:0:1" || host == "0:0:0:0:0:0:0:0") return true
        return false
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
