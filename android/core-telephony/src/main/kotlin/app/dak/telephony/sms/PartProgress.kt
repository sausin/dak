package app.dak.telephony.sms

/**
 * Per-message multipart progress. Some OEMs fire the sent/delivery intent of every part, some only the last one;
 * both are handled: a message counts as done once every part reported, or once the last part reported with no
 * earlier failure.
 */
internal data class PartProgress(
    val partCount: Int,
    val sentParts: Set<Int> = emptySet(),
    val deliveredParts: Set<Int> = emptySet(),
    val failed: Boolean = false,
    val startedAtMillis: Long = 0,
) {
    fun withSent(part: Int): PartProgress = copy(sentParts = sentParts + part)

    fun withDelivered(part: Int): PartProgress = copy(deliveredParts = deliveredParts + part)

    fun withFailure(): PartProgress = copy(failed = true)

    val isFullySent: Boolean
        get() = !failed && (sentParts.size >= partCount || (partCount - 1) in sentParts)

    val isFullyDelivered: Boolean
        get() = deliveredParts.size >= partCount || (partCount - 1) in deliveredParts

    fun encode(): String =
        "$partCount;${sentParts.sorted().joinToString(",")};${deliveredParts.sorted().joinToString(",")};" +
            "${if (failed) 1 else 0};$startedAtMillis"

    companion object {
        fun decode(value: String?): PartProgress? {
            val fields = value?.split(';') ?: return null
            if (fields.size != 5) return null
            val count = fields[0].toIntOrNull() ?: return null
            fun set(s: String): Set<Int> = if (s.isEmpty()) emptySet() else s.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            return PartProgress(
                partCount = count,
                sentParts = set(fields[1]),
                deliveredParts = set(fields[2]),
                failed = fields[3] == "1",
                startedAtMillis = fields[4].toLongOrNull() ?: 0L,
            )
        }
    }
}
