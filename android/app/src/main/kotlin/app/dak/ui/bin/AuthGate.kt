package app.dak.ui.bin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.dak.ui.lock.ConfirmResult
import app.dak.ui.lock.rememberAppAuthGate

/** Outcome of an [AuthGate] check. */
enum class AuthResult {
    SUCCESS,
    /** The user cancelled or failed the check. */
    DENIED,
    /** The device has no screen lock and no app PIN; callers decide whether to proceed. */
    UNAVAILABLE,
}

/**
 * Confirmation for sensitive surfaces (the recycle bin lock, OTP-forwarding rules). Delegates to the shared
 * app-lock authenticator ([app.dak.ui.lock.AppAuthGate]): the app PIN when the user chose one (fingerprint first if
 * enabled), otherwise the phone's screen lock through androidx.biometric's prompt (keyguard screen on Android 8.x).
 */
class AuthGate internal constructor(private val start: (title: String, subtitle: String?, (AuthResult) -> Unit) -> Unit) {
    fun authenticate(title: String, subtitle: String? = null, onResult: (AuthResult) -> Unit) = start(title, subtitle, onResult)
}

@Composable
fun rememberAuthGate(): AuthGate {
    val gate = rememberAppAuthGate()
    return remember(gate) {
        AuthGate { title, subtitle, onResult ->
            gate.authenticate(title, subtitle) { result ->
                onResult(
                    when (result) {
                        ConfirmResult.SUCCESS -> AuthResult.SUCCESS
                        ConfirmResult.DENIED -> AuthResult.DENIED
                        ConfirmResult.UNAVAILABLE -> AuthResult.UNAVAILABLE
                    },
                )
            }
        }
    }
}
