package app.dak.security

/** How long the app may stay in the background before it locks again. [value] is the stored setting value. */
enum class AutoLockTimeout(val value: String, val millis: Long) {
    IMMEDIATELY("immediately", 0L),
    SECONDS_30("30s", 30_000L),
    MINUTE_1("1m", 60_000L),
    MINUTES_5("5m", 5 * 60_000L),
    MINUTES_15("15m", 15 * 60_000L),
    ;

    companion object {
        val DEFAULT = MINUTE_1
        fun fromValue(value: String?): AutoLockTimeout = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

/** The unlock method the user chose for the app lock ([OFF] = app lock disabled). */
enum class LockMethodChoice(val value: String) {
    OFF("off"),
    /** The phone's own screen lock (fingerprint/face and PIN/pattern/password) via the system prompt. */
    DEVICE("device"),
    /** A separate 4–8 digit PIN for Dak, optionally with fingerprint instead of typing it. */
    APP_PIN("appPin"),
    ;

    companion object {
        val DEFAULT = OFF
        fun fromValue(value: String?): LockMethodChoice = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

/** When the window gets `FLAG_SECURE` (blank Recents thumbnail, no screenshots). */
enum class RecentsProtection(val value: String) {
    /** Only while the app lock is on. */
    WHEN_LOCK_ON("whenLockOn"),
    ALWAYS("always"),
    NEVER("never"),
    ;

    companion object {
        val DEFAULT = WHEN_LOCK_ON
        fun fromValue(value: String?): RecentsProtection = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

/** What actually guards the app right now, after checking what the device and the stored PIN allow. */
enum class EffectiveLock { NONE, DEVICE, APP_PIN }

/** The user's app-lock settings, as read from the settings registry. */
data class AppLockConfig(
    val method: LockMethodChoice = LockMethodChoice.DEFAULT,
    val autoLock: AutoLockTimeout = AutoLockTimeout.DEFAULT,
    val lockOnScreenOff: Boolean = true,
    val recents: RecentsProtection = RecentsProtection.DEFAULT,
    val protectSensitiveScreens: Boolean = false,
) {
    val enabled: Boolean get() = method != LockMethodChoice.OFF
}

/** Pure decisions shared by the lock manager, the settings screen and notifications. */
object AppLockRules {

    /**
     * The lock that can actually be enforced. A choice that cannot be honoured falls back rather than locking the user
     * out: app PIN without a stored PIN (e.g. settings restored on a new phone) uses the device lock when there is
     * one; the device lock with no screen lock set uses the app PIN if one exists; otherwise nothing.
     */
    fun effectiveLock(method: LockMethodChoice, deviceSecure: Boolean, hasAppPin: Boolean): EffectiveLock = when (method) {
        LockMethodChoice.OFF -> EffectiveLock.NONE
        LockMethodChoice.APP_PIN -> when {
            hasAppPin -> EffectiveLock.APP_PIN
            deviceSecure -> EffectiveLock.DEVICE
            else -> EffectiveLock.NONE
        }
        LockMethodChoice.DEVICE -> when {
            deviceSecure -> EffectiveLock.DEVICE
            hasAppPin -> EffectiveLock.APP_PIN
            else -> EffectiveLock.NONE
        }
    }

    /**
     * How a one-off confirmation (sensitive screen, OTP forwarding) authenticates, independent of whether the app
     * lock is on: the app PIN when the user chose it (or has no screen lock), otherwise the device lock.
     */
    fun confirmationLock(method: LockMethodChoice, deviceSecure: Boolean, hasAppPin: Boolean): EffectiveLock = when {
        method == LockMethodChoice.APP_PIN && hasAppPin -> EffectiveLock.APP_PIN
        deviceSecure -> EffectiveLock.DEVICE
        hasAppPin -> EffectiveLock.APP_PIN
        else -> EffectiveLock.NONE
    }

    /** True when the window should carry `FLAG_SECURE`. The lock screen itself always does ([locked]). */
    fun secureWindow(config: AppLockConfig, lockActive: Boolean, locked: Boolean): Boolean = locked || when (config.recents) {
        RecentsProtection.ALWAYS -> true
        RecentsProtection.NEVER -> false
        RecentsProtection.WHEN_LOCK_ON -> lockActive
    }

    /**
     * Biometric-only unlock (no device-credential fallback in the system prompt) is offered only as a shortcut for the
     * app PIN, so there is always a knowledge factor behind it.
     */
    fun fingerprintInsteadOfPinAvailable(hasAppPin: Boolean, strongBiometricEnrolled: Boolean): Boolean =
        hasAppPin && strongBiometricEnrolled
}

/**
 * In-memory lock state machine (never persisted: a new process always starts locked when the lock is on).
 *
 * Times are monotonic milliseconds (`SystemClock.elapsedRealtime`). "Expiry" ends both the app unlock and the
 * sensitive-screen session: it happens when the app returns after being in the background for at least the
 * auto-lock timeout, immediately on backgrounding with [AutoLockTimeout.IMMEDIATELY], and on screen-off when
 * [AppLockConfig.lockOnScreenOff] is set.
 *
 * While an authentication prompt of our own is showing ([beginAuth] … [endAuth]) the app may be pushed to the
 * background by the system credential screen; that must not re-lock it (or the prompt would loop), so backgrounding
 * during a prompt expires only if it lasts longer than [AUTH_GRACE_MILLIS] beyond the timeout.
 */
class LockSession(initiallyLocked: Boolean) {

    /** True while the lock overlay must cover the app. */
    var locked: Boolean = initiallyLocked
        private set

    /** True once the user authenticated in this foreground session (app unlock or a sensitive-screen check). */
    var sensitiveUnlocked: Boolean = false
        private set

    private var backgroundSince: Long? = null
    /** The current background period started while our own prompt was showing (the prompt caused it). */
    private var backgroundForAuth = false
    private var authDepth = 0

    val authInProgress: Boolean get() = authDepth > 0

    /** The app went to the background at [now]. */
    fun onBackground(now: Long, config: AppLockConfig, lockActive: Boolean) {
        backgroundSince = now
        backgroundForAuth = authInProgress
        if (config.autoLock == AutoLockTimeout.IMMEDIATELY && !backgroundForAuth) expire(lockActive)
    }

    /**
     * The app came back to the foreground at [now]. Whether the prompt already finished (its result can arrive before
     * or after this call) does not matter: what counts is why the app went to the background.
     */
    fun onForeground(now: Long, config: AppLockConfig, lockActive: Boolean) {
        val since = backgroundSince ?: return
        backgroundSince = null
        val away = (now - since).coerceAtLeast(0L)
        val limit = if (backgroundForAuth) config.autoLock.millis + AUTH_GRACE_MILLIS else config.autoLock.millis
        backgroundForAuth = false
        if (away >= limit) expire(lockActive)
    }

    /** The screen turned off (whether or not the app was in the foreground). */
    fun onScreenOff(config: AppLockConfig, lockActive: Boolean) {
        if (config.lockOnScreenOff) {
            // The screen going off is never caused by our prompt, so it also ends any auth grace.
            backgroundForAuth = false
            expire(lockActive)
        }
    }

    /** The user authenticated on the lock screen. */
    fun onUnlocked() {
        locked = false
        sensitiveUnlocked = true
    }

    /** The user passed a sensitive-screen check. */
    fun onSensitiveUnlocked() {
        sensitiveUnlocked = true
    }

    /** Locks right away (e.g. "Lock now"); only meaningful when the lock is active. */
    fun lockNow(lockActive: Boolean) = expire(lockActive)

    /**
     * Settings or device state changed. Turning the lock off (or losing every way to enforce it) unlocks; turning it
     * on never locks the user out of the screen they are on — it applies from the next expiry.
     */
    fun onPolicyChanged(lockActive: Boolean) {
        if (!lockActive) locked = false
    }

    fun beginAuth() {
        authDepth++
    }

    fun endAuth() {
        if (authDepth > 0) authDepth--
    }

    private fun expire(lockActive: Boolean) {
        sensitiveUnlocked = false
        if (lockActive) locked = true
    }

    companion object {
        /** Extra background time tolerated while our own prompt (e.g. the system credential screen) is up. */
        const val AUTH_GRACE_MILLIS: Long = 60_000L
    }
}
