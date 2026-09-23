package app.dak.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.security.AppLockConfig
import app.dak.security.AppLockManager
import app.dak.security.AutoLockTimeout
import app.dak.security.DeviceAuthStatus
import app.dak.security.EffectiveLock
import app.dak.security.LockMethodChoice
import app.dak.security.RecentsProtection
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the App lock screen shows. */
data class AppLockUiState(
    val config: AppLockConfig = AppLockConfig(),
    val effective: EffectiveLock = EffectiveLock.NONE,
    val hasPin: Boolean = false,
    val device: DeviceAuthStatus = DeviceAuthStatus(deviceSecure = false, biometricEnrolled = false, strongBiometricEnrolled = false, biometricHardware = false),
    val fingerprintInsteadOfPin: Boolean = false,
    val lockScreenPrivacy: String = "hideContent",
)

/**
 * Backs the App lock screen. Verification (device prompt, PIN setup, confirming before turning protection down)
 * happens in the UI with the shared authenticators before these setters are called.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val manager: AppLockManager,
    private val settings: SettingsStore,
) : ViewModel() {

    private val tick = MutableStateFlow(0)

    val state: StateFlow<AppLockUiState> = combine(
        manager.state,
        manager.hasPin,
        settings.observe(DakSettings.lockScreenPrivacy),
        tick,
    ) { lock, hasPin, privacy, _ ->
        AppLockUiState(
            config = lock.config,
            effective = lock.effective,
            hasPin = hasPin,
            device = manager.deviceStatus(),
            fingerprintInsteadOfPin = manager.fingerprintInsteadOfPin(),
            lockScreenPrivacy = privacy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLockUiState())

    /** Re-reads device capabilities (after returning from system security settings). */
    fun refresh() {
        manager.refresh()
        tick.value += 1
    }

    /** Returns true when lock-screen notifications were switched to "Sender only" as part of turning the lock on. */
    fun setMethod(choice: LockMethodChoice): Boolean = manager.setMethod(choice)

    fun setAutoLock(timeout: AutoLockTimeout) = settings.set(DakSettings.autoLockAfter, timeout.value)

    fun setLockOnScreenOff(enabled: Boolean) = settings.set(DakSettings.lockOnScreenOff, enabled)

    fun setRecents(protection: RecentsProtection) = settings.set(DakSettings.hideInRecents, protection.value)

    fun setProtectSensitive(enabled: Boolean) = settings.set(DakSettings.protectSensitiveScreens, enabled)

    fun setFingerprintInsteadOfPin(enabled: Boolean) {
        viewModelScope.launch {
            manager.setFingerprintInsteadOfPin(enabled)
            tick.value += 1
        }
    }

    fun removePin() {
        viewModelScope.launch { manager.clearPin() }
    }

    fun lockNow() = manager.lockNow()
}
