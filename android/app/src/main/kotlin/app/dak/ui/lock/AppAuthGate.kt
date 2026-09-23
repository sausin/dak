package app.dak.ui.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.dak.R
import app.dak.security.AppLockRules
import app.dak.security.EffectiveLock

/** Outcome of an [AppAuthGate] confirmation. */
enum class ConfirmResult {
    SUCCESS,
    /** The user cancelled or failed the check. */
    DENIED,
    /** Nothing to authenticate with (no screen lock and no app PIN); callers decide whether to proceed. */
    UNAVAILABLE,
}

/**
 * The shared authenticator for one-off confirmations: sensitive screens, OTP-forwarding rules, changing the app lock.
 * Uses the app PIN when the user chose it (fingerprint first if they enabled that), otherwise the phone's screen lock
 * through the system prompt, and falls back to the app PIN when the phone has no screen lock. A success also counts
 * as authenticating for this session's sensitive screens.
 */
class AppAuthGate internal constructor(private val start: (title: String, subtitle: String?, (ConfirmResult) -> Unit) -> Unit) {
    fun authenticate(title: String, subtitle: String? = null, onResult: (ConfirmResult) -> Unit) = start(title, subtitle, onResult)
}

private class PinRequest(val title: String, val subtitle: String?, val onResult: (ConfirmResult) -> Unit)

/** An [AppAuthGate] for this composition; renders its own PIN dialog when needed. */
@Composable
fun rememberAppAuthGate(): AppAuthGate {
    val manager = rememberAppLockManager()
    val device = rememberDeviceAuthenticator()
    var pinRequest by remember { mutableStateOf<PinRequest?>(null) }
    val usePin = stringResource(R.string.lock_use_pin)

    fun fingerprintShortcut(): Boolean {
        val status = manager.deviceStatus()
        return manager.fingerprintInsteadOfPin() &&
            AppLockRules.fingerprintInsteadOfPinAvailable(manager.hasPin.value, status.strongBiometricEnrolled)
    }

    pinRequest?.let { request ->
        fun finish(result: ConfirmResult) {
            pinRequest = null
            if (result == ConfirmResult.SUCCESS) manager.onSensitiveUnlocked()
            request.onResult(result)
        }
        PinVerifyDialog(
            title = request.title,
            subtitle = request.subtitle,
            onDismiss = { finish(ConfirmResult.DENIED) },
            onVerified = { finish(ConfirmResult.SUCCESS) },
            onFingerprint = if (fingerprintShortcut()) {
                {
                    device.biometricOnly(request.title, request.subtitle, usePin) { outcome ->
                        if (outcome == PromptOutcome.SUCCESS) finish(ConfirmResult.SUCCESS)
                    }
                }
            } else {
                null
            },
        )
    }

    return remember(manager, device, usePin) {
        AppAuthGate { title, subtitle, onResult ->
            val done: (ConfirmResult) -> Unit = { result ->
                if (result == ConfirmResult.SUCCESS) manager.onSensitiveUnlocked()
                onResult(result)
            }
            fun askPin() {
                pinRequest = PinRequest(title, subtitle, onResult)
            }
            when (manager.confirmationLock()) {
                EffectiveLock.NONE -> done(ConfirmResult.UNAVAILABLE)
                EffectiveLock.DEVICE -> device.deviceLock(title, subtitle) { outcome ->
                    when (outcome) {
                        PromptOutcome.SUCCESS -> done(ConfirmResult.SUCCESS)
                        PromptOutcome.UNAVAILABLE -> if (manager.hasPin.value) askPin() else done(ConfirmResult.UNAVAILABLE)
                        else -> done(ConfirmResult.DENIED)
                    }
                }
                EffectiveLock.APP_PIN -> if (fingerprintShortcut()) {
                    device.biometricOnly(title, subtitle, usePin) { outcome ->
                        when (outcome) {
                            PromptOutcome.SUCCESS -> done(ConfirmResult.SUCCESS)
                            PromptOutcome.CANCELLED -> done(ConfirmResult.DENIED)
                            else -> askPin()
                        }
                    }
                } else {
                    askPin()
                }
            }
        }
    }
}

/**
 * The app-PIN check as a dialog. Its window is secure (no screenshots, blank in Recents) and drops obscured touches.
 */
@Composable
fun PinVerifyDialog(
    title: String,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    subtitle: String? = null,
    onFingerprint: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false),
    ) {
        ObscuredTouchGuard()
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 420.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            ) {
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
                    )
                }
                PinVerifyPanel(title = title, onVerified = onVerified, onFingerprint = onFingerprint, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lock_cancel)) }
            }
        }
    }
}
