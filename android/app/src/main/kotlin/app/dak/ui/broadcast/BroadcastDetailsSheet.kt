package app.dak.ui.broadcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.broadcast.BroadcastDetails
import app.dak.broadcast.CopyState
import app.dak.broadcast.RecipientView
import app.dak.ui.common.Avatar
import app.dak.ui.common.relativeTime
import app.dak.ui.conversation.DeliveryTick

/**
 * Detail of one sent broadcast: the message, per-recipient state with delivery ticks (from each 1:1 message),
 * Retry on failures, and the replies members sent since (tap opens that person's conversation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastDetailsSheet(
    details: BroadcastDetails?,
    onRetry: (index: Int) -> Unit,
    onOpenThread: (threadId: Long) -> Unit,
    onCancelUnsent: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (details == null) {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        } else {
            DetailsList(details, onRetry, onOpenThread, onCancelUnsent, onDelete)
        }
    }
}

@Composable
private fun DetailsList(
    details: BroadcastDetails,
    onRetry: (index: Int) -> Unit,
    onOpenThread: (threadId: Long) -> Unit,
    onCancelUnsent: () -> Unit,
    onDelete: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.bc_details_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(details.record.template, style = MaterialTheme.typography.bodyLarge)
                Text(statusLine(details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (details.count(CopyState.SCHEDULED) > 0) {
                        TextButton(onClick = onCancelUnsent) { Text(stringResource(R.string.bc_cancel_unsent)) }
                    }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.bc_delete_record)) }
                }
            }
        }
        item(key = "replies-title") { SectionTitle(stringResource(R.string.bc_details_replies)) }
        if (details.replies.isEmpty()) {
            item(key = "no-replies") {
                Text(
                    stringResource(R.string.bc_details_no_replies),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
        }
        items(details.replies) { reply ->
            val open = stringResource(R.string.bc_open_conversation, reply.displayName)
            ListItem(
                modifier = Modifier.clickable(onClickLabel = open) { onOpenThread(reply.threadId) },
                leadingContent = { Avatar(name = reply.displayName, key = reply.address) },
                headlineContent = { Text(reply.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(reply.body, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text(relativeTime(reply.dateMillis), style = MaterialTheme.typography.labelSmall) },
            )
        }
        item(key = "recipients-title") {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.bc_details_recipients))
        }
        items(details.recipients, key = { "p-" + it.index }) { view ->
            RecipientRow(view, onRetry = { onRetry(view.index) }, onOpen = view.threadId?.let { t -> { onOpenThread(t) } })
        }
    }
}

@Composable
private fun RecipientRow(view: RecipientView, onRetry: () -> Unit, onOpen: (() -> Unit)?) {
    val r = view.recipient
    val label = r.displayName.ifBlank { r.address }
    ListItem(
        modifier = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier,
        leadingContent = { Avatar(name = r.displayName.ifBlank { null }, key = r.address) },
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                view.tick?.let { DeliveryTick(it) }
                val state = stateText(view)
                Text(
                    listOfNotNull(state, view.failureReason).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (view.state == CopyState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = if (view.canRetry) {
            { TextButton(onClick = onRetry) { Text(stringResource(R.string.bc_retry)) } }
        } else {
            null
        },
    )
}

@Composable
private fun stateText(view: RecipientView): String {
    val context = LocalContext.current
    return when (view.state) {
        CopyState.SCHEDULED -> stringResource(R.string.bc_state_scheduled, formatWhen(context, view.recipient.sendAtMillis))
        CopyState.SENDING -> stringResource(R.string.bc_state_sending)
        CopyState.SENT -> stringResource(R.string.bc_state_sent)
        CopyState.DELIVERED -> stringResource(R.string.bc_state_delivered)
        CopyState.FAILED -> stringResource(R.string.bc_state_failed)
        CopyState.CANCELLED -> stringResource(R.string.bc_state_cancelled)
    }
}

/** "3 delivered · 5 sent · 2 pending · 1 failed". */
@Composable
internal fun statusLine(details: BroadcastDetails): String = stringResource(
    R.string.bc_status_line,
    details.count(CopyState.DELIVERED),
    details.count(CopyState.SENT),
    details.count(CopyState.SCHEDULED) + details.count(CopyState.SENDING),
    details.count(CopyState.FAILED),
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
    )
}
