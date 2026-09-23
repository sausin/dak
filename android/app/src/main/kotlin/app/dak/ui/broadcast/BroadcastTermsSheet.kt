package app.dak.ui.broadcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.automations.broadcast.BroadcastLimits

/**
 * The one-time "Use broadcasts with care" sheet. [onAccept] only becomes available once the box is ticked;
 * dismissing the sheet any other way calls [onDecline]. Acceptance is stored with the terms version by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastTermsSheet(onAccept: () -> Unit, onDecline: () -> Unit) {
    var ticked by rememberSaveable { mutableStateOf(false) }
    var showAup by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDecline, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.bc_terms_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.bc_terms_intro), style = MaterialTheme.typography.bodyMedium)
            Bullet(stringResource(R.string.bc_terms_personal))
            Bullet(stringResource(R.string.bc_terms_expect))
            Bullet(stringResource(R.string.bc_terms_spam))
            Bullet(stringResource(R.string.bc_terms_india))
            Bullet(stringResource(R.string.bc_terms_throttle))
            Bullet(stringResource(R.string.bc_terms_charges))
            Bullet(
                stringResource(
                    R.string.bc_terms_limits,
                    BroadcastLimits.HARD_MAX_RECIPIENTS,
                    BroadcastLimits.HARD_MAX_MESSAGES_PER_DAY,
                ),
            )
            TextButton(onClick = { showAup = true }) { Text(stringResource(R.string.bc_terms_read_full)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Checkbox) { ticked = !ticked }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = ticked, onCheckedChange = null)
                Text(stringResource(R.string.bc_terms_checkbox), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDecline) { Text(stringResource(R.string.bc_terms_decline)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAccept, enabled = ticked) { Text(stringResource(R.string.bc_terms_accept)) }
            }
        }
    }
    if (showAup) AcceptableUseDialog(onDismiss = { showAup = false })
}

/** The acceptable-use text (mirrors docs/terms-acceptable-use.md), shown in-app with no network. */
@Composable
fun AcceptableUseDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bc_aup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.bc_aup_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bc_close)) } },
    )
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
