package app.dak.automations.broadcast

/**
 * Loose phone-number identity for broadcast lists, independent of formatting: spaces, dashes, brackets, a leading
 * `+`, `00` or trunk `0` and the country code do not matter. Two numbers are the same when their last 10 digits
 * match, or, for shorter national numbers (7-9 digits, e.g. Singapore's 8), when one is a suffix of the other.
 */
public object PhoneKey {

    /** Digits only, with leading zeros (trunk / `00` prefix) removed. */
    public fun digits(address: String): String = address.filter { it in '0'..'9' }.trimStart('0')

    /** Dedupe key: the last 10 digits (fewer when the number is shorter); lowercase text for alphanumeric ids. */
    public fun of(address: String): String {
        if (isAlphanumeric(address)) return address.trim().lowercase()
        val d = digits(address)
        return if (d.length > 10) d.takeLast(10) else d
    }

    /** True when [a] and [b] are the same number (see the class doc). */
    public fun same(a: String, b: String): Boolean {
        if (isAlphanumeric(a) || isAlphanumeric(b)) return a.trim().equals(b.trim(), ignoreCase = true)
        val da = digits(a)
        val db = digits(b)
        if (da.isEmpty() || db.isEmpty()) return false
        if (da.length >= 10 && db.length >= 10) return da.takeLast(10) == db.takeLast(10)
        val (short, long) = if (da.length <= db.length) da to db else db to da
        return short.length >= MIN_NATIONAL_DIGITS && long.endsWith(short)
    }

    /** A sender id with letters (e.g. `VM-HDFCBK`): cannot receive a reply SMS, never a broadcast recipient. */
    public fun isAlphanumeric(address: String): Boolean = address.any { it.isLetter() }

    /** A short code (1-6 digits): service numbers, possibly premium-rate, never a broadcast recipient. */
    public fun isShortCode(address: String): Boolean {
        if (isAlphanumeric(address)) return false
        val d = address.filter { it in '0'..'9' }
        return d.length in 1 until MIN_NATIONAL_DIGITS
    }

    /** Nothing dialable at all (no digits, no letters). */
    public fun isInvalid(address: String): Boolean = address.none { it.isLetterOrDigit() }

    private const val MIN_NATIONAL_DIGITS = 7
}
