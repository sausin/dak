package app.dak.security

/**
 * Escalating lockout after repeated wrong app-PIN entries: the first [FREE_ATTEMPTS] failures cost nothing, then
 * every further failure starts a lockout of 30 s, 1 min, 5 min, 15 min, 30 min and finally 1 hour (the cap).
 */
object LockoutSchedule {
    const val FREE_ATTEMPTS: Int = 5

    private val STEPS_MILLIS = longArrayOf(
        30_000L,
        60_000L,
        5 * 60_000L,
        15 * 60_000L,
        30 * 60_000L,
        60 * 60_000L,
    )

    /** Lockout that starts right after the [failures]-th consecutive failure (0 when none applies). */
    fun lockoutMillisAfter(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val step = (failures - FREE_ATTEMPTS).coerceAtMost(STEPS_MILLIS.size - 1)
        return STEPS_MILLIS[step]
    }
}

/**
 * A moment read from two clocks: [wallMillis] (`System.currentTimeMillis`, survives reboots but the user can change
 * it) and [elapsedMillis] (`SystemClock.elapsedRealtime`, monotonic but restarts at boot), plus [bootId] (the
 * platform boot count, or any value that changes on every reboot).
 */
data class ClockReading(val wallMillis: Long, val elapsedMillis: Long, val bootId: Int)

/**
 * Persisted app-PIN failure state. Survives process death and reboots, and resists clock tampering: within the same
 * boot the lockout is measured on the monotonic clock; after a reboot it falls back to the wall clock, clamped so that
 * setting the clock back never extends and setting it forward only helps after a reboot.
 */
data class LockoutState(
    val failures: Int = 0,
    val lockoutMillis: Long = 0L,
    val startedAt: ClockReading? = null,
) {
    /** Milliseconds until another attempt is allowed (0 = allowed now). */
    fun remainingMillis(now: ClockReading): Long {
        val start = startedAt ?: return 0L
        if (lockoutMillis <= 0L) return 0L
        val passed = if (now.bootId == start.bootId && now.elapsedMillis >= start.elapsedMillis) {
            now.elapsedMillis - start.elapsedMillis
        } else {
            // Rebooted (or elapsed time went backwards, which also means a reboot): wall clock, never negative.
            (now.wallMillis - start.wallMillis).coerceAtLeast(0L)
        }
        return (lockoutMillis - passed).coerceIn(0L, lockoutMillis)
    }

    /** State after one more wrong PIN at [now]. */
    fun afterFailure(now: ClockReading): LockoutState {
        val count = (failures + 1).coerceAtMost(MAX_COUNTED_FAILURES)
        val lockout = LockoutSchedule.lockoutMillisAfter(count)
        return LockoutState(failures = count, lockoutMillis = lockout, startedAt = if (lockout > 0) now else null)
    }

    /** Wrong attempts left before the next failure starts a lockout (0 once lockouts have begun). */
    val attemptsBeforeLockout: Int get() = (LockoutSchedule.FREE_ATTEMPTS - 1 - failures).coerceAtLeast(0)

    /** `failures;lockoutMillis;wall;elapsed;boot` (the last three empty when no lockout started). */
    fun encode(): String {
        val s = startedAt
        return listOf(
            failures.toString(),
            lockoutMillis.toString(),
            s?.wallMillis?.toString().orEmpty(),
            s?.elapsedMillis?.toString().orEmpty(),
            s?.bootId?.toString().orEmpty(),
        ).joinToString(";")
    }

    companion object {
        val NONE = LockoutState()
        private const val MAX_COUNTED_FAILURES = 1_000

        /**
         * Parses [encoded]. Blank means no failures; a corrupt value fails closed: it decodes to [LockoutSchedule.FREE_ATTEMPTS]
         * failures, so the very next wrong PIN starts a lockout.
         */
        fun decode(encoded: String?): LockoutState {
            if (encoded.isNullOrBlank()) return NONE
            val p = encoded.split(';')
            val failures = p.getOrNull(0)?.toIntOrNull()
            val lockout = p.getOrNull(1)?.toLongOrNull()
            if (p.size != 5 || failures == null || lockout == null || failures < 0 || lockout < 0) {
                // Tampered or corrupt: never reset the counter to zero.
                return LockoutState(failures = LockoutSchedule.FREE_ATTEMPTS, lockoutMillis = 0L, startedAt = null)
            }
            val wall = p[2].toLongOrNull()
            val elapsed = p[3].toLongOrNull()
            val boot = p[4].toIntOrNull()
            val start = if (wall != null && elapsed != null && boot != null) ClockReading(wall, elapsed, boot) else null
            return LockoutState(failures.coerceAtMost(MAX_COUNTED_FAILURES), if (start == null) 0L else lockout, start)
        }
    }
}
