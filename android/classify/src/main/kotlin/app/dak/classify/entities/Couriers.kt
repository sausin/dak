package app.dak.classify.entities

/**
 * Couriers recognised by name next to a tracking / AWB number. Keys are stable identifiers the app maps to a
 * courier website (opened only when the user taps "Track"); nothing here touches the network.
 */
public object Couriers {

    /** Courier key to the spellings that identify it in a message (lower case, matched as whole words). */
    internal val NAMES: Map<String, List<String>> = linkedMapOf(
        "bluedart" to listOf("blue dart", "bluedart"),
        "delhivery" to listOf("delhivery"),
        "dtdc" to listOf("dtdc"),
        "ecomexpress" to listOf("ecom express", "ecomexpress"),
        "xpressbees" to listOf("xpressbees", "xpress bees"),
        "ekart" to listOf("ekart"),
        "shadowfax" to listOf("shadowfax"),
        "indiapost" to listOf("india post", "speed post", "speedpost", "indiapost"),
        "fedex" to listOf("fedex"),
        "dhl" to listOf("dhl"),
        "ups" to listOf("ups"),
        "aramex" to listOf("aramex"),
    )

    /** Every courier key. */
    public val keys: Set<String> get() = NAMES.keys

    private val pattern: Regex = Regex(
        "(?i)(?<![\\p{L}\\d])(" + NAMES.values.flatten().sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it).replace(" ", "\\E\\s?\\Q") } + ")(?![\\p{L}\\d])",
    )

    /** The key of the first courier named in [text], or null. */
    public fun find(text: String): String? {
        val name = pattern.find(text)?.groupValues?.get(1)?.lowercase()?.replace(Regex("\\s"), " ") ?: return null
        return NAMES.entries.firstOrNull { (_, names) -> names.any { it == name || it.replace(" ", "") == name.replace(" ", "") } }?.key
    }
}
