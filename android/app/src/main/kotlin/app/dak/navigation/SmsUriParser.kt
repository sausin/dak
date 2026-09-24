package app.dak.navigation

import java.io.ByteArrayOutputStream

/**
 * Pure RFC 5724 parser for `sms:` / `smsto:` / `mms:` / `mmsto:` URIs, plus the Android extras contract for
 * SENDTO / VIEW / SEND. It has no Android dependencies so it can be unit-tested on the JVM.
 *
 * Grammar (RFC 5724 §2.2): `sms:<recipient>[,<recipient>]*[?<hfield>[&<hfield>]*]`, with `hfield = name=value`.
 * Rules this parser applies:
 * - It works on the **encoded** scheme-specific part (`Uri.encodedSchemeSpecificPart`). The string is split on the
 *   first unencoded `?`, then on unencoded `&` and `,`, and only then is each piece percent-decoded, **once**. So
 *   `?body=a%26b` gives `a&b` and `?body=50%25` gives `50%`.
 * - `body` is matched case-insensitively. Other hfields are ignored. The first `body` wins.
 * - `+` is a literal plus (RFC 3986), not a space: `+91…` recipients depend on it, and `%20` is the space.
 * - Invalid percent sequences (`%zz`, a trailing `%`) are kept literally, and invalid UTF-8 becomes U+FFFD.
 *   Nothing throws.
 * - `;` is also accepted as a recipient separator, for compatibility with apps that build Windows-style lists.
 * - A leading `//` (the `smsto://123` form some apps send) is dropped.
 * - The body is capped at [MAX_BODY_CHARS] so a hostile intent cannot push a huge string through the navigation
 *   back stack (saved to a Bundle). The body is only ever used as composer text, never as markup or a route.
 */
object SmsUriParser {
    /** Longest prefilled body accepted from another app (about 60 GSM-7 segments). */
    const val MAX_BODY_CHARS: Int = 10_000

    /** Most recipients accepted from one URI. */
    const val MAX_RECIPIENTS: Int = 50

    data class Parts(val recipients: List<String>, val body: String?)

    /** Where the composer should start: recipients joined with `,` (or null) and a prefilled body (or null). */
    data class ComposeRequest(val to: String?, val body: String?)

    /**
     * Parses [encodedSsp], the encoded scheme-specific part (everything after `scheme:`). A fragment, if the caller
     * has one, should be appended as `#fragment`. RFC 5724 has no fragments, so a literal `#` is treated as data.
     */
    fun parse(encodedSsp: String?): Parts {
        val ssp = encodedSsp.orEmpty()
        val q = ssp.indexOf('?')
        val recipientPart = (if (q >= 0) ssp.substring(0, q) else ssp).trim().removePrefix("//")
        val query = if (q >= 0) ssp.substring(q + 1) else null

        val recipients = recipientPart.split(',', ';')
            .asSequence()
            .map { percentDecode(it).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_RECIPIENTS)
            .toList()

        val body = query?.split('&')
            ?.firstNotNullOfOrNull { field ->
                val eq = field.indexOf('=')
                if (eq < 0) return@firstNotNullOfOrNull null
                val name = percentDecode(field.substring(0, eq)).trim()
                if (!name.equals("body", ignoreCase = true)) null else percentDecode(field.substring(eq + 1))
            }
        return Parts(recipients, body?.let(::capBody)?.takeIf { it.isNotEmpty() })
    }

    /**
     * Combines the pieces of an incoming intent into a compose request.
     *
     * @param encodedSsp the data URI's encoded scheme-specific part when it is an sms/smsto/mms/mmsto URI, else null.
     * @param addressExtra the de facto `address` extra (SEND intents from older apps).
     * @param smsBodyExtra the de facto `sms_body` extra.
     * @param textExtra `Intent.EXTRA_TEXT`.
     *
     * Extras take precedence over the URI body, as the Android `sms_body` contract expects: `sms_body` first, then
     * `EXTRA_TEXT`, then the URI's `body`. Recipients come from the URI, falling back to `address`.
     */
    fun resolve(
        encodedSsp: String?,
        addressExtra: String? = null,
        smsBodyExtra: String? = null,
        textExtra: String? = null,
    ): ComposeRequest {
        val parts = encodedSsp?.let(::parse) ?: Parts(emptyList(), null)
        val to = parts.recipients.takeIf { it.isNotEmpty() }?.joinToString(",")
            ?: addressExtra?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                ?.take(MAX_RECIPIENTS)?.takeIf { it.isNotEmpty() }?.joinToString(",")
        val body = smsBodyExtra?.takeIf { it.isNotEmpty() }
            ?: textExtra?.takeIf { it.isNotEmpty() }
            ?: parts.body
        return ComposeRequest(to, body?.let(::capBody)?.takeIf { it.isNotEmpty() })
    }

    /**
     * Decodes `%XX` escapes once as UTF-8. `+` stays a plus. Malformed escapes are copied through literally.
     */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = StringBuilder(s.length)
        val bytes = ByteArrayOutputStream()
        var i = 0
        fun flush() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = hexValue(s[i + 1])
                val lo = hexValue(s[i + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** Cuts to [MAX_BODY_CHARS] without splitting a surrogate pair. */
    internal fun capBody(body: String): String {
        if (body.length <= MAX_BODY_CHARS) return body
        var end = MAX_BODY_CHARS
        if (Character.isHighSurrogate(body[end - 1])) end--
        return body.substring(0, end)
    }
}
