package app.dak.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.dak.security.AppLockManager
import app.dak.security.DeviceAuthCapabilities

/** Outcome of a system authentication prompt. */
enum class PromptOutcome {
    SUCCESS,
    /** Dismissed, cancelled or failed; nothing was bypassed. */
    CANCELLED,
    /** The user tapped the prompt's "Use PIN" button (biometric-only prompt in front of the app PIN). */
    USE_PIN,
    /** Nothing to authenticate with (no screen lock / no biometrics / no hardware). */
    UNAVAILABLE,
    /** Too many failed biometric attempts; the system locked biometrics out for a while. */
    LOCKED_OUT,
}

/**
 * Runs the system authentication UI:
 * - [deviceLock]: fingerprint/face or the phone's PIN/pattern/password. androidx.biometric `BiometricPrompt` on API
 *   28+ (authenticators per [DeviceAuthCapabilities.deviceLockAuthenticators]); the keyguard confirm-credential screen
 *   on API 26–27.
 * - [biometricOnly]: a Class 3 biometric with a "Use PIN" button, as a shortcut in front of the app PIN.
 *
 * Every prompt is bracketed with [AppLockManager.beginAuthPrompt]/[AppLockManager.endAuthPrompt] so the system
 * credential screen pushing Dak to the background does not re-lock it. Results arrive on the main thread.
 */
class DeviceAuthenticator internal constructor(
    private val context: Context,
    private val manager: AppLockManager,
    private val launchKeyguard: (Intent, (Boolean) -> Unit) -> Boolean,
) {

    fun deviceLock(title: String, subtitle: String?, onResult: (PromptOutcome) -> Unit) {
        if (!manager.deviceStatus().deviceSecure) {
            onResult(PromptOutcome.UNAVAILABLE)
            return
        }
        if (DeviceAuthCapabilities.usesBiometricPromptForDeviceLock) {
            val info = runCatching {
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(DeviceAuthCapabilities.deviceLockAuthenticators())
                    .setConfirmationRequired(false)
                    .build()
            }.getOrNull()
            if (info == null) onResult(PromptOutcome.UNAVAILABLE) else showPrompt(info, onResult)
        } else {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            @Suppress("DEPRECATION")
            val intent = keyguard?.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) {
                onResult(PromptOutcome.UNAVAILABLE)
                return
            }
            manager.beginAuthPrompt()
            val launched = launchKeyguard(intent) { ok ->
                manager.endAuthPrompt()
                onResult(if (ok) PromptOutcome.SUCCESS else PromptOutcome.CANCELLED)
            }
            if (!launched) {
                manager.endAuthPrompt()
                onResult(PromptOutcome.UNAVAILABLE)
            }
        }
    }

    fun biometricOnly(title: String, subtitle: String?, usePinLabel: String, onResult: (PromptOutcome) -> Unit) {
        if (!manager.deviceStatus().strongBiometricEnrolled) {
            onResult(PromptOutcome.UNAVAILABLE)
            return
        }
        val info = runCatching {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(DeviceAuthCapabilities.BIOMETRIC_ONLY)
                .setNegativeButtonText(usePinLabel)
                .setConfirmationRequired(false)
                .build()
        }.getOrNull()
        if (info == null) onResult(PromptOutcome.UNAVAILABLE) else showPrompt(info, onResult)
    }

    private fun showPrompt(info: BiometricPrompt.PromptInfo, onResult: (PromptOutcome) -> Unit) {
        val activity = context.findFragmentActivity()
        if (activity == null || activity.isFinishing) {
            onResult(PromptOutcome.UNAVAILABLE)
            return
        }
        var delivered = false
        fun deliver(outcome: PromptOutcome) {
            if (delivered) return
            delivered = true
            manager.endAuthPrompt()
            onResult(outcome)
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                deliver(PromptOutcome.SUCCESS)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                deliver(outcomeFor(errorCode))
            }
            // onAuthenticationFailed: one rejected attempt; the prompt stays up and lets the user retry.
        }
        manager.beginAuthPrompt()
        try {
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
        } catch (e: RuntimeException) {
            // Unsupported authenticator combination or the activity's state was already saved.
            deliver(PromptOutcome.UNAVAILABLE)
        }
    }

    private companion object {
        fun outcomeFor(errorCode: Int): PromptOutcome = when (errorCode) {
            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> PromptOutcome.USE_PIN
            BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> PromptOutcome.LOCKED_OUT
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            -> PromptOutcome.UNAVAILABLE
            // User cancel, system cancel (e.g. screen off), timeout, …: a refusal, never a bypass.
            else -> PromptOutcome.CANCELLED
        }
    }
}

/** A [DeviceAuthenticator] bound to the current activity (registers the keyguard result launcher). */
@Composable
fun rememberDeviceAuthenticator(): DeviceAuthenticator {
    val context = LocalContext.current
    val manager = rememberAppLockManager()
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pending[0]
        pending[0] = null
        callback?.invoke(result.resultCode == Activity.RESULT_OK)
    }
    return remember(context, manager, launcher) {
        DeviceAuthenticator(context, manager) { intent, onDone ->
            pending[0] = onDone
            runCatching { launcher.launch(intent) }.isSuccess.also { if (!it) pending[0] = null }
        }
    }
}
