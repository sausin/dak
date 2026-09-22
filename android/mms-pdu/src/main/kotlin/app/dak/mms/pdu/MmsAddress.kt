package app.dak.mms.pdu

/**
 * MMS address helpers. On the wire phone numbers carry a type suffix (`+15551234567/TYPE=PLMN`); e-mail
 * addresses do not. The rest of the app deals only in bare addresses.
 */
object MmsAddress {
    private const val TYPE_SUFFIX = "/TYPE="

    /** Strips a `/TYPE=...` suffix (any type, any case) and surrounding whitespace. */
    fun fromWire(address: String): String {
        val trimmed = address.trim()
        val idx = trimmed.indexOf(TYPE_SUFFIX, ignoreCase = true)
        return if (idx >= 0) trimmed.substring(0, idx).trim() else trimmed
    }

    /**
     * Adds `/TYPE=PLMN` to phone numbers. E-mail addresses and values that already carry a type are returned as-is.
     * Visual separators (spaces, dashes, dots, parentheses) are removed from phone numbers.
     */
    fun toWire(address: String): String {
        val trimmed = address.trim()
        if (trimmed.contains(TYPE_SUFFIX, ignoreCase = true) || trimmed.contains('@')) return trimmed
        val compact = trimmed.filterNot { it == ' ' || it == '-' || it == '.' || it == '(' || it == ')' }
        return if (isPhoneNumber(compact)) "$compact/TYPE=PLMN" else trimmed
    }

    /** True for dialable numbers: optional leading `+`, then digits, `*` or `#` only. */
    fun isPhoneNumber(address: String): Boolean {
        if (address.isEmpty()) return false
        val body = if (address[0] == '+') address.substring(1) else address
        return body.isNotEmpty() && body.all { it.isDigit() || it == '*' || it == '#' }
    }
}
