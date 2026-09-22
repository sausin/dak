package app.dak.classify

/** Why a link was flagged. */
public enum class LinkRisk {
    /** Host matches (or is a subdomain of) a bundled official domain: safe. */
    OFFICIAL,
    /** Host is a known URL shortener: destination is hidden, treat with caution. */
    SHORTENED,
    /** Host closely resembles an official domain (edit distance / homoglyph / brand-in-subdomain). */
    LOOKALIKE,
    /** Suspicious top-level domain commonly abused for phishing. */
    SUSPICIOUS_TLD,
    /** Nothing notable found. */
    UNKNOWN,
}

public data class LinkVerdict(val link: ExtractedLink, val risk: LinkRisk, val matchedBrand: String? = null)

/**
 * Flags lookalike domains against a bundled list of official Indian bank, government and courier
 * domains, plus a bundled list of URL shorteners.
 */
public class LookalikeDomainChecker(
    officialDomains: Map<String, String> = defaultOfficialDomains(),
    private val shorteners: Set<String> = defaultShorteners(),
    private val suspiciousTlds: Set<String> = defaultSuspiciousTlds(),
) {
    // brand -> domain, indexed the other way for lookup.
    private val domainToBrand: Map<String, String> = officialDomains
    private val brandNames: List<Pair<String, String>> = officialDomains.entries.map { it.value.lowercase() to it.key }

    public fun check(link: ExtractedLink): LinkVerdict {
        val host = link.host ?: return LinkVerdict(link, LinkRisk.UNKNOWN)

        if (shorteners.any { host == it || host.endsWith(".$it") }) {
            return LinkVerdict(link, LinkRisk.SHORTENED)
        }
        if (isOfficial(host)) {
            return LinkVerdict(link, LinkRisk.OFFICIAL, matchedBrand = domainToBrand[host])
        }

        lookalikeBrand(host)?.let { brand ->
            return LinkVerdict(link, LinkRisk.LOOKALIKE, matchedBrand = brand)
        }

        val tld = host.substringAfterLast('.', missingDelimiterValue = "")
        if (tld.isNotEmpty() && tld in suspiciousTlds) {
            return LinkVerdict(link, LinkRisk.SUSPICIOUS_TLD)
        }

        return LinkVerdict(link, LinkRisk.UNKNOWN)
    }

    private fun isOfficial(host: String): Boolean =
        domainToBrand.containsKey(host) || domainToBrand.keys.any { host.endsWith(".$it") }

    /**
     * A host resembles an official brand if the brand name appears verbatim as a hyphenated
     * subdomain/label segment (e.g. `hdfc-bank-kyc.xyz`), or if the host's registrable label is
     * within edit distance 2 of an official domain's label (a homoglyph/typo-squat).
     */
    private fun lookalikeBrand(host: String): String? {
        val labels = host.split('.', '-')
        for ((brandLower, brandName) in brandNames) {
            val brandWords = brandLower.split(Regex("[\\s.]+")).filter { it.length >= 3 }
            if (brandWords.isNotEmpty() && brandWords.all { w -> labels.any { it.contains(w) } }) {
                // Brand words present, but host is not itself the official domain: lookalike.
                return brandName
            }
        }
        val registrable = host.split('.').let { parts ->
            if (parts.size >= 2) parts[parts.size - 2] else host
        }
        for (officialDomain in domainToBrand.keys) {
            val officialLabel = officialDomain.split('.').let { if (it.size >= 2) it[it.size - 2] else officialDomain }
            if (officialLabel.length >= 4 && levenshtein(registrable, officialLabel) in 1..2) {
                return domainToBrand.getValue(officialDomain)
            }
        }
        return null
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    public companion object {
        /** Bundled official domain -> brand name. Not exhaustive; a starting set for v1. */
        public fun defaultOfficialDomains(): Map<String, String> = mapOf(
            "hdfcbank.com" to "HDFC Bank",
            "icicibank.com" to "ICICI Bank",
            "onlinesbi.sbi" to "State Bank of India",
            "sbi.co.in" to "State Bank of India",
            "axisbank.com" to "Axis Bank",
            "kotak.com" to "Kotak Mahindra Bank",
            "yesbank.in" to "Yes Bank",
            "idfcfirstbank.com" to "IDFC First Bank",
            "indusind.com" to "IndusInd Bank",
            "pnbindia.in" to "Punjab National Bank",
            "bankofbaroda.in" to "Bank of Baroda",
            "canarabank.com" to "Canara Bank",
            "paytm.com" to "Paytm",
            "phonepe.com" to "PhonePe",
            "amazon.in" to "Amazon",
            "amazon.com" to "Amazon",
            "flipkart.com" to "Flipkart",
            "swiggy.com" to "Swiggy",
            "zomato.com" to "Zomato",
            "delhivery.com" to "Delhivery",
            "bluedart.com" to "BlueDart",
            "irctc.co.in" to "IRCTC",
            "goindigo.in" to "IndiGo",
            "jio.com" to "Jio",
            "airtel.in" to "Airtel",
            "myvi.in" to "Vi",
            "uidai.gov.in" to "UIDAI (Aadhaar)",
            "incometax.gov.in" to "Income Tax Department",
            "india.gov.in" to "Government of India",
            "indiapost.gov.in" to "India Post",
        )

        public fun defaultShorteners(): Set<String> = setOf(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly",
            "rebrand.ly", "cutt.ly", "shorte.st", "tiny.cc", "rb.gy", "bitly.com",
        )

        public fun defaultSuspiciousTlds(): Set<String> = setOf(
            "xyz", "tk", "top", "click", "info", "loan", "work", "gq", "cf", "ml", "buzz",
        )
    }
}
