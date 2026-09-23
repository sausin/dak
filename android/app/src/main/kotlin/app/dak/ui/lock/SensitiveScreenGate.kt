package app.dak.ui.lock

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.dak.R
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState

/**
 * Wraps a sensitive destination (recycle bin, passbook, backup, automations, forwarding). When
 * Settings → "Protect sensitive screens" is on and the user has not authenticated in this session, shows a locked
 * placeholder and asks with the shared [AppAuthGate] instead of composing [content] — so nothing of the screen (not
 * even its ViewModel) exists until the check passes. With no screen lock and no app PIN there is nothing to check
 * against, and the screen opens.
 *
 * @param title the destination's title, shown on the locked placeholder's top bar.
 */
@Composable
fun SensitiveScreenGate(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val manager = rememberAppLockManager()
    val state by manager.state.collectAsState()
    val required = state.config.protectSensitiveScreens && !state.sensitiveUnlocked
    if (!required) {
        content()
    } else {
        val gate = rememberAppAuthGate()
        var asked by remember { mutableStateOf(false) }
        val prompt = stringResource(R.string.lock_sensitive_prompt, title)
        fun ask() {
            asked = true
            gate.authenticate(prompt) { result ->
                // SUCCESS already marks the session; UNAVAILABLE means nothing can protect it, so let it open.
                if (result == ConfirmResult.UNAVAILABLE) manager.onSensitiveUnlocked()
            }
        }
        // Ask on arrival, but never underneath the app-lock overlay (unlocking there opens this screen anyway).
        LaunchedEffect(state.locked) {
            if (!state.locked && !asked) ask()
        }
        Scaffold(topBar = { DakTopAppBar(title = title, onBack = onBack) }) { padding ->
            EmptyState(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.lock_sensitive_title),
                body = stringResource(R.string.lock_sensitive_body),
                actionLabel = stringResource(R.string.lock_unlock_action),
                onAction = { ask() },
                modifier = Modifier.padding(padding),
            )
        }
    }
}
