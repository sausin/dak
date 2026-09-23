package app.dak.ui.forwarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ForwardToInbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.forwarding.ForwardingStatus
import app.dak.automations.safety.ValidationIssue
import app.dak.navigation.DakNavigator
import app.dak.ui.automations.SaveCheck
import app.dak.ui.bin.AuthResult
import app.dak.ui.bin.rememberAuthGate
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.WarningBanner
import app.dak.ui.theme.DakTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Auto-forwarding: time-boxed rules that forward chosen channels (e.g. HDFC Bank, Zerodha, Income Tax Dept) to
 * someone (e.g. your CA) from your own SIM. Free: nothing leaves the phone except the SMS itself. OTPs are excluded
 * unless the user opts in with a biometric confirmation; such rules carry a persistent warning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardingScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: ForwardingViewModel = hiltViewModel()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val sims by viewModel.simList.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val auth = rememberAuthGate()
    var editing by remember { mutableStateOf<ForwardingSpec?>(null) }
    var issues by remember { mutableStateOf<List<ValidationIssue>>(emptyList()) }
    var incomplete by remember { mutableStateOf(false) }
    val confirmTitle = stringResource(R.string.fw_confirm_otp_title)
    val confirmSubtitle = stringResource(R.string.fw_confirm_otp_subtitle)
    val noLockText = stringResource(R.string.scr_auto_needs_screen_lock)
    val now = System.currentTimeMillis()

    fun confirmThen(onConfirmed: () -> Unit) {
        auth.authenticate(confirmTitle, confirmSubtitle) { result ->
            when (result) {
                AuthResult.SUCCESS -> onConfirmed()
                AuthResult.UNAVAILABLE -> scope.launch { snackbar.showSnackbar(noLockText) }
                AuthResult.DENIED -> Unit
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { DakTopAppBar(title = stringResource(R.string.fw_title), onBack = { navigator.back() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { issues = emptyList(); incomplete = false; editing = newSpec() },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.fw_new_rule)) },
            )
        },
    ) { padding ->
        val list = rows.orEmpty()
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.fw_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (list.any { it.spec.includeOtp && it.spec.status(now) == ForwardingStatus.ACTIVE }) {
                item {
                    WarningBanner(title = stringResource(R.string.fw_otp_notice_title), body = stringResource(R.string.fw_otp_notice_body))
                }
            }
            if (rows != null && list.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.ForwardToInbox,
                        title = stringResource(R.string.fw_empty_title),
                        body = stringResource(R.string.fw_empty_body),
                    )
                }
            }
            items(list, key = { it.id }) { row ->
                ForwardingRuleRow(
                    row = row,
                    nowMillis = now,
                    onEdit = {
                        issues = emptyList()
                        incomplete = false
                        // Editing an ended rule (usually to extend it) turns it back on when saved.
                        editing = if (row.spec.status(now) == ForwardingStatus.ENDED) row.spec.copy(enabled = true) else row.spec
                    },
                    onToggle = { enabled ->
                        if (enabled && viewModel.needsConfirmationToEnable(row)) {
                            confirmThen { viewModel.setEnabled(row, true, confirmed = true) }
                        } else {
                            viewModel.setEnabled(row, enabled)
                        }
                    },
                    onDelete = { viewModel.delete(row) },
                )
                HorizontalDivider()
            }
            item { Row(Modifier.padding(48.dp)) {} }
        }
    }

    editing?.let { spec ->
        ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ForwardingEditor(
                spec = spec,
                sims = sims.filter { it.isActive },
                issues = issues,
                incomplete = incomplete,
                viewModel = viewModel,
                onChange = { editing = it },
                onSave = {
                    incomplete = false
                    when (val check = viewModel.trySave(spec)) {
                        SaveCheck.Saved -> editing = null
                        SaveCheck.Incomplete -> incomplete = true
                        is SaveCheck.Invalid -> issues = check.issues
                        is SaveCheck.NeedsConfirmation -> confirmThen {
                            viewModel.confirmAndSave(check.rule)
                            editing = null
                        }
                    }
                },
                onCancel = { editing = null },
            )
        }
    }
}

/** A new rule: today for 30 days, OTPs excluded, the default "Fwd from {sender}: {body}" template. */
private fun newSpec(): ForwardingSpec {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return ForwardingSpec(
        startMillis = startOfDay(today, zone),
        endMillis = endOfDay(today.plusDays(DEFAULT_DAYS), zone),
    )
}

internal fun startOfDay(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

internal fun endOfDay(date: LocalDate, zone: ZoneId): Long = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

internal fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

private const val DEFAULT_DAYS = 30L

@Composable
private fun ForwardingRuleRow(
    row: ForwardingRow,
    nowMillis: Long,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val spec = row.spec
    val status = spec.status(nowMillis)
    ListItem(
        modifier = Modifier.clickable(onClick = onEdit),
        headlineContent = { Text(spec.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    stringResource(R.string.fw_row_route, spec.sources.joinToString(", ") { it.name }, spec.recipients.joinToString(", ") { it.label }),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusText(spec, status),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (status) {
                        ForwardingStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                        ForwardingStatus.ENDED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (spec.includeOtp) {
                    Text(
                        stringResource(R.string.fw_row_includes_otp),
                        style = MaterialTheme.typography.labelSmall,
                        color = DakTheme.colors.warning.accent,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                // An ended rule cannot be switched back on; edit its dates instead.
                Switch(checked = spec.enabled && status != ForwardingStatus.ENDED, onCheckedChange = onToggle, enabled = status != ForwardingStatus.ENDED)
            }
        },
    )
}

@Composable
private fun statusText(spec: ForwardingSpec, status: ForwardingStatus): String {
    val end = spec.endMillis
    val period = when {
        end == null -> stringResource(R.string.fw_period_until_stopped)
        else -> stringResource(R.string.fw_period_range, formatDate(spec.startMillis), formatDate(end))
    }
    val label = stringResource(
        when (status) {
            ForwardingStatus.ACTIVE -> R.string.fw_status_active
            ForwardingStatus.SCHEDULED -> R.string.fw_status_scheduled
            ForwardingStatus.PAUSED -> R.string.fw_status_paused
            ForwardingStatus.ENDED -> R.string.fw_status_ended
        },
    )
    return "$label · $period"
}
