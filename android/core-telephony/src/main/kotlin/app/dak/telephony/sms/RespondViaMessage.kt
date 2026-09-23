package app.dak.telephony.sms

import java.io.ByteArrayOutputStream

/**
 * Parser for `sms:` / `smsto:` / `mms:` / `mmsto:` URIs (RFC 5724, plus the Android `smsto:` / `mms*:` variants) used
 * by RESPOND_VIA_MESSAGE and the SENDTO / VIEW intents. Pure Kotlin.
 *
 * Input is the **encoded** scheme-specific part (`Uri.getEncodedSchemeSpecificPart()`), never the decoded one: the
 * query is split into hfields on `&` *before* anything is percent-decoded, and each value is decoded exactly once, so
 * `%26` (`&`), `%25` (`%`), `%3F` (`?`) and `%2C` (`,`) inside a body or number survive. `+` is a literal plus (RFC
 * 3986, and phone numbers start with it), not a space.
 *
 * - Recipients: separated by `,` (RFC 5724) or `;` (older Android / iOS links); blanks dropped, duplicates removed,
 *   order kept. A leading `//` (the `sms://…` form some apps emit) is ignored.
 * - Body: the `body` hfield, matched case-insensitively; other hfields are ignored. With several `body` fields the
 *   first wins.
 */
object RespondViaMessage {
    /** Recipients from an encoded scheme-specific part, e.g. `+15551234,+15559876?body=hi`. */
    fun recipients(encodedSchemeSpecificPart: String?): List<String> {
        if (encodedSchemeSpecificPart.isNullOrBlank()) return emptyList()
        val list = encodedSchemeSpecificPart.substringBefore('?').removePrefix("//")
        return list.split(',', ';')
            .map { percentDecode(it).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** The `body` hfield of an encoded scheme-specific part, decoded once; null when there is none. */
    fun body(encodedSchemeSpecificPart: String?): String? {
        val query = encodedSchemeSpecificPart?.substringAfter('?', "")?.takeIf { it.isNotEmpty() } ?: return null
        for (field in query.split('&')) {
            val eq = field.indexOf('=')
            val name = if (eq >= 0) field.substring(0, eq) else field
            if (percentDecode(name).equals("body", ignoreCase = true)) {
                return if (eq >= 0) percentDecode(field.substring(eq + 1)) else ""
            }
        }
        return null
    }

    /**
     * RFC 3986 percent-decoding as UTF-8, applied once. Malformed escapes (`%`, `%4`, `%zz`) are kept literally
     * rather than rejected, and invalid UTF-8 becomes U+FFFD, so a sloppy link still yields its text.
     */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            // Literal characters (including a surrogate pair, kept together) as UTF-8.
            val end = if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) i + 2 else i + 1
            out.write(value.substring(i, end).toByteArray(Charsets.UTF_8))
            i = end
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
