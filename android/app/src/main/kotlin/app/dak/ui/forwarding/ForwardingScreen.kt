package app.dak.ui.forwarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.dak.automation.ForwardingHold
import app.dak.automation.ForwardingStatusNotifier
import app.dak.automations.forwarding.ForwardingPolicy
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.forwarding.ForwardingStatus
import app.dak.automations.rule.Rule
import app.dak.automations.safety.ValidationIssue
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.bin.AuthResult
import app.dak.ui.bin.rememberAuthGate
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.WarningBanner
import app.dak.ui.theme.DakTheme
import kotlinx.coroutines.launch

/**
 * Auto-forwarding: time-boxed rules that forward chosen channels (e.g. HDFC Bank, Zerodha, Income Tax Dept) to a
 * contact (e.g. your CA) from your own SIM. Free: nothing leaves the phone except the SMS itself. Because forwarding
 * bank SMS and OTPs is a classic scam setup, rules default to one hour; a longer or open-ended period, extending a
 * rule, including OTPs or a risky-looking recipient shows a scam warning ([ForwardingRiskDialog]) and then needs the
 * fingerprint / screen lock ([rememberAuthGate], the shared app-lock authenticator). Forwarding also needs the app lock
 * to be set up ([AppLockNeededDialog] otherwise). Ended rules are listed apart and can be used again (a fresh period of
 * the same length, through the same checks); each rule has its history of what was sent.
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
    // The rule as stored when the editor opened (null for a new rule), to tell an extension from a new period.
    var original by remember { mutableStateOf<ForwardingSpec?>(null) }
    var issues by remember { mutableStateOf<List<ValidationIssue>>(emptyList()) }
    var incomplete by remember { mutableStateOf(false) }
    // A pending high-risk confirmation: the warning is showing; Continue runs the biometric check, then [onConfirmed].
    var pendingRisk by remember { mutableStateOf<PendingRisk?>(null) }
    // "Set up app lock first": forwarding cannot be turned on without it.
    var lockNeeded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val confirmTitle = stringResource(R.string.fw_confirm_forward_title)
    val confirmSubtitle = stringResource(R.string.fw_confirm_forward_subtitle)
    val noLockText = stringResource(R.string.scr_auto_needs_screen_lock)
    val contactsText = stringResource(R.string.fw_contacts_permission_needed)
    val context = LocalContext.current
    val now = System.currentTimeMillis()

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun confirmThen(onConfirmed: () -> Unit) {
        auth.authenticate(confirmTitle, confirmSubtitle) { result ->
            when (result) {
                AuthResult.SUCCESS -> onConfirmed()
                AuthResult.UNAVAILABLE -> scope.launch { snackbar.showSnackbar(noLockText) }
                AuthResult.DENIED -> Unit
            }
        }
    }

    /** Acts on a save/enable check: [onReady] right away, [onConfirmed] after the warning and biometric check. */
    fun handle(check: ForwardingCheck, onReady: (Rule) -> Unit, onConfirmed: (Rule) -> Unit) {
        when (check) {
            is ForwardingCheck.Ready -> onReady(check.rule)
            ForwardingCheck.NeedsAppLock -> lockNeeded = true
            ForwardingCheck.Incomplete -> incomplete = true
            is ForwardingCheck.Invalid -> issues = check.issues
            ForwardingCheck.NeedsContactsAccess -> {
                contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                scope.launch { snackbar.showSnackbar(contactsText) }
            }
            is ForwardingCheck.NotAContact -> scope.launch {
                snackbar.showSnackbar(context.getString(R.string.fw_enable_contact_missing, check.recipient))
            }
            is ForwardingCheck.NeedsConfirmation -> pendingRisk = PendingRisk(check.risk) { onConfirmed(check.rule) }
        }
    }

    fun enable(row: ForwardingRow) {
        scope.launch {
            handle(
                viewModel.checkEnable(row),
                // Re-checked when applied (after the warning and fingerprint): the lock may have gone meanwhile.
                onReady = { if (!viewModel.setEnabled(row, true)) lockNeeded = true },
                onConfirmed = { if (!viewModel.setEnabled(row, true, confirmed = true)) lockNeeded = true },
            )
        }
    }

    /** Shows where a re-used rule now runs until, or the app-lock dialog when it could not be turned on. */
    fun reused(ok: Boolean, rule: Rule) {
        if (!ok) {
            lockNeeded = true
            return
        }
        val end = ForwardingSpec.fromRule(rule)?.endMillis
        val text = if (end == null) {
            context.getString(R.string.fw_use_again_done_open)
        } else {
            context.getString(R.string.fw_use_again_done, ForwardingStatusNotifier.formatInstant(context, end))
        }
        scope.launch { snackbar.showSnackbar(text) }
    }

    /** "Use again" for an ended rule: a fresh period of the same length from now. */
    fun reuse(row: ForwardingRow) {
        scope.launch {
            handle(
                viewModel.checkReuse(row),
                onReady = { rule -> reused(viewModel.save(rule), rule) },
                onConfirmed = { rule -> reused(viewModel.confirmAndSave(rule), rule) },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            DakTopAppBar(title = stringResource(R.string.fw_title), onBack = { navigator.back() }) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.fw_history_menu)) },
                        leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            navigator.navigate(Routes.automationHistory())
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { issues = emptyList(); incomplete = false; original = null; editing = newSpec() },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.fw_new_rule)) },
            )
        },
    ) { padding ->
        val list = rows.orEmpty()
        val (ended, current) = list.partition { it.spec.status(now) == ForwardingStatus.ENDED }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.fw_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (list.any { it.spec.includeOtp && it.spec.status(now) == ForwardingStatus.ACTIVE && !it.unconfirmed }) {
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
            items(current, key = { it.id }) { row ->
                ForwardingRuleRow(
                    row = row,
                    nowMillis = now,
                    onEdit = {
                        issues = emptyList()
                        incomplete = false
                        original = row.spec
                        editing = row.spec
                    },
                    onToggle = { enabled -> if (enabled) enable(row) else viewModel.setEnabled(row, false) },
                    onConfirm = { enable(row) },
                    onUseAgain = { reuse(row) },
                    onHistory = { navigator.navigate(Routes.automationHistory(row.id)) },
                    onDelete = { viewModel.delete(row) },
                )
                HorizontalDivider()
            }
            if (ended.isNotEmpty()) {
                item(key = "ended-header") {
                    Text(
                        stringResource(R.string.fw_section_ended),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(ended, key = { it.id }) { row ->
                    ForwardingRuleRow(
                        row = row,
                        nowMillis = now,
                        onEdit = {
                            issues = emptyList()
                            incomplete = false
                            original = row.spec
                            // Editing an ended rule (e.g. to change its dates) turns it back on when saved.
                            editing = row.spec.copy(enabled = true)
                        },
                        onToggle = { enabled -> if (enabled) reuse(row) },
                        onConfirm = { reuse(row) },
                        onUseAgain = { reuse(row) },
                        onHistory = { navigator.navigate(Routes.automationHistory(row.id)) },
                        onDelete = { viewModel.delete(row) },
                    )
                    HorizontalDivider()
                }
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
                    issues = emptyList()
                    scope.launch {
                        handle(
                            viewModel.checkSave(spec, original),
                            onReady = { rule ->
                                if (viewModel.save(rule)) editing = null else lockNeeded = true
                            },
                            onConfirmed = { rule ->
                                if (viewModel.confirmAndSave(rule)) editing = null else lockNeeded = true
                            },
                        )
                    }
                },
                onCancel = { editing = null },
            )
        }
    }

    pendingRisk?.let { pending ->
        ForwardingRiskDialog(
            risk = pending.risk,
            onContinue = {
                pendingRisk = null
                confirmThen(pending.onConfirmed)
            },
            onDismiss = { pendingRisk = null },
        )
    }

    if (lockNeeded) {
        AppLockNeededDialog(
            onSetUp = {
                lockNeeded = false
                editing = null
                navigator.navigate(Routes.APP_LOCK)
            },
            onDismiss = { lockNeeded = false },
        )
    }
}

