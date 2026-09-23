package app.dak.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of the app lock for the UI. */
data class AppLockState(
    /** The lock overlay must cover the app. */
    val locked: Boolean,
    /** What unlocks it (or confirms a sensitive action). */
    val effective: EffectiveLock,
    /** The window must carry `FLAG_SECURE`. */
    val secureWindow: Boolean,
    val config: AppLockConfig,
    /** The user authenticated in this session, so sensitive screens open without asking again. */
    val sensitiveUnlocked: Boolean = false,
)

/** Result of checking a typed app PIN. */
sealed interface PinCheck {
    data object Correct : PinCheck
    /** Wrong; [attemptsLeft] more wrong entries are allowed before a lockout starts. */
    data class Wrong(val attemptsLeft: Int) : PinCheck
    /** Too many wrong entries: no attempt is allowed for [remainingMillis]. */
    data class LockedOut(val remainingMillis: Long) : PinCheck
    /** No app PIN is set. */
    data object NoPin : PinCheck
}

/**
 * Owns the app lock: reads the registry's privacy rows, follows the process going to the background
 * ([ProcessLifecycleOwner]) and the screen turning off, and exposes [state] for the overlay in `MainActivity`.
 *
 * Authentication state lives only in memory ([LockSession]), so a new process always starts locked when the lock is
 * on. The PIN is never stored: only its salted PBKDF2 hash, in no-backup storage ([AppLockStore]).
 *
 * [start] must be called on the main thread (MainActivity does it in `onCreate`); it is idempotent.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val store: AppLockStore,
    private val capabilities: DeviceAuthCapabilities,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hasher = PinHasher()

    private var config: AppLockConfig = readConfig()
    private val hasPinState = MutableStateFlow(store.hasPin())
    private var deviceSecure: Boolean = capabilities.isDeviceSecure()
    private val session = LockSession(initiallyLocked = lockActive())
    private val secureHolds = MutableStateFlow(0)
    private val stateFlow = MutableStateFlow(snapshot())
    private var started = false

    /** Current lock state; the overlay shows while [AppLockState.locked]. */
    val state: StateFlow<AppLockState> = stateFlow.asStateFlow()

    /** Whether an app PIN is set on this device. */
    val hasPin: StateFlow<Boolean> = hasPinState.asStateFlow()

    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = update {
                refreshDevice()
                session.onForeground(SystemClock.elapsedRealtime(), config, lockActive())
            }

            override fun onStop(owner: LifecycleOwner) = update {
                session.onBackground(SystemClock.elapsedRealtime(), config, lockActive())
            }
        })
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) update { session.onScreenOff(config, lockActive()) }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            combine(
                settings.observe(DakSettings.appLock),
                settings.observe(DakSettings.autoLockAfter),
                settings.observe(DakSettings.lockOnScreenOff),
                settings.observe(DakSettings.hideInRecents),
                settings.observe(DakSettings.protectSensitiveScreens),
            ) { method, auto, screenOff, recents, sensitive ->
                AppLockConfig(
                    method = LockMethodChoice.fromValue(method),
                    autoLock = AutoLockTimeout.fromValue(auto),
                    lockOnScreenOff = screenOff,
                    recents = RecentsProtection.fromValue(recents),
                    protectSensitiveScreens = sensitive,
                )
            }.distinctUntilChanged().collect { next ->
                update {
                    config = next
                    session.onPolicyChanged(lockActive())
                }
            }
        }
        scope.launch {
            secureHolds.collect { update {} }
        }
    }

    /** Current effective config (the registry values). */
    fun config(): AppLockConfig = config

    /** How a one-off confirmation (sensitive screen, OTP forwarding) should authenticate right now. */
    fun confirmationLock(): EffectiveLock {
        refreshDevice()
        return AppLockRules.confirmationLock(config.method, deviceSecure, hasPinState.value)
    }

    /** Device capabilities, re-read on every call (the user may have changed the screen lock meanwhile). */
    fun deviceStatus(): DeviceAuthStatus = capabilities.status()

    /** Suspends until the app is not locked (used to hold deep links until the user unlocks). */
    suspend fun awaitUnlocked() {
        state.first { !it.locked }
    }

    /** Called after a successful unlock on the lock screen. */
    fun onUnlocked() = update { session.onUnlocked() }

    /** Called after a successful sensitive-screen check. */
    fun onSensitiveUnlocked() = update { session.onSensitiveUnlocked() }

    /** True when a sensitive screen may open without asking again (authenticated in this session). */
    fun isSensitiveUnlocked(): Boolean = session.sensitiveUnlocked

    /** Locks right away (no-op when the lock is not active). */
    fun lockNow() = update { session.lockNow(lockActive()) }

    /**
     * Brackets one of our own authentication prompts: while it shows, being pushed to the background by the system
     * credential screen does not re-lock the app.
     */
    fun beginAuthPrompt() = session.beginAuth()

    fun endAuthPrompt() = session.endAuth()

    /** Keeps `FLAG_SECURE` on while a PIN pad (or other secret entry) is visible; call the returned function to release. */
    fun holdSecureWindow(): () -> Unit {
        secureHolds.value = secureHolds.value + 1
        var released = false
        return {
            if (!released) {
                released = true
                secureHolds.value = (secureHolds.value - 1).coerceAtLeast(0)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ App PIN

    /** Milliseconds until another PIN attempt is allowed (0 = now). */
    suspend fun pinLockoutRemainingMillis(): Long = withContext(Dispatchers.IO) { store.lockout().remainingMillis(clock()) }

    /** Checks [pin] (wiped afterwards), updating the persisted failure counter. Runs PBKDF2 off the main thread. */
    suspend fun verifyPin(pin: CharArray): PinCheck = withContext(Dispatchers.Default) {
        try {
            val record = store.pinRecord() ?: return@withContext PinCheck.NoPin
            val lockout = store.lockout()
            val now = clock()
            val remaining = lockout.remainingMillis(now)
            if (remaining > 0) return@withContext PinCheck.LockedOut(remaining)
            if (hasher.verify(pin, record)) {
                store.setLockout(LockoutState.NONE)
                PinCheck.Correct
            } else {
                val next = lockout.afterFailure(now)
                store.setLockout(next)
                val nextRemaining = next.remainingMillis(now)
                if (nextRemaining > 0) PinCheck.LockedOut(nextRemaining) else PinCheck.Wrong(next.attemptsBeforeLockout)
            }
        } finally {
            pin.fill('\u0000')
        }
    }

    /** Stores a new app PIN (wiped afterwards). Returns false when [pin] is not 4–8 digits. */
    suspend fun setPin(pin: CharArray): Boolean {
        val ok = withContext(Dispatchers.Default) {
            try {
                if (!PinPolicy.isValid(pin)) return@withContext false
                store.setPinRecord(hasher.hash(pin))
                true
            } finally {
                pin.fill('\u0000')
            }
        }
        if (ok) update { hasPinState.value = true }
        return ok
    }

    /** Removes the app PIN. If the lock was set to "App PIN" it falls back per [AppLockRules.effectiveLock]. */
    suspend fun clearPin() {
        withContext(Dispatchers.IO) { store.clearPin() }
        update {
            hasPinState.value = false
            session.onPolicyChanged(lockActive())
        }
    }

    fun fingerprintInsteadOfPin(): Boolean = store.fingerprintInsteadOfPin()

    suspend fun setFingerprintInsteadOfPin(enabled: Boolean) = withContext(Dispatchers.IO) { store.setFingerprintInsteadOfPin(enabled) }

    // ------------------------------------------------------------------------------------------------ internals

    private fun readConfig() = AppLockConfig(
        method = LockMethodChoice.fromValue(settings.get(DakSettings.appLock)),
        autoLock = AutoLockTimeout.fromValue(settings.get(DakSettings.autoLockAfter)),
        lockOnScreenOff = settings.get(DakSettings.lockOnScreenOff),
        recents = RecentsProtection.fromValue(settings.get(DakSettings.hideInRecents)),
        protectSensitiveScreens = settings.get(DakSettings.protectSensitiveScreens),
    )

    private fun effective(): EffectiveLock = AppLockRules.effectiveLock(config.method, deviceSecure, hasPinState.value)

    private fun lockActive(): Boolean = effective() != EffectiveLock.NONE

    private fun refreshDevice() {
        val secure = capabilities.isDeviceSecure()
        if (secure != deviceSecure) {
            deviceSecure = secure
            session.onPolicyChanged(lockActive())
        }
    }

    private fun snapshot(): AppLockState {
        val active = lockActive()
        return AppLockState(
            locked = session.locked,
            effective = effective(),
            secureWindow = AppLockRules.secureWindow(config, active, session.locked) || secureHolds.value > 0,
            config = config,
            sensitiveUnlocked = session.sensitiveUnlocked,
        )
    }

    private fun update(block: () -> Unit) {
        synchronized(this) {
            block()
            stateFlow.value = snapshot()
        }
    }

    private fun clock(): ClockReading = ClockReading(
        wallMillis = System.currentTimeMillis(),
        elapsedMillis = SystemClock.elapsedRealtime(),
        bootId = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1),
    )
}
