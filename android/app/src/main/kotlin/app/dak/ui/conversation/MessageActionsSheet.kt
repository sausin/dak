package app.dak.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.core.model.SimInfo
import app.dak.index.MessageItem
import app.dak.ui.common.categoryLabel
import app.dak.ui.common.rememberRelativeTimeFormatter
import app.dak.ui.common.text.BidiText

/** Callbacks of the long-press message sheet. Null entries are hidden. */
class MessageSheetActions(
    val onCopyText: () -> Unit,
    val onCopyCode: (() -> Unit)?,
    val onCopyAmount: (() -> Unit)?,
    val onReply: (() -> Unit)?,
    val onForward: (() -> Unit)?,
    val onStar: () -> Unit,
    val onRetry: (() -> Unit)?,
    val onDelete: () -> Unit,
    val onReportFraud: (() -> Unit)?,
    val onReportSpam: (() -> Unit)?,
    val onInfo: () -> Unit,
)

/**
 * Long-press sheet for one message, actions ordered by how often they are used (copy, copy code / amount, reply,
 * forward, star, retry, delete, report fraud / spam, details). A bottom sheet keeps every action in the thumb zone
 * and each row is a full-width 56dp target; the sheet handles back and predictive back itself.
 */
@Composable
fun MessageActionsSheet(item: MessageItem, actions: MessageSheetActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (item.body.isNotEmpty()) {
                Text(
                    BidiText.isolate(item.body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                HorizontalDivider()
            }
            @Composable
            fun row(icon: ImageVector, label: String, destructive: Boolean = false, action: () -> Unit) =
                SheetRow(icon, label, destructive) { onDismiss(); action() }

            if (item.body.isNotEmpty()) row(Icons.Outlined.ContentCopy, stringResource(R.string.scr_action_copy_text), action = actions.onCopyText)
            actions.onCopyCode?.let { row(Icons.Outlined.Key, stringResource(R.string.action_copy_code), action = it) }
            actions.onCopyAmount?.let { row(Icons.Outlined.Payments, stringResource(R.string.ux_action_copy_amount), action = it) }
            actions.onReply?.let { row(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.ux_action_reply), action = it) }
            actions.onForward?.let { row(Icons.AutoMirrored.Filled.Forward, stringResource(R.string.scr_action_forward), action = it) }
            row(
                if (item.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                stringResource(if (item.starred) R.string.scr_action_unstar else R.string.scr_action_star),
                action = actions.onStar,
            )
            actions.onRetry?.let { row(Icons.Outlined.Refresh, stringResource(R.string.scr_action_retry), action = it) }
            row(Icons.Outlined.Delete, stringResource(R.string.scr_action_delete_to_bin), destructive = true, action = actions.onDelete)
            actions.onReportFraud?.let { row(Icons.Outlined.Warning, stringResource(R.string.safe_action_report_fraud), destructive = true, action = it) }
            actions.onReportSpam?.let { row(Icons.Outlined.Report, stringResource(R.string.scr_action_report_spam), action = it) }
            row(Icons.Outlined.Info, stringResource(R.string.ux_action_info), action = actions.onInfo)
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, destructive: Boolean, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(label) },
    )
}

/** "Message details": sender/recipient, exact time, SIM, SMS or MMS, delivery status and category with confidence. */
@Composable
fun MessageInfoDialog(item: MessageItem, sim: SimInfo?, onDismiss: () -> Unit) {
    val formatter = rememberRelativeTimeFormatter()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ux_action_info), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoLine(stringResource(if (item.isOutgoing) R.string.ux_info_to else R.string.ux_info_from), BidiText.displaySafe(item.address))
                InfoLine(stringResource(R.string.ux_info_time), formatter.formatAbsolute(item.dateMillis))
                if (sim != null) InfoLine(stringResource(R.string.ux_info_sim), sim.displayName.ifBlank { simLabel(sim) })
                InfoLine(stringResource(R.string.ux_info_type), stringResource(if (item.key.kind == MessageKind.MMS) R.string.ux_info_mms else R.string.ux_info_sms))
                statusLabel(item)?.let { InfoLine(stringResource(R.string.ux_info_status), it) }
                item.deliveredAtMillis?.takeIf { item.tickState == TickState.DELIVERED }?.let {
                    InfoLine(stringResource(R.string.tick_info_delivered_at), formatter.formatAbsolute(it))
                }
                if (!item.isOutgoing && item.category != Category.UNKNOWN) {
                    val percent = (item.confidence * 100).toInt().coerceIn(0, 100)
                    InfoLine(stringResource(R.string.ux_info_category), stringResource(R.string.ux_info_confidence, categoryLabel(item.category), percent))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ux_action_close)) } },
    )
}

@Composable
private fun statusLabel(item: MessageItem): String? = when (item.tickState) {
    TickState.SENDING -> stringResource(R.string.scr_status_sending)
    TickState.SENT -> stringResource(R.string.scr_status_sent)
    TickState.DELIVERED -> stringResource(R.string.tick_delivered)
    TickState.FAILED -> stringResource(if (item.box == MessageBox.FAILED) R.string.scr_status_failed else R.string.tick_not_delivered)
    null -> null
}

@Composable
private fun InfoLine(label: String, value: String) {
    // One merged TalkBack node per line: "From, HDFC Bank".
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
