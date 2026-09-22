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
 * `<principal-entity-prefix>-<entity-header>[-<traffic-type>]`, e.g. `VM-HDFCBK`, `JD-HDFCBK`,
 * `AX-HDFCBK-S`, `VK-AMAZON-P`, `BZ-SWIGGY-T`.
 *
 * The 2-letter prefix identifies the registered telemarketer/access-provider that relayed the
 * message (`VM`, `JD`, `AX`, `VK`, `BZ`, `TX`, `BP`, `BW`, `DM`, `DN`, `DT`, `TA`, `TD`, `TG`, `TJ`,
 * `TK`, `TS`, `TV`, `VD`, `VI`, `VP` are all seen in the wild) and is not brand-identifying: the
 * same entity header is routinely seen fronted by several different prefixes.
 */
public data class DltHeader(
    val prefix: String,
    val entityHeader: String,
    val trafficType: TrafficType?,
) {
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

    /**
     * Parses [address] as an Indian DLT header, if it looks like one: a 2-letter prefix, a dash,
     * an alphanumeric entity header (2-20 chars), and an optional dash + single-letter traffic-type
     * suffix. Returns null for anything else (numeric senders, plain short codes, free text).
     */
    public fun parseDltHeader(address: String): DltHeader? {
        val trimmed = address.trim().uppercase()
        val parts = trimmed.split('-')
        if (parts.size !in 2..3) return null
        val prefix = parts[0]
        if (!prefixRegex.matches(prefix)) return null
        val entity = parts[1]
        if (entity.isEmpty() || entity.length > 20 || !entity.all { it.isLetterOrDigit() }) return null
        // An entity header must contain at least one letter (else it's likely a numeric sender split
        // by a stray dash, not a DLT header).
        if (entity.none { it.isLetter() }) return null
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
