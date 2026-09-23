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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.dak.R
import app.dak.security.PinPolicy
import kotlinx.coroutines.launch

/**
 * Choose-and-confirm flow for a new app PIN (4–8 digits, obvious PINs refused). Stores only its hash through
 * [app.dak.security.AppLockManager.setPin], then calls [onDone]. Secure, tapjacking-guarded dialog window.
 */
@Composable
fun PinSetupDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val manager = rememberAppLockManager()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<CharArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var resetKey by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) { onDispose { first?.fill('\u0000') } }

    fun restart(error: String) {
        first?.fill('\u0000')
        first = null
        message = error
        isError = true
        resetKey++
    }

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
                val confirming = first != null
                PinPad(
                    title = stringResource(if (confirming) R.string.lock_setup_confirm_title else R.string.lock_setup_title),
                    message = message ?: stringResource(if (confirming) R.string.lock_setup_confirm_hint else R.string.lock_setup_hint),
                    isError = isError,
                    enabled = !busy,
                    resetKey = resetKey,
                    modifier = Modifier.fillMaxWidth(),
                    onSubmit = { pin ->
                        val chosen = first
                        when {
                            chosen == null && PinPolicy.isEasyToGuess(pin) -> {
                                pin.fill('\u0000')
                                restart(context.getString(R.string.lock_setup_too_easy))
                            }
                            chosen == null -> {
                                first = pin
                                message = null
                                isError = false
                            }
                            !PinPolicy.matches(chosen, pin) -> {
                                pin.fill('\u0000')
                                restart(context.getString(R.string.lock_setup_mismatch))
                            }
                            else -> {
                                chosen.fill('\u0000')
                                first = null
                                busy = true
                                scope.launch {
                                    val ok = manager.setPin(pin)
                                    busy = false
                                    if (ok) onDone() else restart(context.getString(R.string.lock_setup_invalid))
                                }
                            }
                        }
                    },
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lock_cancel)) }
            }
        }
    }
}
