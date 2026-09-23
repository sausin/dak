package app.dak.ui.inbox

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.dak.R

/** Top bar while conversations are selected for "Fold together". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoldSelectionTopBar(count: Int, onClose: () -> Unit, onFold: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel)) }
        },
        title = { Text(stringResource(R.string.fold_selected_count, count)) },
        actions = {
            TextButton(onClick = onFold, enabled = count >= 2) { Text(stringResource(R.string.fold_action_fold_together)) }
        },
    )
}
