package app.dak.classify

/**
 * Tokenises an SMS body into a privacy-safe, placeholder form suitable for sending to an opt-in
 * cloud classifier. Digits, amounts, URLs, emails, card numbers and probable names are replaced
 * with placeholders; digits are never leaked in any form.
 */
public object Masker {

    private val urlRegex = Regex("""(?i)\bhttps?://\S+|\bwww\.\S+""")
    private val emailRegex = Regex("""(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b""")
    // Card / long numeric runs (>=6 digits, possibly grouped) before the generic amount/number pass.
    private val cardRegex = Regex("""\b(?:\d[ -]?){12,19}\b""")
    private val amountRegex = Regex("""(?i)(₹|rs\.?|inr|usd|\$|aed|eur|€|£|gbp)\s*[\d,]+(\.\d+)?""")
    private val numberRegex = Regex("""\d+(\.\d+)?""")

    // Capitalised word(s) following a salutation, used as a light heuristic for a personal name.
    // Only the salutation itself is matched case-insensitively; the name still requires capitals.
    private val nameAfterRegex = Regex("""\b((?i:dear|hi|hello|hey))\b\s+([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+){0,2})""")
    private val nameToFromRegex = Regex("""\b((?i:to|from))\b\s+([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+){0,2})\b""")

    /** Masks [body], returning placeholder text with no leaked digits. */
    public fun mask(body: String): String {
        var text = body

        // Order matters: URLs/emails/cards first (they contain digits that would otherwise be
        // caught by looser passes), then names (which rely on surrounding words still being
        // present), then amounts, then any remaining bare digits.
        text = urlRegex.replace(text, "<URL>")
        text = emailRegex.replace(text, "<EMAIL>")
        text = cardRegex.replace(text, "<NUM>")

        text = replaceNames(text)

        text = amountRegex.replace(text, "<AMT>")
        text = numberRegex.replace(text, "<NUM>")

        require(text.none { it.isDigit() }) { "Masker.mask leaked a digit" }
        return text
    }

    private fun replaceNames(text: String): String {
        var result = nameAfterRegex.replace(text) { m -> "${m.groupValues[1]} <NAME>" }
        result = nameToFromRegex.replace(result) { m -> "${m.groupValues[1]} <NAME>" }
        return result
    }
}
