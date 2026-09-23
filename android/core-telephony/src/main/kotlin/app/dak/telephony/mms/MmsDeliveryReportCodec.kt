package app.dak.telephony.mms

/** `<updatedAtMillis>|<recipient>=<status>,...` (pure, JVM-tested). */
internal object MmsDeliveryReportCodec {
    fun encode(updatedAtMillis: Long, reports: Map<String, Int>): String =
        "$updatedAtMillis|" + reports.entries.joinToString(",") { (k, v) -> "${cleanKey(k)}=$v" }

    fun decode(value: String?): Pair<Long, Map<String, Int>>? {
        val (ts, body) = value?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: return null
        val at = ts.toLongOrNull() ?: return null
        val reports = body.split(',').filter { it.isNotEmpty() }.mapNotNull { entry ->
            val (k, v) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            v.toIntOrNull()?.let { k to it }
        }.toMap()
        return at to reports
    }

    /** Recipient keys never contain the codec's separators. */
    fun cleanKey(key: String): String = key.replace(SEPARATORS, "_").ifEmpty { "?" }

    private val SEPARATORS = Regex("[|,=]")
}
