package app.dak.ui.broadcast

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.automations.broadcast.BroadcastPlanner
import app.dak.broadcast.BroadcastDetails
import app.dak.navigation.DakNavigator
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.relativeTime
import java.util.Calendar

/**
 * One broadcast list as a thread: every broadcast sent to it (tap for per-recipient ticks, failures with Retry, and
 * replies), and a composer with `{firstName}` / `{name}` placeholders, a per-recipient preview, SIM choice and
 * "Send later" (one time only; broadcasts never recur). Every send goes through [BroadcastConfirmDialog].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BroadcastListScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: BroadcastListViewModel = hiltViewModel()
    val context = LocalContext.current
    val list by viewModel.list.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val sims by viewModel.simList.collectAsStateWithLifecycle()
    val subId by viewModel.subId.collectAsStateWithLifecycle()
    val scheduledAt by viewModel.scheduledAt.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val needsConsent by viewModel.needsConsent.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by rememberSaveable { mutableStateOf(false) }
    var openRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var contactsTick by remember { mutableIntStateOf(0) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsTick++ }
    val listState = rememberLazyListState()
    LaunchedEffect(openRecordId) { viewModel.openDetails(openRecordId) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BroadcastEvent.Queued -> snackbar.showSnackbar(
                    context.getString(if (event.scheduled) R.string.bc_scheduled_snack else R.string.bc_sent_snack, event.count),
                )
                BroadcastEvent.Failed -> snackbar.showSnackbar(context.getString(R.string.bc_send_failed))
                is BroadcastEvent.OpenConversation -> navigator.openConversation(event.conversationId)
            }
        }
    }
    val items = history.orEmpty()
    LaunchedEffect(items.size) { if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1) }

    val current = list
    Scaffold(
        modifier = modifier,
        topBar = {
            DakTopAppBar(
                title = current?.name ?: stringResource(R.string.bc_title),
                onBack = { navigator.back() },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { editing = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.bc_edit_list))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.bc_list_missing), style = MaterialTheme.typography.bodyLarge)
            }
        } else Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val count = current.members.size
            Text(
                quantityString(R.plurals.bc_members_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                if (history != null && items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.bc_thread_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                items(items, key = { it.record.id }) { details ->
                    SentBubble(details) { openRecordId = details.record.id }
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.bc_placeholders_label),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    val first = stringResource(R.string.bc_insert_first_name)
                    val full = stringResource(R.string.bc_insert_name)
                    AssistChip(onClick = { viewModel.insertPlaceholder(first) }, label = { Text(first) })
                    AssistChip(onClick = { viewModel.insertPlaceholder(full) }, label = { Text(full) })
                }
                val sample = current.members.firstOrNull { BroadcastPlanner.realName(it).isNotEmpty() } ?: current.members.firstOrNull()
                if (sample != null && viewModel.text.contains('{')) {
                    Text(
                        stringResource(
                            R.string.bc_preview_for,
                            sample.displayName.ifBlank { sample.address },
                            BroadcastPlanner.render(viewModel.text, sample),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (sims.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (sim in sims) {
                            FilterChip(
                                selected = subId == sim.subId,
                                onClick = { viewModel.selectSim(if (subId == sim.subId) null else sim.subId) },
                                label = { Text(sim.displayName) },
                            )
                        }
                    }
                }
                val at = scheduledAt
                if (at != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null)
                        Text(
                            stringResource(R.string.bc_scheduled_for, formatWhen(context, at)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        TextButton(onClick = { viewModel.schedule(null) }) { Text(stringResource(R.string.bc_send_now_instead)) }
                    }
                }
                OutlinedTextField(
                    value = viewModel.text,
                    onValueChange = viewModel::onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.bc_composer_hint)) },
                    minLines = 2,
                    maxLines = 6,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickDateTime(context, scheduledAt) { viewModel.schedule(it) } }) {
                        Text(stringResource(R.string.bc_send_later))
                    }
                    Box(Modifier.weight(1f))
                    Button(onClick = viewModel::requestSend, enabled = viewModel.text.isNotBlank() && !busy) {
                        Text(stringResource(R.string.bc_send))
                    }
                }
            }
        }
    }

    preview?.let { p ->
        val simName = sims.firstOrNull { it.subId == p.subId }?.displayName
        BroadcastConfirmDialog(p, simName = simName, onConfirm = viewModel::confirmSend, onDismiss = viewModel::dismissPreview)
    }
    if (openRecordId != null) {
        val shown = selected?.takeIf { it.record.id == openRecordId }
        BroadcastDetailsSheet(
            details = shown,
            onRetry = { index -> shown?.let { viewModel.retry(it.record.id, index) } },
            onOpenThread = { threadId ->
                openRecordId = null
                viewModel.openThread(threadId)
            },
            onCancelUnsent = { shown?.let { viewModel.cancelUnsent(it.record.id) } },
            onDelete = {
                shown?.let { viewModel.deleteRecord(it.record.id) }
                openRecordId = null
            },
            onDismiss = { openRecordId = null },
        )
    }
    if (editing && current != null) {
        val canRead = remember(contactsTick) { viewModel.canReadContacts }
        BroadcastListEditor(
            initial = current,
            canReadContacts = canRead,
            onRequestContacts = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
            search = viewModel::searchContacts,
            onSave = { name, members ->
                viewModel.saveList(name, members)
                editing = false
            },
            onDelete = {
                editing = false
                viewModel.deleteList()
                navigator.back()
            },
            onDismiss = { editing = false },
        )
    }
    if (needsConsent) {
        BroadcastTermsSheet(onAccept = viewModel::acceptTerms, onDecline = { navigator.back() })
    }
}

/** One sent broadcast in the list's thread: the text, when, delivery summary and reply count. */
@Composable
private fun SentBubble(details: BroadcastDetails, onClick: () -> Unit) {
    val context = LocalContext.current
    val record = details.record
    val now = System.currentTimeMillis()
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 320.dp).clickable(onClick = onClick),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(record.template, style = MaterialTheme.typography.bodyLarge)
                val whenText = if (record.startsAtMillis > now) {
                    stringResource(R.string.bc_scheduled_for, formatWhen(context, record.startsAtMillis))
                } else {
                    relativeTime(record.createdAt)
                }
                Text(whenText, style = MaterialTheme.typography.labelSmall)
                Text(statusLine(details), style = MaterialTheme.typography.labelSmall)
                if (details.replies.isNotEmpty()) {
                    Text(
                        quantityString(R.plurals.bc_reply_count, details.replies.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Platform date then time pickers (no experimental Compose pickers); only future times are offered by date. */
private fun pickDateTime(context: Context, initial: Long?, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply {
        timeInMillis = initial ?: (System.currentTimeMillis() + DEFAULT_OFFSET_MILLIS)
    }
    val dateDialog = DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val picked = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(picked.timeInMillis)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(context),
            ).show()
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH),
    )
    dateDialog.datePicker.minDate = System.currentTimeMillis() - 1_000L
    dateDialog.datePicker.maxDate = System.currentTimeMillis() + MAX_AHEAD_MILLIS
    dateDialog.show()
}

private const val DEFAULT_OFFSET_MILLIS = 60L * 60 * 1000
private const val MAX_AHEAD_MILLIS = 30L * 24 * 60 * 60 * 1000