/** The scam warning to show for [risk]; [onConfirmed] runs after it and a successful biometric check. */
private class PendingRisk(val risk: ForwardingRisk, val onConfirmed: () -> Unit)

/** A new rule: now for one hour ([ForwardingPolicy.defaultWindow]), OTPs excluded, the default template. */
private fun newSpec(): ForwardingSpec {
    val (start, end) = ForwardingPolicy.defaultWindow(System.currentTimeMillis())
    return ForwardingSpec(startMillis = start, endMillis = end)
}

@Composable
private fun ForwardingRuleRow(
    row: ForwardingRow,
    nowMillis: Long,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onUseAgain: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    val spec = row.spec
    val status = spec.status(nowMillis)
    val ended = status == ForwardingStatus.ENDED
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
                    statusText(row, status),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        row.hold != null -> MaterialTheme.colorScheme.error
                        status == ForwardingStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                        status == ForwardingStatus.ENDED -> MaterialTheme.colorScheme.error
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
                if (ended) {
                    TextButton(onClick = onUseAgain) { Text(stringResource(R.string.fw_row_use_again)) }
                }
                if (row.unconfirmed && !ended) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.fw_row_needs_confirmation),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onConfirm) { Text(stringResource(R.string.fw_row_confirm)) }
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onHistory) {
                    Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.fw_history_row_cd, spec.name))
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                // Switching an ended rule on uses it again: a fresh period of the same length from now.
                Switch(checked = spec.enabled && !ended, onCheckedChange = onToggle)
            }
        },
    )
}

@Composable
private fun statusText(row: ForwardingRow, status: ForwardingStatus): String {
    val context = LocalContext.current
    val spec = row.spec
    val end = spec.endMillis
    val period = when {
        end == null -> stringResource(R.string.fw_period_until_stopped)
        else -> stringResource(
            R.string.fw_period_range,
            ForwardingStatusNotifier.formatInstant(context, spec.startMillis),
            ForwardingStatusNotifier.formatInstant(context, end),
        )
    }
    val label = stringResource(
        when {
            status == ForwardingStatus.ENDED -> R.string.fw_status_ended
            row.hold == ForwardingHold.CONTACT_MISSING -> R.string.fw_status_paused_contact
            row.hold == ForwardingHold.CONTACTS_ACCESS -> R.string.fw_status_paused_access
            row.hold == ForwardingHold.LOCK_OFF -> R.string.fw_status_off_lock_off
            row.hold == ForwardingHold.SCREEN_LOCK_REMOVED -> R.string.fw_status_off_screen_lock
            row.hold == ForwardingHold.LOCK_NEEDED -> R.string.fw_status_off_lock_needed
            status == ForwardingStatus.ACTIVE -> R.string.fw_status_active
            status == ForwardingStatus.SCHEDULED -> R.string.fw_status_scheduled
            else -> R.string.fw_status_paused
        },
    )
    return "$label · $period"
}
