package app.dak.classify

/** How a sender address is shaped. */
public enum class SenderKind {
    /** Indian DLT-registered alphanumeric header, e.g. `VM-HDFCBK`, `AX-HDFCBK-S`. */
    DLT_HEADER,
    /** Short numeric code, typically 5-6 digits (e.g. `56070`). */
    SHORT_CODE,
    /** A full phone number (10+ digits, optionally with a country code / `+`). */
    PHONE_NUMBER,
    /** Anything else: plain alphanumeric text that isn't a recognised DLT header. */
    ALPHANUMERIC,
}

/** DLT traffic-type suffix, the last segment of some headers (e.g. the `-P` in `VK-AMAZON-P`). */
public enum class TrafficType(public val suffix: Char) {
    PROMOTIONAL('P'),
    SERVICE_IMPLICIT('S'),
    TRANSACTIONAL('T'),
    GOVERNMENT('G');

    public companion object {
        public fun fromSuffix(c: Char): TrafficType? = entries.firstOrNull { it.suffix == c.uppercaseChar() }
    }
}

/**
 * A parsed Indian DLT (Distributed Ledger Technology) sender header, of the shape
 * `<prefix>-<entity-header>[-<traffic-type>]`, e.g. `VM-HDFCBK`, `JD-HDFCBK`, `AX-HDFCBK-S`, `VK-AMAZON-P`,
 * `BZ-SWIGGY-T`, or a numeric promotional header such as `VM-612345`.
 *
 * The 2-letter prefix is added by the terminating network: one letter for the access provider (operator) and one
 * for its licensed service area (circle); `VM`, `JD`, `AX`, `VK`, `BZ`, `TX`, `BP` and many more are seen in the
 * wild. It says where the message was delivered, not who sent it, so the same entity header is routinely seen behind
 * several prefixes and the prefix is never brand-identifying.
 *
 * The entity header is the sender's registered header: alphanumeric (with a letter; up to 20 characters accepted)
 * for service, transactional and government traffic, or exactly 6 digits for promotional traffic.
 */
public data class DltHeader(
    val prefix: String,
    val entityHeader: String,
    val trafficType: TrafficType?,
) {
    /** A 6-digit numeric entity header: TRAI reserves these for promotional traffic. */
    val isNumeric: Boolean get() = entityHeader.isNotEmpty() && entityHeader.all { it in '0'..'9' }

    /**
     * The route the message travelled on: the explicit [trafficType] suffix, or [TrafficType.PROMOTIONAL] for a
     * numeric header without one; null when unknown (an alphanumeric header without a suffix).
     */
    val route: TrafficType? get() = trafficType ?: if (isNumeric) TrafficType.PROMOTIONAL else null

    public fun raw(): String = buildString {
        append(prefix)
        append('-')
        append(entityHeader)
        if (trafficType != null) {
            append('-')
            append(trafficType.suffix)
        }
    }
}

/** Parsing and normalisation helpers for SMS sender addresses. */
public object SenderId {

    private val prefixRegex = Regex("^[A-Za-z]{2}$")

    /** Length of a numeric (promotional) DLT entity header. */
    private const val NUMERIC_HEADER_LENGTH = 6

    /**
     * Parses [address] as an Indian DLT header, if it looks like one: a 2-letter prefix, a dash, an entity header
     * (up to 20 ASCII letters and digits with at least one letter, or exactly 6 digits for a numeric promotional
     * header; DLT headers are ASCII, so `VM-НDFCBK` with a Cyrillic Н is not one),
     * and an optional dash + single-letter traffic-type suffix. Returns null for anything else (phone numbers, plain
     * short codes, free text, and other digit runs behind a dash such as `VM-12345` or `91-9876543210`).
     */
    public fun parseDltHeader(address: String): DltHeader? {
        // ASCII only, checked before upper-casing: "ı" (dotless i) and "ſ" (long s) upper-case to I and S.
        if (address.any { it.code >= 0x80 }) return null
        val trimmed = address.trim().uppercase()
        val parts = trimmed.split('-')
        if (parts.size !in 2..3) return null
        val prefix = parts[0]
        if (!prefixRegex.matches(prefix)) return null
        val entity = parts[1]
        if (entity.isEmpty() || entity.length > 20 || !entity.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        // An alphanumeric entity header has a letter. An all-digit one is a numeric promotional header, which is
        // exactly 6 ASCII digits: any other digit run is more likely a number split by a stray dash.
        if (entity.none { it.isLetter() } && !(entity.length == NUMERIC_HEADER_LENGTH && entity.all { it in '0'..'9' })) {
            return null
        }
        var traffic: TrafficType? = null
        if (parts.size == 3) {
            val suffix = parts[2]
            if (suffix.length != 1) return null
            traffic = TrafficType.fromSuffix(suffix[0]) ?: return null
        }
        return DltHeader(prefix, entity, traffic)
    }

    /** Classifies the shape of [address]. */
    public fun classify(address: String): SenderKind {
        val trimmed = address.trim()
        if (parseDltHeader(trimmed) != null) return SenderKind.DLT_HEADER
        val digitsOnly = trimmed.all { it.isDigit() || it == '+' } && trimmed.any { it.isDigit() }
        if (digitsOnly) {
            val digits = trimmed.filter { it.isDigit() }
            return if (digits.length in 5..6) SenderKind.SHORT_CODE else SenderKind.PHONE_NUMBER
        }
        return SenderKind.ALPHANUMERIC
    }

    /**
     * A stable key used to collapse the same real-world sender registered under multiple DLT
     * prefixes into one merge group, e.g. `VM-HDFCBK`, `JD-HDFCBK`, `AX-HDFCBK` all map to `HDFCBK`.
     *
     * - DLT headers merge on their entity header alone (prefix and traffic-type suffix dropped).
     * - Numeric senders merge on their last 10 digits (Indian mobile number length), so a number
     *   seen with or without a `+91`/`0` prefix still collapses to the same key.
     * - Anything else merges on its own uppercased, trimmed form.
     */
    public fun mergeKey(address: String): String {
        val trimmed = address.trim()
        parseDltHeader(trimmed)?.let { return it.entityHeader }
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isNotEmpty() && trimmed.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) {
            return if (digits.length > 10) digits.takeLast(10) else digits
        }
        return trimmed.uppercase()
    }

    /** True if [address] is a plain 10-digit Indian mobile number (ignoring a `+91`/`0` prefix). */
    public fun isIndianMobile(address: String): Boolean {
        val digits = address.trim().removePrefix("+").filter { it.isDigit() }
        val local = when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
            digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
            else -> return false
        }
        return local.length == 10 && local[0] in '6'..'9'
    }
}
