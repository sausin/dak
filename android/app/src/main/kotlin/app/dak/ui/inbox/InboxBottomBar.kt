package app.dak.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ForwardToInbox
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.navigation.Routes

/**
 * The inbox's bottom bar, in the thumb zone of tall phones: "More places" (a sheet with every secondary screen),
 * a search pill and the new-message FAB. Replaces the top search bar and top overflow menu.
 */
@Composable
internal fun InboxBottomBar(onPlaces: () -> Unit, onSearch: () -> Unit, onCompose: () -> Unit) {
    BottomAppBar(
        actions = {
            IconButton(onClick = onPlaces) {
                Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.ux_inbox_places))
            }
            SearchPill(onClick = onSearch, modifier = Modifier.weight(1f).padding(end = 12.dp))
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCompose,
                containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.scr_inbox_new_message))
            }
        },
    )
}

@Composable
private fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.scr_search_hint)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A secondary destination reachable from the inbox. */
private data class Place(val label: Int, val icon: ImageVector, val route: String)

private val PLACES = listOf(
    Place(R.string.scr_passbook_title, Icons.Outlined.AccountBalanceWallet, Routes.PASSBOOK),
    Place(R.string.scr_bin_title, Icons.Outlined.DeleteOutline, Routes.BIN),
    Place(R.string.scr_automations_title, Icons.Outlined.AutoAwesome, Routes.AUTOMATIONS),
    Place(R.string.fw_menu_forwarding, Icons.Outlined.ForwardToInbox, Routes.FORWARDING),
    Place(R.string.fw_menu_birthdays, Icons.Outlined.Cake, Routes.BIRTHDAYS),
    Place(R.string.fold_action_manage, Icons.Outlined.Category, Routes.SENDER_GROUPS),
    Place(R.string.scr_blocked_title, Icons.Outlined.Block, Routes.BLOCKED),
    Place(R.string.scr_backup_title, Icons.Outlined.Backup, Routes.BACKUP),
)

/**
 * Bottom sheet listing the inbox's secondary screens (what used to be the top overflow menu), with icons and
 * full-width 56dp rows. Dismisses with back (predictive back is handled by the sheet itself).
 */
@Composable
internal fun PlacesSheet(onDismiss: () -> Unit, onNavigate: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.ux_inbox_places_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(PLACES, key = { it.route }) { place -> PlaceRow(stringResource(place.label), place.icon) { onNavigate(place.route) } }
            item(key = "settings") {
                PlaceRow(stringResource(R.string.settings_title), Icons.Outlined.Settings) { onNavigate(Routes.settings()) }
            }
        }
    }
}

@Composable
private fun PlaceRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
    )
}
