package app.dak.ui.lock

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.dak.R
import app.dak.security.PinCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * [PinPad] wired to [app.dak.security.AppLockManager.verifyPin]: shows attempts left, a live lockout countdown
 * (persisted across restarts by the manager), and calls [onVerified] on the correct PIN.
 */
@Composable
fun PinVerifyPanel(
    title: String,
    onVerified: () -> Unit,
    modifier: Modifier = Modifier,
    onFingerprint: (() -> Unit)? = null,
) {
    val manager = rememberAppLockManager()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var resetKey by remember { mutableIntStateOf(0) }
    /** Monotonic time the current lockout ends (0 = none). */
    var lockoutEnds by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(manager) {
        val remaining = manager.pinLockoutRemainingMillis()
        if (remaining > 0) lockoutEnds = SystemClock.elapsedRealtime() + remaining
    }
    LaunchedEffect(lockoutEnds) {
        while (lockoutEnds > 0) {
            now = SystemClock.elapsedRealtime()
            if (now >= lockoutEnds) {
                lockoutEnds = 0L
                message = null
                isError = false
                break
            }
            delay(500)
        }
    }

    val lockedOut = lockoutEnds > 0 && now < lockoutEnds
    val shownMessage = if (lockedOut) {
        context.getString(R.string.lock_pin_locked_out, formatCountdown(lockoutEnds - now))
    } else {
        message
    }

    PinPad(
        title = title,
        message = shownMessage,
        isError = isError || lockedOut,
        enabled = !busy && !lockedOut,
        resetKey = resetKey,
        onFingerprint = onFingerprint,
        modifier = modifier,
        onSubmit = { pin ->
            busy = true
            scope.launch {
                val result = manager.verifyPin(pin)
                busy = false
                when (result) {
                    PinCheck.Correct -> {
                        message = null
                        isError = false
                        onVerified()
                    }
                    is PinCheck.Wrong -> {
                        isError = true
                        resetKey++
                        // Tries before a lockout starts, counting the one that would trigger it.
                        val tries = result.attemptsLeft + 1
                        message = if (tries <= 2) {
                            context.resources.getQuantityString(R.plurals.lock_pin_wrong_attempts, tries, tries)
                        } else {
                            context.getString(R.string.lock_pin_wrong)
                        }
                    }
                    is PinCheck.LockedOut -> {
                        isError = true
                        resetKey++
                        now = SystemClock.elapsedRealtime()
                        lockoutEnds = now + result.remainingMillis
                    }
                    PinCheck.NoPin -> {
                        isError = true
                        message = context.getString(R.string.lock_pin_missing)
                    }
                }
            }
        },
    )
}

/** `m:ss` (or `h:mm:ss` from an hour up), rounded up so it never shows 0:00 while still locked. */
internal fun formatCountdown(millis: Long): String {
    val total = ((millis + 999) / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val locale = Locale.getDefault()
    return if (h > 0) "%d:%02d:%02d".format(locale, h, m, s) else "%d:%02d".format(locale, m, s)
}
