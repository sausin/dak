package app.dak.ui.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.dak.R
import app.dak.security.AppLockRules
import app.dak.security.AppLockState
import app.dak.security.EffectiveLock
import kotlinx.coroutines.launch

/**
 * Full-screen lock drawn above the NavHost while [AppLockState.locked]. The NavHost stays composed underneath (its
 * semantics cleared by the caller) so the back stack and any deep link opened meanwhile are intact after unlocking.
 *
 * Shows only static content (app mark, "Dak is locked"): no message preview can leak to the screen or to
 * accessibility services. Its window is secure while shown ([AppLockState.secureWindow] is always true when locked),
 * touches are dropped while another app's window obscures it, and it swallows every touch and the back key (back
 * sends Dak to the background instead of revealing what is underneath).
 *
 * Device lock: an "Unlock Dak" button, and the system prompt starts by itself whenever the app resumes (not again
 * right after the user dismissed it). App PIN: the PIN pad, with the fingerprint shortcut if enabled.
 */
@Composable
fun AppLockOverlay(state: AppLockState, modifier: Modifier = Modifier) {
    val manager = rememberAppLockManager()
    val device = rememberDeviceAuthenticator()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var promptShowing by remember { mutableStateOf(false) }
    var promptEndedAt by remember { mutableLongStateOf(0L) }

    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)
    val usePin = stringResource(R.string.lock_use_pin)
    val unavailable = stringResource(R.string.lock_device_unavailable)
    val biometricLockedOut = stringResource(R.string.lock_biometric_locked_out)

    ObscuredTouchGuard()
    BackHandler { context.findActivity()?.moveTaskToBack(true) }
    LaunchedEffect(Unit) {
        // Whatever had focus underneath (e.g. the composer) must not keep the keyboard open over the lock.
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    val fingerprintShortcut = state.effective == EffectiveLock.APP_PIN && manager.fingerprintInsteadOfPin() &&
        AppLockRules.fingerprintInsteadOfPinAvailable(manager.hasPin.value, manager.deviceStatus().strongBiometricEnrolled)

    fun finishPrompt() {
        promptShowing = false
        promptEndedAt = SystemClock.elapsedRealtime()
    }

    fun promptDevice() {
        if (promptShowing) return
        promptShowing = true
        message = null
        device.deviceLock(title, subtitle) { outcome ->
            finishPrompt()
            when (outcome) {
                PromptOutcome.SUCCESS -> manager.onUnlocked()
                PromptOutcome.UNAVAILABLE -> {
                    // The screen lock was removed meanwhile: recompute (falls back to the app PIN, if any).
                    message = unavailable
                    manager.refresh()
                }
                PromptOutcome.LOCKED_OUT -> message = biometricLockedOut
                PromptOutcome.CANCELLED, PromptOutcome.USE_PIN -> Unit
            }
        }
    }

    fun promptFingerprint() {
        if (promptShowing) return
        promptShowing = true
        message = null
        device.biometricOnly(title, subtitle, usePin) { outcome ->
            finishPrompt()
            when (outcome) {
                PromptOutcome.SUCCESS -> manager.onUnlocked()
                PromptOutcome.LOCKED_OUT -> message = biometricLockedOut
                else -> Unit // back to the PIN pad
            }
        }
    }

    // Auto-start the prompt on every resume, except the resume caused by closing our own prompt.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val justClosed = SystemClock.elapsedRealtime() - promptEndedAt < AUTO_PROMPT_COOLDOWN_MILLIS
        if (!justClosed) {
            // Posted, not run inside the lifecycle dispatch: the prompt commits a fragment transaction.
            scope.launch {
                when {
                    state.effective == EffectiveLock.DEVICE -> promptDevice()
                    fingerprintShortcut -> promptFingerprint()
                    else -> Unit
                }
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            // Swallow every touch so nothing underneath can be operated blind.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        SecureWhileShown()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
        ) {
            AppMark()
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            when (state.effective) {
                EffectiveLock.APP_PIN -> {
                    PinVerifyPanel(
                        title = stringResource(R.string.lock_enter_pin),
                        onVerified = { manager.onUnlocked() },
                        onFingerprint = if (fingerprintShortcut) ({ promptFingerprint() }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    message?.let { LockMessage(it) }
                }
                EffectiveLock.DEVICE, EffectiveLock.NONE -> {
                    Text(
                        stringResource(R.string.lock_body_device),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    message?.let { LockMessage(it) }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { promptDevice() }, enabled = !promptShowing) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.lock_unlock_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun LockMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

/** The Dak launcher mark (adaptive icon layers drawn by hand: painterResource cannot load adaptive icons). */
@Composable
internal fun AppMark(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(colorResource(R.color.ic_launcher_background)),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(128.dp),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val AUTO_PROMPT_COOLDOWN_MILLIS = 2_000L
