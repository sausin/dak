package app.dak.ui.forwarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.dak.R

/**
 * "Set up app lock first": shown when the user tries to turn on auto-forwarding or another automation that sends
 * messages off the phone while no app lock is set up (see `OutboundAutomationGuard`). [onSetUp] opens the App lock
 * screen.
 */
@Composable
fun AppLockNeededDialog(onSetUp: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.fw_lock_needed_title)) },
        text = { Text(stringResource(R.string.fw_lock_needed_body)) },
        confirmButton = { TextButton(onClick = onSetUp) { Text(stringResource(R.string.fw_lock_needed_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.fw_lock_needed_not_now)) } },
    )
}
