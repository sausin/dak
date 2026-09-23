package app.dak.telephony.sms

/** Parsing for `sms:` / `smsto:` / `mms:` / `mmsto:` URIs (RESPOND_VIA_MESSAGE and share intents). */
internal object RespondViaMessage {
    /**
     * Recipients from a URI's (already percent-decoded) scheme-specific part, e.g. `+15551234,+15559876?body=hi`.
     * Separators `,` and `;` are both accepted; blanks are dropped; order is kept and duplicates removed.
     */
    fun recipients(schemeSpecificPart: String?): List<String> {
        if (schemeSpecificPart.isNullOrBlank()) return emptyList()
        val withoutQuery = schemeSpecificPart.substringBefore('?').removePrefix("//")
        return withoutQuery.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** The `body=` query parameter of an sms URI, if any (already percent-decoded by the platform). */
    fun body(schemeSpecificPart: String?): String? {
        val query = schemeSpecificPart?.substringAfter('?', "")?.takeIf { it.isNotEmpty() } ?: return null
        return query.split('&').firstOrNull { it.startsWith("body=") }?.removePrefix("body=")
    }
}
