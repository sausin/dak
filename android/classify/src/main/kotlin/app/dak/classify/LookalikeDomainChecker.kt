package app.dak.classify

import app.dak.classify.unicode.Confusables
import kotlin.math.abs

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
    private val brandNames: List<Pair<String, String>> = officialDomains.values.distinct().map { it.lowercase() to it }

    /** Each brand's words of 3+ letters ("hdfc", "bank"), split once here rather than on every check. */
    private val brandWords: List<Pair<List<String>, String>> = brandNames.map { (lower, name) -> wordsOf(lower) to name }

    public fun check(link: ExtractedLink): LinkVerdict {
        val host = link.host ?: return LinkVerdict(link, LinkRisk.UNKNOWN)

        if (shorteners.any { host == it || host.endsWith(".$it") }) {
            return LinkVerdict(link, LinkRisk.SHORTENED)
        }
        if (isOfficial(host)) {
            return LinkVerdict(link, LinkRisk.OFFICIAL, matchedBrand = domainToBrand[host])
        }

        // "sbi.co.in.account-verify.example", "irctc.co.in-refund.example": an official domain spelled out at the start
        // of (or inside) another host, which the reader takes for the real one.
        embeddedOfficialBrand(host)?.let { brand ->
            return LinkVerdict(link, LinkRisk.LOOKALIKE, matchedBrand = brand)
        }

        lookalikeBrand(host)?.let { brand ->
            return LinkVerdict(link, LinkRisk.LOOKALIKE, matchedBrand = brand)
        }

        // Internationalised hosts: take the host the browser resolves (UTS #46: fullwidth letters mapped, `xn--` labels
        // decoded), reduce it to its UTS #39 skeleton (Cyrillic "а", Greek "ο", mathematical and fullwidth letters,
        // Bengali "০", … become the Latin they imitate; diacritics dropped) and compare with the official domains'
        // skeletons, so "hdfcbаnk.com" names the brand it imitates. Any other IDN host is still flagged: SMS phishing
        // uses them almost exclusively as homographs, and a warning costs a legitimate IDN link one extra tap. The
        // dialog then shows the punycode host (or the Unicode one, when HostDisplay finds it safe for the reader).
        if (link.isIdn) {
            val skeleton = Confusables.looseSkeleton(link.unicodeHost ?: host)
            val brand = skeletonToBrand[skeleton]
                ?: skeletonToBrand.entries.firstOrNull { skeleton.endsWith(".${it.key}") }?.value
                ?: lookalikeBrand(skeleton, skeletons = true)
            return LinkVerdict(link, LinkRisk.LOOKALIKE, matchedBrand = brand)
        }

        // "https://hdfcbank.com@evil.example/": the part before '@' is decoration, the host is what opens.
        if (link.hasUserInfo) {
            return LinkVerdict(link, LinkRisk.LOOKALIKE)
        }

        // A bare IP address ("http://192.0.2.44/sbi", "http://3221225516/kyc", which browsers read as 192.0.2.44):
        // no real bank, courier or government sends one, and it hides who runs the site.
        if (isIpHost(link.asciiHost ?: host)) {
            return LinkVerdict(link, LinkRisk.LOOKALIKE)
        }

        // "gov-uk-support-payment.com", "fines.ae-gov.com", "antai-gouv-paiement.com": a government word in a host that
        // is not under a government domain (`.gov`, `gov.uk`, `gouv.fr`, `gov.in`, ...).
        if (imitatesGovernment(host)) {
            return LinkVerdict(link, LinkRisk.LOOKALIKE)
        }

        val tld = host.substringAfterLast('.', missingDelimiterValue = "")
        if (tld.isNotEmpty() && tld in suspiciousTlds) {
            return LinkVerdict(link, LinkRisk.SUSPICIOUS_TLD)
        }

        return LinkVerdict(link, LinkRisk.UNKNOWN)
    }

    /** Official domains by skeleton ([Confusables.looseSkeleton]), for IDN hosts. Built on the first IDN link. */
    private val skeletonToBrand: Map<String, String> by lazy {
        domainToBrand.entries.associate { (domain, brand) -> Confusables.looseSkeleton(domain) to brand }
    }

    /** Official registrable labels (`hdfcbank` of `hdfcbank.com`) with their brand. */
    private val officialLabels: List<Pair<String, String>> = domainToBrand.map { (domain, brand) ->
        domain.split('.').let { if (it.size >= 2) it[it.size - 2] else domain } to brand
    }

    /** [brandNames] and [officialLabels] in skeleton form, for [lookalikeBrand] on a skeleton. */
    private val skeletonBrandWords: List<Pair<List<String>, String>> by lazy {
        brandNames.map { (lower, name) -> wordsOf(Confusables.looseSkeleton(lower)) to name }
    }
    private val skeletonOfficialLabels: List<Pair<String, String>> by lazy {
        officialLabels.map { (label, brand) -> Confusables.looseSkeleton(label) to brand }
    }

    private fun isIpHost(host: String): Boolean {
        if (host.startsWith("[")) return true
        val last = host.trimEnd('.').substringAfterLast('.')
        if (last.isEmpty()) return false
        return last.all { it in '0'..'9' } || (last.startsWith("0x") && last.length > 2 && last.drop(2).all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun imitatesGovernment(host: String): Boolean {
        val labels = host.trimEnd('.').split('.')
        if (labels.none { label -> label.split('-').any { it in GOVERNMENT_WORDS } }) return false
        val last = labels.last()
        val official = last in GOVERNMENT_TLDS ||
            (labels.size >= 2 && last.length == 2 && labels[labels.size - 2] in GOVERNMENT_SECOND_LEVEL)
        return !official
    }

    /** The brand of an official domain that starts [host] or one of its labels and is followed by `.` or `-`. */
    private fun embeddedOfficialBrand(host: String): String? = domainToBrand.entries.firstOrNull { (domain, _) ->
        host.startsWith("$domain.") || host.startsWith("$domain-") || host.contains(".$domain.") || host.contains(".$domain-")
    }?.value

    private fun isOfficial(host: String): Boolean =
        domainToBrand.containsKey(host) || domainToBrand.keys.any { host.endsWith(".$it") }

    /**
     * A host resembles an official brand if the brand name appears verbatim as a hyphenated
     * subdomain/label segment (e.g. `hdfc-bank-kyc.xyz`), or if the host's registrable label is
     * within edit distance 2 of an official domain's label (a homoglyph/typo-squat).
     */
    private fun lookalikeBrand(host: String, skeletons: Boolean = false): String? {
        val labels = host.split('.', '-')
        for ((words, brandName) in if (skeletons) skeletonBrandWords else brandWords) {
            if (words.isNotEmpty() && words.all { w -> labels.any { it.contains(w) } }) {
                // Brand words present, but host is not itself the official domain: lookalike.
                return brandName
            }
        }
        val registrable = host.split('.').let { parts ->
            if (parts.size >= 2) parts[parts.size - 2] else host
        }
        for ((officialLabel, brand) in if (skeletons) skeletonOfficialLabels else officialLabels) {
            if (officialLabel.length >= 4 && abs(registrable.length - officialLabel.length) <= 2 &&
                levenshtein(registrable, officialLabel) in 1..2
            ) {
                return brand
            }
        }
        return null
    }

    /** Edit distance, two rows at a time. */
    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    private fun wordsOf(lowerBrand: String): List<String> = lowerBrand.split(WORD_SPLIT).filter { it.length >= 3 }

    public companion object {
        private val WORD_SPLIT = Regex("[\\s.]+")

        /** Host words that name a government ("gov-uk-...", "...-gouv-..."). */
        private val GOVERNMENT_WORDS = setOf("gov", "gouv", "gob", "govt")

        /** Top-level domains only governments hold. */
        private val GOVERNMENT_TLDS = setOf("gov", "mil")

        /** Second-level labels of government domains under a country code (`gov.uk`, `gouv.fr`, `gob.es`, `nic.in`...). */
        private val GOVERNMENT_SECOND_LEVEL = setOf("gov", "gouv", "gob", "govt", "go", "gc", "nic", "mil", "gv")

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
