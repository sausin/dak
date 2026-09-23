package app.dak.index.otp

/** Pure timing rules of the OTP lifecycle. */
object OtpTiming {
    /** A consumed OTP is never auto-deleted sooner than this, so a retrying app can still read it. */
    const val MIN_CONSUMED_DELETE_MILLIS: Long = 5 * 60_000L
    const val DEFAULT_CONSUMED_DELETE_MILLIS: Long = 10 * 60_000L
    const val DEFAULT_OTP_DELETE_MILLIS: Long = 24 * 60 * 60_000L

    /** Two OTP messages with the same code in one conversation within this window are shown as a repeat. */
    const val REPEAT_WINDOW_MILLIS: Long = 10 * 60_000L

    fun clampConsumedDelay(requestedMillis: Long): Long = maxOf(requestedMillis, MIN_CONSUMED_DELETE_MILLIS)

    /**
     * Remaining delay before deleting a message that arrived at [arrivedAtMillis] with lifetime [lifetimeMillis],
     * never negative (a message already past its lifetime is deleted immediately).
     */
    fun remainingDelay(arrivedAtMillis: Long, lifetimeMillis: Long, nowMillis: Long): Long =
        (arrivedAtMillis + lifetimeMillis - nowMillis).coerceAtLeast(0L)
}
