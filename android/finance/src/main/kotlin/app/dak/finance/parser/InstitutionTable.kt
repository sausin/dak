package app.dak.finance.parser

/**
 * Maps an SMS sender header (the DLT header before the hyphen, e.g. `VM-HDFCBK`, `AD-ICICIB`, or
 * a bare header like `HDFCBK`) to a human-readable institution name. Intentionally small and
 * local to `:finance`; the richer sender-identity table lives in `:classify` and this module does
 * not depend on it.
 */
object InstitutionTable {

    /** Header token (as it appears after the DLT prefix, uppercase) -> institution display name. */
    private val table: Map<String, String> = mapOf(
        "HDFCBK" to "HDFC Bank",
        "HDFCBN" to "HDFC Bank",
        "ICICIB" to "ICICI Bank",
        "ICICIT" to "ICICI Bank",
        "SBIINB" to "State Bank of India",
        "SBIUPI" to "State Bank of India",
        "SBICRD" to "State Bank of India",
        "ATMSBI" to "State Bank of India",
        "AXISBK" to "Axis Bank",
        "AXISBN" to "Axis Bank",
        "KOTAKB" to "Kotak Mahindra Bank",
        "KOTAKM" to "Kotak Mahindra Bank",
        "PAYTMB" to "Paytm Payments Bank",
        "PYTMBK" to "Paytm Payments Bank",
        "PHONPE" to "PhonePe",
        "PHONEP" to "PhonePe",
        "AMZNPB" to "Amazon Pay",
        "AMAZNP" to "Amazon Pay",
        "YESBNK" to "Yes Bank",
        "IDFCFB" to "IDFC FIRST Bank",
        "INDUSB" to "IndusInd Bank",
        "PNBSMS" to "Punjab National Bank",
        "BOIIND" to "Bank of India",
        "CANBNK" to "Canara Bank",
        "UNIONB" to "Union Bank of India",
        "IDBIBK" to "IDBI Bank",
        "RBLBNK" to "RBL Bank",
        "FEDBNK" to "Federal Bank",
        "SCBANK" to "Standard Chartered",
        "CITIBK" to "Citibank",
        "HSBCIN" to "HSBC",
        "AUBANK" to "AU Small Finance Bank",
        "BOBTXN" to "Bank of Baroda",
        "BOBSMS" to "Bank of Baroda",
        "CENTBK" to "Central Bank of India",
        "BKOFIN" to "Bank of India",
    )

    /**
     * Home country (ISO 3166-1 alpha-2) of an institution display name from this table, or null if unknown. Every
     * entry today is an Indian DLT header (international brands here are their Indian arms: `HSBCIN`, `CITIBK`), so
     * a known name means "IN"; used to default such an account's home currency to INR wherever the user is.
     */
    fun countryOf(institution: String?): String? {
        val name = institution?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (table.values.any { it.equals(name, ignoreCase = true) }) "IN" else null
    }

    /** A DLT header: 2-letter prefix, an alphanumeric entity header with a letter, optional traffic suffix. */
    private val dltHeader = Regex("""^[A-Z]{2}-([A-Z0-9]*[A-Z][A-Z0-9]*)(?:-[A-Z])?$""")

    /**
     * Whether [sender] is an Indian DLT (TRAI) header such as `VM-NEWBNK-S`: only Indian senders use that shape, so a
     * number the message leaves without a currency ("debited by 250.0") is in rupees.
     */
    fun isDltSender(sender: String): Boolean = dltHeader.matches(sender.trim().uppercase())

    /**
     * Resolves an institution name from a raw SMS sender, e.g. `VM-HDFCBK`, `AD-HDFCBK-S`,
     * `HDFCBK`, or a plain phone number (returns null for the latter). A DLT sender whose header is not in the table
     * resolves to the header itself (`AX-NEWBNK-S` -> `NEWBNK`), so every bank gets its own accounts without a table
     * entry; the table only supplies friendlier names.
     */
    fun institutionFor(sender: String): String? {
        val cleaned = sender.trim().uppercase()
        val parts = cleaned.split('-').filter { it.isNotBlank() }
        for (part in parts) {
            table[part]?.let { return it }
        }
        // Fall back to substring match, since headers sometimes carry a suffix like "HDFCBKS".
        for ((header, name) in table) {
            if (cleaned.contains(header)) return name
        }
        return dltHeader.matchEntire(cleaned)?.groupValues?.get(1)
    }
}
