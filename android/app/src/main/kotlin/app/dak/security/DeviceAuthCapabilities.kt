package app.dak.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone can authenticate with right now. */
data class DeviceAuthStatus(
    /** A screen lock (PIN, pattern or password) is set. */
    val deviceSecure: Boolean,
    /** A fingerprint/face usable by the system prompt is enrolled (the class the device-lock prompt accepts). */
    val biometricEnrolled: Boolean,
    /** A Class 3 (strong) biometric is enrolled: required for "fingerprint instead of app PIN". */
    val strongBiometricEnrolled: Boolean,
    /** The phone has biometric hardware at all (to tell "none enrolled" from "not supported"). */
    val biometricHardware: Boolean,
)

/**
 * Checks with `BiometricManager.canAuthenticate` and `KeyguardManager.isDeviceSecure` what the device supports, and
 * picks authenticator combinations that androidx.biometric accepts on each API level:
 * - API 30+: `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`;
 * - API 28–29: `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` (STRONG combined with DEVICE_CREDENTIAL is unsupported there);
 * - API 26–27: no system prompt for the device credential; the caller uses the keyguard confirm-credential screen.
 */
@Singleton
class DeviceAuthCapabilities @Inject constructor(@ApplicationContext private val context: Context) {

    fun status(): DeviceAuthStatus {
        val manager = BiometricManager.from(context)
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val secure = keyguard?.isDeviceSecure == true
        val promptBiometric = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG else BIOMETRIC_WEAK
        val promptResult = manager.canAuthenticate(promptBiometric)
        val strongResult = manager.canAuthenticate(BIOMETRIC_STRONG)
        val hardware = promptResult != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE &&
            promptResult != BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE &&
            promptResult != BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        return DeviceAuthStatus(
            deviceSecure = secure,
            biometricEnrolled = promptResult == BiometricManager.BIOMETRIC_SUCCESS,
            strongBiometricEnrolled = strongResult == BiometricManager.BIOMETRIC_SUCCESS,
            biometricHardware = hardware,
        )
    }

    fun isDeviceSecure(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true

    companion object {
        /** True when the device-lock prompt goes through androidx.biometric (API 28+); below, use the keyguard intent. */
        val usesBiometricPromptForDeviceLock: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        /** Authenticators for "fingerprint/face or the phone's screen lock" on this API level (API 28+ only). */
        fun deviceLockAuthenticators(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        /** Authenticators for the biometric-only shortcut in front of the app PIN. */
        const val BIOMETRIC_ONLY: Int = BIOMETRIC_STRONG
    }
}
