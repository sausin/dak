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
    )

    /**
     * Resolves an institution name from a raw SMS sender, e.g. `VM-HDFCBK`, `AD-HDFCBK-S`,
     * `HDFCBK`, or a plain phone number (returns null for the latter).
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
        return null
    }
}
