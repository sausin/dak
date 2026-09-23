package app.dak.classify.scam

import app.dak.classify.text.KeywordPrefilter

/**
 * Bank / wallet name families used to tell which institution a message claims to be from, and whether a sender
 * header or a template-bundle brand belongs to the same one. Covers the institutions scammers impersonate most in
 * India, including some the template bundle does not list yet (a claim of those is still recognised, and a sender
 * header for them is then "not in the bank table").
 *
 * Every pattern is a plain alternation with no nested quantifiers (linear time on any input).
 */
internal object BankNames {

    internal class Family(
        val id: String,
        val displayName: String,
        pattern: String,
        /** Uppercase substrings that mark a sender header as belonging to (or imitating) this family. */
        val headerTokens: List<String>,
    ) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /** Order matters: State Bank of India is checked before Bank of India. */
    val families: List<Family> = listOf(
        Family("sbi", "State Bank of India", """\bsbi\b|state\s?bank""", listOf("SBI")),
        Family("hdfc", "HDFC Bank", """\bhdfc""", listOf("HDFC")),
        Family("icici", "ICICI Bank", """\bicici""", listOf("ICICI")),
        Family("axis", "Axis Bank", """\baxis\b""", listOf("AXIS")),
        Family("kotak", "Kotak Mahindra Bank", """\bkotak\b""", listOf("KOTAK")),
        Family("yes", "Yes Bank", """\byes\s?bank\b""", listOf("YESB")),
        Family("idfc", "IDFC First Bank", """\bidfc\b""", listOf("IDFC")),
        Family("indusind", "IndusInd Bank", """\bindusind\b""", listOf("INDUS")),
        Family("pnb", "Punjab National Bank", """\bpnb\b|punjab\s+national""", listOf("PNB", "PUNB")),
        Family("bob", "Bank of Baroda", """bank\s+of\s+baroda|\bbob\b""", listOf("BOB", "BARODA")),
        Family("canara", "Canara Bank", """\bcanara\b""", listOf("CANB", "CANARA")),
        Family("union", "Union Bank of India", """\bunion\s+bank\b""", listOf("UNIONB", "UBOI")),
        Family("boi", "Bank of India", """(?<!state\s)bank\s+of\s+india\b""", listOf("BOIIND", "BKOFIN")),
        Family("central", "Central Bank of India", """central\s+bank""", listOf("CBOI", "CENTBK")),
        Family("indian", "Indian Bank", """\bindian\s+bank\b""", listOf("INDIANB")),
        Family("federal", "Federal Bank", """\bfederal\s+bank\b""", listOf("FEDBNK", "FEDERAL")),
        Family("rbl", "RBL Bank", """\brbl\b""", listOf("RBL")),
        Family("idbi", "IDBI Bank", """\bidbi\b""", listOf("IDBI")),
        Family("au", "AU Small Finance Bank", """\bau\s+(?:small\s+finance|bank)\b""", listOf("AUBANK")),
        Family("paytm", "Paytm", """\bpaytm\b""", listOf("PAYTM", "PYTM")),
        Family("phonepe", "PhonePe", """\bphone\s?pe\b""", listOf("PHONEPE", "PHNPE")),
        Family("gpay", "Google Pay", """google\s?pay|\bgpay\b""", listOf("GPAY", "GOOGPAY")),
        Family("amazonpay", "Amazon Pay", """amazon\s?pay""", listOf("AMZNPY", "AMAZONPAY")),
        Family("bhim", "BHIM UPI", """\bbhim\b""", listOf("BHIM")),
    )

    private val byId: Map<String, Family> = families.associateBy { it.id }

    fun byId(id: String): Family? = byId[id]

    /** The first family named in [text] (a message body, a bundle brand or a ledger institution), or null. */
    fun familyIn(text: String): Family? {
        val hits = prefilter.scan(text)
        for (i in families.indices) {
            if (prefilter.mayMatch(i, hits) && families[i].regex.containsMatchIn(text)) return families[i]
        }
        return null
    }

    /** One keyword pass instead of trying every family's regex at every position (same result, see KeywordPrefilter). */
    private val prefilter = KeywordPrefilter(families.map { it.regex.pattern })

    /** The family whose header token appears in the (uppercased) sender [header], or null. */
    fun familyOfHeader(header: String): Family? {
        val upper = header.uppercase()
        return families.firstOrNull { f -> f.headerTokens.any { upper.contains(it) } }
    }
}
