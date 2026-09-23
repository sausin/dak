package app.dak.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.index.ConversationSummary

/** Top bar while conversations are selected: close and the (announced) selection count. */
@Composable
internal fun SelectionTopBar(count: Int, onClose: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel)) }
        },
        title = {
            Text(
                stringResource(R.string.fold_selected_count, count),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
    )
}

/** Callbacks of the selection action bar; each one acts on the whole selection. */
internal interface SelectionActions {
    fun archive()
    fun delete()
    fun markRead()
    fun pin()
    fun mute()
    fun fold()
    fun selectAll()
}

/**
 * Bottom action bar for the selected conversations, in the thumb zone: Archive, Delete, Read, Pin, and More (mute,
 * fold together, select all). Labels follow the selection ("Unarchive" when everything selected is archived).
 */
@Composable
internal fun SelectionBottomBar(selected: Collection<ConversationSummary>, actions: SelectionActions) {
    var more by remember { mutableStateOf(false) }
    val allArchived = selected.isNotEmpty() && selected.all { it.archived }
    val allPinned = selected.isNotEmpty() && selected.all { it.pinned }
    val allMuted = selected.isNotEmpty() && selected.all { it.muted }
    val anyUnread = selected.any { it.unreadCount > 0 }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Outlined.Archive, stringResource(if (allArchived) R.string.scr_action_unarchive else R.string.scr_action_archive), onClick = actions::archive)
            BarAction(Icons.Outlined.Delete, stringResource(R.string.ux_action_delete_short), onClick = actions::delete)
            BarAction(Icons.Outlined.CheckCircle, stringResource(R.string.ux_action_read_short), enabled = anyUnread, onClick = actions::markRead)
            BarAction(Icons.Outlined.PushPin, stringResource(if (allPinned) R.string.scr_action_unpin else R.string.scr_action_pin), onClick = actions::pin)
            Box {
                BarAction(Icons.Outlined.MoreVert, stringResource(R.string.ux_selection_more)) { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (allMuted) R.string.scr_action_unmute else R.string.scr_action_mute)) },
                        onClick = { more = false; actions.mute() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.fold_action_fold_together)) },
                        enabled = selected.size >= 2,
                        onClick = { more = false; actions.fold() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ux_action_select_all)) },
                        onClick = { more = false; actions.selectAll() },
                    )
                }
            }
        }
    }
}

/** Icon over a short label; at least 64×56dp so it is an easy thumb target. */
@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
