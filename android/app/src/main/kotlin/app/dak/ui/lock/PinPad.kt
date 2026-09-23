package app.dak.ui.lock

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.security.PinPolicy

/**
 * Numeric PIN pad with large keys and key-press haptics. Digits are kept in a private [CharArray] (never a `String`)
 * and handed to [onSubmit] as a copy that the receiver wipes; the buffer is cleared on submit and when this leaves
 * the composition. Entered digits are shown as dots only, and accessibility announces only how many were typed.
 *
 * Callers must keep the window secure while this is visible ([SecureWhileShown]; dialogs use a secure policy).
 *
 * @param message a hint or an error line under the dots ([isError] colours it).
 * @param enabled false while verifying or during a lockout: keys do nothing.
 * @param onFingerprint when set, shows a "Use fingerprint" button.
 * @param resetKey change it to clear the entry (e.g. after a wrong PIN).
 */
@Composable
fun PinPad(
    title: String,
    message: String?,
    isError: Boolean,
    enabled: Boolean,
    onSubmit: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    onFingerprint: (() -> Unit)? = null,
    resetKey: Int = 0,
) {
    val view = LocalView.current
    val buffer = remember { CharArray(PinPolicy.MAX_LENGTH) }
    var length by remember { mutableIntStateOf(0) }

    fun clear() {
        buffer.fill('\u0000')
        length = 0
    }
    DisposableEffect(Unit) { onDispose { buffer.fill('\u0000') } }
    LaunchedEffect(resetKey) { if (resetKey != 0) clear() }

    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    fun digit(c: Char) {
        if (!enabled || length >= PinPolicy.MAX_LENGTH) return
        tap()
        buffer[length] = c
        length += 1
    }
    fun backspace() {
        if (!enabled || length == 0) return
        tap()
        length -= 1
        buffer[length] = '\u0000'
    }
    fun submit() {
        if (!enabled || length < PinPolicy.MIN_LENGTH) return
        tap()
        val pin = buffer.copyOf(length)
        clear()
        onSubmit(pin)
    }

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        PinDots(length)
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Column(
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
            verticalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    row.forEach { c -> DigitKey(c, enabled) { digit(c) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                IconKey(
                    icon = Icons.AutoMirrored.Outlined.Backspace,
                    label = stringResource(R.string.lock_pin_delete),
                    enabled = enabled && length > 0,
                    onClick = ::backspace,
                    onLongClick = { if (enabled) clear() },
                )
                DigitKey('0', enabled) { digit('0') }
                IconKey(
                    icon = Icons.Outlined.Check,
                    label = stringResource(R.string.lock_pin_enter),
                    enabled = enabled && length >= PinPolicy.MIN_LENGTH,
                    onClick = ::submit,
                    emphasized = true,
                )
            }
        }
        if (onFingerprint != null) {
            TextButton(onClick = onFingerprint) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.lock_use_fingerprint))
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun PinDots(length: Int) {
    val description = stringResource(R.string.lock_pin_entered, length)
    val slots = maxOf(PinPolicy.MIN_LENGTH, length)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        repeat(slots) { i ->
            val filled = i < length
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .then(
                        if (filled) Modifier.background(MaterialTheme.colorScheme.primary)
                        else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    ),
            )
        }
    }
}

@Composable
private fun DigitKey(c: Char, enabled: Boolean, onClick: () -> Unit) {
    KeySurface(enabled = enabled, label = c.toString(), onClick = onClick) {
        Text(c.toString(), style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun IconKey(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    emphasized: Boolean = false,
) {
    KeySurface(enabled = enabled, label = label, onClick = onClick, onLongClick = onLongClick, emphasized = emphasized) {
        Icon(icon, contentDescription = null)
    }
}

@Composable
private fun KeySurface(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    emphasized: Boolean = false,
    content: @Composable () -> Unit,
) {
    val container = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(KeySize)
            .clip(CircleShape)
            .background(container)
            .alpha(if (enabled) 1f else 0.6f)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
    ) {
        content()
    }
}

private val KeySize: Dp = 72.dp
private val KeyGap: Dp = 20.dp
