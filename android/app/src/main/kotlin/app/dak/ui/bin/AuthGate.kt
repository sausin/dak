package app.dak.ui.bin

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Outcome of an [AuthGate] check. */
enum class AuthResult {
    SUCCESS,
    /** The user cancelled or failed the check. */
    DENIED,
    /** The device has no screen lock or biometric at all; callers decide whether to proceed. */
    UNAVAILABLE,
}

/**
 * Biometric / device-credential confirmation for sensitive surfaces (the recycle bin lock, OTP-forwarding rules).
 * Uses the platform `BiometricPrompt` (Android 10+) with device-credential fallback, and the keyguard
 * confirm-credential screen on Android 8-9, so it works from a plain `ComponentActivity` (androidx.biometric's prompt
 * needs a `FragmentActivity`).
 */
class AuthGate internal constructor(private val start: (title: String, subtitle: String?, (AuthResult) -> Unit) -> Unit) {
    fun authenticate(title: String, subtitle: String? = null, onResult: (AuthResult) -> Unit) = start(title, subtitle, onResult)
}

@Composable
fun rememberAuthGate(): AuthGate {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<((AuthResult) -> Unit)?>(null) }
    val keyguardLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.invoke(if (result.resultCode == Activity.RESULT_OK) AuthResult.SUCCESS else AuthResult.DENIED)
        pending = null
    }
    return remember(context) {
        AuthGate { title, subtitle, onResult ->
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguard == null || !keyguard.isDeviceSecure) {
                onResult(AuthResult.UNAVAILABLE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                showBiometricPrompt(context, title, subtitle, onResult)
            } else {
                @Suppress("DEPRECATION")
                val intent = keyguard.createConfirmDeviceCredentialIntent(title, subtitle)
                if (intent == null) {
                    onResult(AuthResult.UNAVAILABLE)
                } else {
                    pending = onResult
                    keyguardLauncher.launch(intent)
                }
            }
        }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private fun showBiometricPrompt(context: Context, title: String, subtitle: String?, onResult: (AuthResult) -> Unit) {
    val executor = ContextCompat.getMainExecutor(context)
    val builder = BiometricPrompt.Builder(context).setTitle(title)
    if (subtitle != null) builder.setSubtitle(subtitle)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    val prompt = builder.build()
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
            onResult(AuthResult.SUCCESS)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            // The device is secure (checked before), so any error is a refusal, never a bypass.
            onResult(AuthResult.DENIED)
        }
    }
    prompt.authenticate(CancellationSignal(), executor, callback)
}
