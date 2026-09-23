package app.dak.classify.entities

/**
 * The regular expressions [EntityExtractor] runs over a (bounded) body. Every quantifier is bounded and no group
 * nests an unbounded repetition, so none of them can backtrack catastrophically; `SecurityTest` runs each one over
 * pathological input. Internal so that harness can reach them.
 */
internal object EntityPatterns {

    /** Account/card masks: "XX1234", "XXXX1234", "****1234", "xxxx xxxx 1234", "•• 1234", "XXXX-XX-1234". */
    val maskedAccount = Regex("""(?<![\p{L}\d])(?:[Xx*•]{2,6}[- ]?){1,4}(\d{3,6})(?![\d\p{L}])""")

    /** "A/c ending 1234", "card ending with 5678", "account ending in 0042": the span is the digits. */
    val accountEnding = Regex(
        """(?i)\b(?:a/c|acct|account|card)\b[^\n\d]{0,20}?\bending(?:\s{1,3}(?:with|in))?[\s:]{0,3}(\d{3,6})(?!\d)""",
    )

    /** Minimal amount matcher, used only when the caller supplies no amount hints (`:classify` has no `:finance`). */
    val amount = Regex("""(?i)(?:₹|\brs\.?|\binr)\s{0,2}(\d{1,3}(?:,\d{2,3}){0,6}(?:\.\d{1,2})?|\d{1,13}(?:\.\d{1,2})?)(?:/-)?""")

    /**
     * "Ref no. 412345678901", "UTR: HDFC0000123", "RRN 412312345678", "Txn ID T2409231234", "Order ID 402-1234567-12",
     * "UPI Ref 123456789012", "Transaction ID: ABC12345". Group 1 is the id (must contain a digit; checked in code).
     */
    val reference = Regex(
        """(?i)\b(?:ref(?:erence)?|txn|transaction|utr|rrn|order|booking|invoice|upi\s?ref|imps\s?ref|neft\s?ref)(?![a-z])""" +
            """(?:\s{0,2}(?:no|num|number|id|#))?\.?\s{0,2}[:#-]?\s{0,2}(?:is\s{1,2})?([A-Z0-9][A-Z0-9-]{5,29})(?![A-Za-z0-9])""",
    )

    /** "PNR 1234567890", "PNR No: 1234567890", "PNR:4567891230". */
    val pnr = Regex("""(?i)\bPNR(?:\s{0,2}(?:no|number))?\.?\s{0,2}[:#-]?\s{0,2}(?:is\s{1,2})?(\d{10})(?!\d)""")

    /** "AWB 1234567890", "tracking no. 123456789012", "Tracking ID: FMPP1234567890", "consignment no 12345678". */
    val tracking = Regex(
        """(?i)\b(?:awb|tracking|track(?:ing)?\s{0,2}(?:id|no|number)|consignment|shipment\s{0,2}(?:id|no))(?![a-z])""" +
            """(?:\s{0,2}(?:no|number|id|#))?\.?\s{0,2}[:#-]?\s{0,2}(?:is\s{1,2})?([A-Z0-9]{8,22})(?![A-Za-z0-9])""",
    )

    /**
     * UPI ids ("name@okhdfcbank", "9876543210@ybl"): a local part, "@", then a handle *without* a dot (a dot makes it
     * an email domain). Whether the handle is really a UPI handle is decided in code (known handle, or "UPI"/"VPA"
     * written just before).
     */
    val upi = Regex("""(?<![A-Za-z0-9._%+@-])([A-Za-z0-9][A-Za-z0-9._-]{1,63})@([A-Za-z][A-Za-z0-9]{1,31})(?![A-Za-z0-9@-]|\.[A-Za-z0-9])""")

    /** Emails: local part, "@", 2-6 dot-separated labels. */
    val email = Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\.[A-Za-z0-9-]{1,63}){1,5}(?![A-Za-z0-9-])""")

    /** Just before a UPI candidate: marks it as a UPI id even with an unknown handle. */
    val upiContext = Regex("""(?i)\b(?:upi|vpa)\b(?:\s{0,2}(?:id|address))?[\s:-]{0,3}(?:to\s{1,2})?$""")

    /** Just before a phone candidate: the digits are some other number (account, reference, customer id...). */
    val nonPhoneContext = Regex(
        """(?i)\b(?:a/c|acct|account|card|ref|reference|txn|utr|rrn|order|pnr|awb|id|folio|policy|loan|customer|crn|cif|invoice|booking)\b[\s.:#-]{0,3}(?:no\.?|number)?[\s.:#-]{0,3}$""",
    )

    /** All body regexes, for the ReDoS harness. */
    val all: List<Regex> get() = listOf(maskedAccount, accountEnding, amount, reference, pnr, tracking, upi, email)
}
