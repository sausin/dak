package app.dak.telephony.send

/** Exponential backoff for automatic send retries (SMS and MMS). */
internal object RetryPolicy {
    const val MAX_SMS_ATTEMPTS = 5
    const val MAX_MMS_ATTEMPTS = 4
    private const val BASE_DELAY_MILLIS = 30_000L
    private const val MAX_DELAY_MILLIS = 30 * 60_000L

    /**
     * Delay before attempt number `failedAttempts + 1`, given how many attempts already failed (>= 1):
     * 30 s, 60 s, 2 min, 4 min, ... capped at 30 min.
     */
    fun delayMillis(failedAttempts: Int): Long {
        val exponent = (failedAttempts - 1).coerceIn(0, 16)
        return (BASE_DELAY_MILLIS shl exponent).coerceAtMost(MAX_DELAY_MILLIS)
    }

    fun shouldRetry(failedAttempts: Int, maxAttempts: Int, retryable: Boolean): Boolean =
        retryable && failedAttempts < maxAttempts
}
