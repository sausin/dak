package app.dak.telephony.sms

/** Where one send attempt of a (possibly multipart) SMS stands, from its per-part sent results. */
internal enum class SendAttemptOutcome {
    /** Not every part has reported yet. */
    IN_FLIGHT,

    /** Every part went out. */
    SENT,

    /** No part went out: resending the whole message cannot duplicate anything at the recipient. */
    ALL_FAILED,

    /**
     * Some parts went out and some failed. The recipient's phone holds an incomplete concatenation it can never
     * complete (a resend gets a new concatenation reference), so an automatic resend would deliver the parts that
     * did arrive a second time. Never retried automatically: the user decides.
     */
    PARTIAL,
}

/**
 * Per-message multipart progress for the current attempt. Some OEMs fire the sent/delivery intent of every part,
 * some only the last one; both are handled: an attempt is settled once every part reported, or once the last part
 * reported (parts go out in order, so its result comes last).
 *
 * The outcome is decided once per attempt ([resolved]); reports from an older attempt ([attempt] mismatch) are
 * ignored by [SendProgressStore].
 */
internal data class PartProgress(
    val partCount: Int,
    val sentParts: Set<Int> = emptySet(),
    val deliveredParts: Set<Int> = emptySet(),
    /** True once any part of this attempt failed. */
    val failed: Boolean = false,
    val startedAtMillis: Long = 0,
    val failedParts: Set<Int> = emptySet(),
    /** Result code of the first failed part, for the failure reason. */
    val failureCode: Int = 0,
    /** Attempt number these results belong to (0: unknown, e.g. progress written by an older version). */
    val attempt: Int = 0,
    /** The attempt's outcome was already acted on. */
    val resolved: Boolean = false,
) {
    fun withSent(part: Int): PartProgress = copy(sentParts = sentParts + part)

    fun withDelivered(part: Int): PartProgress = copy(deliveredParts = deliveredParts + part)

    fun withFailure(): PartProgress = copy(failed = true)

    fun withFailedPart(part: Int, code: Int): PartProgress =
        copy(failed = true, failedParts = failedParts + part, failureCode = if (failed) failureCode else code)

    val isFullySent: Boolean
        get() = !failed && (sentParts.size >= partCount || (partCount - 1) in sentParts)

    val isFullyDelivered: Boolean
        get() = deliveredParts.size >= partCount || (partCount - 1) in deliveredParts

    /** Every part reported, or the last one did (see the class comment). */
    val isSettled: Boolean
        get() {
            val reported = sentParts + failedParts
            return reported.size >= partCount || (partCount - 1) in reported
        }

    val outcome: SendAttemptOutcome
        get() = when {
            !isSettled -> SendAttemptOutcome.IN_FLIGHT
            !failed -> SendAttemptOutcome.SENT
            sentParts.isEmpty() -> SendAttemptOutcome.ALL_FAILED
            else -> SendAttemptOutcome.PARTIAL
        }

    fun encode(): String =
        "$partCount;${sentParts.sorted().joinToString(",")};${deliveredParts.sorted().joinToString(",")};" +
            "${if (failed) 1 else 0};$startedAtMillis;${failedParts.sorted().joinToString(",")};$failureCode;$attempt;" +
            (if (resolved) 1 else 0)

    companion object {
        /** Decodes [encode]; also reads the 5-field form written before per-part failures were tracked. */
        fun decode(value: String?): PartProgress? {
            val fields = value?.split(';') ?: return null
            if (fields.size != 5 && fields.size != 9) return null
            val count = fields[0].toIntOrNull() ?: return null
            fun set(s: String): Set<Int> = if (s.isEmpty()) emptySet() else s.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            val base = PartProgress(
                partCount = count,
                sentParts = set(fields[1]),
                deliveredParts = set(fields[2]),
                failed = fields[3] == "1",
                startedAtMillis = fields[4].toLongOrNull() ?: 0L,
            )
            if (fields.size == 5) return base
            return base.copy(
                failedParts = set(fields[5]),
                failureCode = fields[6].toIntOrNull() ?: 0,
                attempt = fields[7].toIntOrNull() ?: 0,
                resolved = fields[8] == "1",
            )
        }
    }
}
