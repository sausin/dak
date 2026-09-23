package app.dak.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.dak.R

/** Callbacks of the thread's selection bar; each acts on the whole selection. Null entries are hidden. */
internal class MessageSelectionActions(
    val onClose: () -> Unit,
    /** Null when nothing selected has text (e.g. only pictures). */
    val onCopy: (() -> Unit)?,
    val onForward: (() -> Unit)?,
    val onDelete: () -> Unit,
    val onSelectAll: () -> Unit,
    /** The single-message sheet (reply, star, details, report…); offered only while exactly one message is selected. */
    val onMessageActions: (() -> Unit)?,
)

/**
 * Contextual top bar while messages are selected, mirroring the inbox's: close, the (announced) count, then copy,
 * forward and delete, with "Message actions" (one message) and "Select all loaded" under More.
 */
@Composable
internal fun MessageSelectionTopBar(count: Int, actions: MessageSelectionActions) {
    var more by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = actions.onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel)) }
        },
        title = {
            Text(
                stringResource(R.string.fold_selected_count, count),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        actions = {
            actions.onCopy?.let { copy ->
                IconButton(onClick = copy) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.scr_action_copy_text)) }
            }
            actions.onForward?.let { forward ->
                IconButton(onClick = forward) { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.scr_action_forward)) }
            }
            IconButton(onClick = actions.onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.ux_action_delete_short)) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.ux_selection_more)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    actions.onMessageActions?.let { open ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.ux_action_message_actions)) }, onClick = { more = false; open() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.ux_action_select_all)) }, onClick = { more = false; actions.onSelectAll() })
                }
            }
        },
    )
}

/** Confirms moving [count] selected messages to the recycle bin (undo is offered afterwards too). */
@Composable
internal fun DeleteSelectedDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.ux_conv_delete_selected_title, count, count)) },
        text = { Text(stringResource(R.string.ux_conv_delete_selected_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
