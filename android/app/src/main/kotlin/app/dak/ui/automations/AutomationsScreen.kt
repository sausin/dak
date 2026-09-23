package app.dak.ui.automations

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.ForwardToInbox
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.automation.ForwardingHold
import app.dak.automation.ForwardingStatusNotifier
import app.dak.automation.RuleEntry
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.forwarding.ForwardingStatus
import app.dak.automations.rule.RelayChannel
import app.dak.automations.rule.isExpired
import app.dak.automations.safety.ValidationIssue
import app.dak.core.model.Category
import app.dak.core.model.SimInfo
import app.dak.index.repo.ScheduledSend
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.premium.Feature
import app.dak.ui.bin.AuthResult
import app.dak.ui.bin.rememberAuthGate
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.LockChip
import app.dak.ui.common.WarningBanner
import app.dak.ui.common.categoryLabel
import app.dak.ui.common.rememberRelativeTimeFormatter
import app.dak.ui.forwarding.AppLockNeededDialog
import app.dak.ui.settings.UpgradeSheet
import app.dak.ui.theme.DakTheme
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Automations: rules with enable toggles, a simple rule editor for the common triggers and actions, presets,
 * scheduled sends, and premium actions shown locked. OTP-forwarding rules require a biometric confirmation; rules that
 * send messages off the phone need the app lock ([AppLockNeededDialog] otherwise). Rules whose period ended are listed
 * apart and switch back on with a fresh period; each rule has its history of what was sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: AutomationsViewModel = hiltViewModel()
    val context = LocalContext.current
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val scheduled by viewModel.scheduled.collectAsStateWithLifecycle()
    val focusGone by viewModel.focusGone.collectAsStateWithLifecycle()
    val focusId = viewModel.focusScheduledId
    val focused = focusId?.let { id -> scheduled.firstOrNull { it.id == id } }
    val sims by viewModel.simList.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val auth = rememberAuthGate()
    var editing by remember { mutableStateOf<RuleDraft?>(null) }
    var upgradeFor by remember { mutableStateOf<Feature?>(null) }
    var issues by remember { mutableStateOf<List<ValidationIssue>>(emptyList()) }
    var lockNeeded by remember { mutableStateOf(false) }
    val confirmTitle = stringResource(R.string.scr_auto_confirm_title)
    val confirmSubtitle = stringResource(R.string.scr_auto_confirm_subtitle)
    val noLockText = stringResource(R.string.scr_auto_needs_screen_lock)

    fun confirmThen(onConfirmed: () -> Unit) {
        auth.authenticate(confirmTitle, confirmSubtitle) { result ->
            when (result) {
                AuthResult.SUCCESS -> onConfirmed()
                AuthResult.UNAVAILABLE -> scope.launch { snackbar.showSnackbar(noLockText) }
                AuthResult.DENIED -> Unit
            }
        }
    }

    /** Date and time pickers, then moves [send] ("Change time", or the heads-up's "Pick time"). */
    fun changeTime(send: ScheduledSend) {
        pickDateTime(context, send.sendAtMillis) { at ->
            viewModel.moveScheduled(send, at) { moved ->
                val text = if (moved) {
                    context.getString(
                        R.string.sched_moved,
                        DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                    )
                } else {
                    context.getString(R.string.sched_time_in_past)
                }
                scope.launch { snackbar.showSnackbar(text) }
            }
        }
    }

    LaunchedEffect(focused?.id) {
        if (focused != null && viewModel.consumePickTime()) changeTime(focused)
    }

    fun turnOn(entry: RuleEntry) {
        val rule = entry.rule
        val spec = rule?.let { ForwardingSpec.fromRule(it) }
        // Ended forwarding rules are used again from the Forwarding screen (contact and risky-recipient checks).
        if (spec != null && spec.status(System.currentTimeMillis()) == ForwardingStatus.ENDED) {
            navigator.navigate(Routes.FORWARDING)
            return
        }
        when (val check = viewModel.checkEnable(entry)) {
            EnableCheck.Unreadable -> Unit
            EnableCheck.NeedsAppLock -> lockNeeded = true
            is EnableCheck.NeedsConfirmation -> confirmThen {
                // Re-checked when applied: the lock may have gone while the fingerprint prompt was up.
                if (!viewModel.enable(entry, check.rule, confirmed = true)) lockNeeded = true
            }
            is EnableCheck.Ready -> if (!viewModel.enable(entry, check.rule)) lockNeeded = true
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            DakTopAppBar(title = stringResource(R.string.scr_automations_title), onBack = { navigator.back() }) {
                TextButton(onClick = { viewModel.addPresets() }) { Text(stringResource(R.string.scr_auto_add_examples)) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { issues = emptyList(); editing = RuleDraft() },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.scr_auto_new_rule)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            // Opened from a scheduled message's heads-up: that send first, highlighted.
            if (focusId != null) {
                item(key = "focus-title") { SectionTitle(R.string.sched_focus_title) }
                if (focused != null) {
                    item(key = "focus-row") {
                        ScheduledRow(
                            send = focused,
                            sims = sims,
                            highlighted = true,
                            onChangeTime = { changeTime(focused) },
                            onCancel = { viewModel.cancelScheduled(focused) },
                        )
                    }
                } else if (focusGone) {
                    item(key = "focus-gone") {
                        Text(
                            stringResource(R.string.sched_focus_gone),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (!viewModel.canScheduleExact()) {
                item {
                    WarningBanner(
                        title = stringResource(R.string.scr_auto_exact_title),
                        body = stringResource(R.string.scr_auto_exact_body),
                        actionLabel = stringResource(R.string.action_fix),
                        onAction = {
                            viewModel.exactAlarmIntent()?.let { intent ->
                                try { context.startActivity(intent) } catch (e: ActivityNotFoundException) { }
                            }
                        },
                    )
                }
            }
            item {
                ShortcutRow(
                    icon = { Icon(Icons.AutoMirrored.Outlined.ForwardToInbox, contentDescription = null) },
                    title = stringResource(R.string.fw_title),
                    summary = stringResource(R.string.fw_shortcut_summary),
                    onClick = { navigator.navigate(Routes.FORWARDING) },
                )
            }
            item {
                ShortcutRow(
                    icon = { Icon(Icons.Outlined.Cake, contentDescription = null) },
                    title = stringResource(R.string.fw_bd_title),
                    summary = stringResource(R.string.fw_bd_shortcut_summary),
                    onClick = { navigator.navigate(Routes.BIRTHDAYS) },
                )
            }
            item {
                ShortcutRow(
                    icon = { Icon(Icons.Outlined.Campaign, contentDescription = null) },
                    title = stringResource(R.string.bc_title),
                    summary = stringResource(R.string.bc_shortcut_summary),
                    onClick = { navigator.navigate(Routes.BROADCASTS) },
                )
            }
            item { SectionTitle(R.string.scr_auto_rules) }
            val list = entries.orEmpty()
            if (entries != null && list.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.scr_auto_no_rules),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            val now = System.currentTimeMillis()
            val (ended, current) = list.partition { it.rule?.isExpired(now) == true }
            val rowItem: @Composable (RuleEntry, Boolean) -> Unit = { entry, isEnded ->
                RuleRow(
                    entry = entry,
                    hold = viewModel.holdOf(entry),
                    ended = isEnded,
                    onToggle = { enabled -> if (enabled) turnOn(entry) else viewModel.disable(entry) },
                    onHistory = { navigator.navigate(Routes.automationHistory(entry.stored.id)) },
                    onEdit = {
                        val draft = entry.rule?.let { RuleDraft.fromRule(it) }
                        if (entry.rule?.let { ForwardingSpec.isForwarding(it) } == true) {
                            navigator.navigate(Routes.FORWARDING)
                        } else if (draft != null) {
                            issues = emptyList()
                            editing = draft
                        } else {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.scr_auto_advanced_rule)) }
                        }
                    },
                    onDelete = { viewModel.delete(entry) },
                )
                HorizontalDivider()
            }
            items(current, key = { it.stored.id }) { entry -> rowItem(entry, false) }
            if (ended.isNotEmpty()) {
                item(key = "ended-header") { SectionTitle(R.string.fw_section_ended) }
                items(ended, key = { it.stored.id }) { entry -> rowItem(entry, true) }
            }
            item { SectionTitle(R.string.scr_auto_scheduled) }
            if (scheduled.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.scr_auto_no_scheduled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(scheduled, key = { "s" + it.id }) { send ->
                ScheduledRow(
                    send = send,
                    sims = sims,
                    onChangeTime = { changeTime(send) },
                    onCancel = { viewModel.cancelScheduled(send) },
                )
            }
            item { Row(Modifier.padding(48.dp)) {} }
        }
    }

    editing?.let { draft ->
        ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            RuleEditor(
                draft = draft,
                sims = sims.filter { it.isActive },
                issues = issues,
                isLocked = viewModel::isLocked,
                onChange = { editing = it },
                onLocked = { upgradeFor = it },
                onSave = {
                    when (val check = viewModel.trySave(draft)) {
                        SaveCheck.Saved -> editing = null
                        SaveCheck.Incomplete -> issues = listOf(ValidationIssue.NoActions)
                        is SaveCheck.Invalid -> issues = check.issues
                        SaveCheck.NeedsAppLock -> lockNeeded = true
                        is SaveCheck.NeedsConfirmation -> confirmThen {
                            if (viewModel.confirmAndSave(check.rule)) editing = null else lockNeeded = true
                        }
                    }
                },
                onCancel = { editing = null },
            )
        }
    }
    upgradeFor?.let { UpgradeSheet(feature = it, onDismiss = { upgradeFor = null }) }
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

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun RuleRow(
    entry: RuleEntry,
    hold: ForwardingHold?,
    ended: Boolean,
    onToggle: (Boolean) -> Unit,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onEdit),
        headlineContent = { Text(entry.stored.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    if (entry.rule == null) stringResource(R.string.scr_auto_unreadable) else describe(entry),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                holdText(hold)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                if (ended && entry.rule != null) {
                    TextButton(onClick = { onToggle(true) }) { Text(stringResource(R.string.fw_row_use_again)) }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onHistory) {
                    Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.fw_history_row_cd, entry.stored.name))
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                Switch(checked = entry.stored.enabled && !ended, onCheckedChange = onToggle, enabled = entry.rule != null)
            }
        },
    )
}

/** Why the app turned a rule off by itself, for its row. */
@Composable
private fun holdText(hold: ForwardingHold?): String? = when (hold) {
    null -> null
    ForwardingHold.CONTACT_MISSING -> stringResource(R.string.fw_status_paused_contact)
    ForwardingHold.CONTACTS_ACCESS -> stringResource(R.string.fw_status_paused_access)
    ForwardingHold.LOCK_OFF -> stringResource(R.string.fw_status_off_lock_off)
    ForwardingHold.SCREEN_LOCK_REMOVED -> stringResource(R.string.fw_status_off_screen_lock)
    ForwardingHold.LOCK_NEEDED -> stringResource(R.string.fw_status_off_lock_needed)
}

@Composable
private fun ShortcutRow(icon: @Composable () -> Unit, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}

@Composable
private fun describe(entry: RuleEntry): String {
    val rule = entry.rule ?: return ""
    val spec = ForwardingSpec.fromRule(rule)
    val context = LocalContext.current
    val ended = stringResource(R.string.fw_status_ended)
    if (spec != null) {
        val now = System.currentTimeMillis()
        val line = ForwardingStatusNotifier.summaryLine(context, spec, now)
        return if (spec.status(now) == ForwardingStatus.ENDED) "$ended · $line" else line
    }
    val actions = rule.actions.joinToString { it::class.simpleName.orEmpty() }
    return stringResource(R.string.scr_auto_rule_summary, actions)
}

@Composable
private fun ScheduledRow(
    send: ScheduledSend,
    sims: List<SimInfo>,
    onChangeTime: () -> Unit,
    onCancel: () -> Unit,
    highlighted: Boolean = false,
) {
    val formatter = rememberRelativeTimeFormatter()
    val sim = sims.firstOrNull { it.subId == send.subId }
    ListItem(
        colors = if (highlighted) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
        headlineContent = { Text(send.addresses.joinToString(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(send.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    formatter.formatAbsolute(send.sendAtMillis) + (sim?.let { " · " + it.displayName }.orEmpty()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onChangeTime) { Text(stringResource(R.string.sched_action_change_time)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Platform date then time pickers, starting at [initial]; only today and later can be picked by date. */
private fun pickDateTime(context: Context, initial: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = maxOf(initial, System.currentTimeMillis()) }
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
    dateDialog.show()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(
    draft: RuleDraft,
    sims: List<SimInfo>,
    issues: List<ValidationIssue>,
    isLocked: (ActionKind) -> Boolean,
    onChange: (RuleDraft) -> Unit,
    onLocked: (Feature) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(if (draft.id == null) R.string.scr_auto_new_rule else R.string.scr_auto_edit_rule), style = MaterialTheme.typography.titleLarge)
        Field(draft.name, R.string.scr_auto_field_name) { onChange(draft.copy(name = it)) }

        EditorSection(R.string.scr_auto_when)
        Field(draft.keyword, R.string.scr_auto_field_keyword) { onChange(draft.copy(keyword = it)) }
        Field(draft.senderContains, R.string.scr_auto_field_sender) { onChange(draft.copy(senderContains = it)) }
        Field(draft.bodyContains, R.string.scr_auto_field_body) { onChange(draft.copy(bodyContains = it)) }
        Text(stringResource(R.string.scr_filter_category), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = draft.category == null, onClick = { onChange(draft.copy(category = null)) }, label = { Text(stringResource(R.string.scr_auto_any)) })
            Category.entries.filter { it != Category.UNKNOWN }.forEach { c ->
                FilterChip(selected = draft.category == c, onClick = { onChange(draft.copy(category = c)) }, label = { Text(categoryLabel(c)) })
            }
        }
        if (sims.size > 1) {
            Text(stringResource(R.string.scr_filter_sim), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = draft.simSlot == null, onClick = { onChange(draft.copy(simSlot = null)) }, label = { Text(stringResource(R.string.scr_inbox_all_sims)) })
                sims.filter { it.slotIndex >= 0 }.forEach { sim ->
                    FilterChip(
                        selected = draft.simSlot == sim.slotIndex,
                        onClick = { onChange(draft.copy(simSlot = sim.slotIndex)) },
                        label = { Text(stringResource(R.string.sim_n, (sim.slotIndex + 1).toString())) },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(draft.copy(onlyOtp = !draft.onlyOtp)) }) {
            Text(stringResource(R.string.scr_auto_only_otp), modifier = Modifier.weight(1f))
            Switch(checked = draft.onlyOtp, onCheckedChange = { onChange(draft.copy(onlyOtp = it)) })
        }
        Field(draft.amountAtLeast, R.string.scr_auto_field_amount, KeyboardType.Decimal) { onChange(draft.copy(amountAtLeast = it)) }

        EditorSection(R.string.scr_auto_then)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // SMS forwarding lives in Auto-forwarding (contacts only, time-boxed); only an existing forward rule shows it.
            ActionKind.entries.filter { it != ActionKind.FORWARD_SMS || draft.action == ActionKind.FORWARD_SMS }.forEach { kind ->
                val locked = isLocked(kind)
                FilterChip(
                    selected = draft.action == kind,
                    onClick = { if (locked) kind.premium?.let(onLocked) else onChange(draft.copy(action = kind)) },
                    label = { Text(actionLabel(kind), color = if (locked) DakTheme.colors.locked else MaterialTheme.colorScheme.onSurface) },
                    leadingIcon = if (locked) { { Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null,
                )
            }
        }
        if (ActionKind.entries.any { isLocked(it) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LockChip()
                Text(stringResource(R.string.scr_auto_premium_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (draft.action) {
            ActionKind.LABEL -> Field(draft.label, R.string.scr_auto_field_label) { onChange(draft.copy(label = it)) }
            ActionKind.NOTIFY -> Field(draft.notifyText, R.string.scr_auto_field_notify_text) { onChange(draft.copy(notifyText = it)) }
            ActionKind.FORWARD_SMS, ActionKind.RELAY -> {
                Field(draft.forwardTo, R.string.scr_auto_field_forward_to, KeyboardType.Phone) { onChange(draft.copy(forwardTo = it)) }
                Field(draft.template, R.string.scr_auto_field_template) { onChange(draft.copy(template = it)) }
                Text(stringResource(R.string.scr_auto_template_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (draft.action == ActionKind.RELAY) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RelayChannel.entries.forEach { ch ->
                            FilterChip(selected = draft.relayChannel == ch, onClick = { onChange(draft.copy(relayChannel = ch)) }, label = { Text(ch.name) })
                        }
                    }
                } else {
                    SendSimPicker(draft, sims, onChange)
                }
                if (draft.onlyOtp || draft.category == Category.OTP || draft.category == null) {
                    Text(stringResource(R.string.scr_auto_otp_forward_warning), style = MaterialTheme.typography.bodySmall, color = DakTheme.colors.warning.accent)
                }
            }
            ActionKind.SCHEDULE_REPLY -> {
                Field(draft.replyText, R.string.scr_auto_field_reply) { onChange(draft.copy(replyText = it)) }
                Field(draft.replyDelayMinutes, R.string.scr_auto_field_delay, KeyboardType.Number) { onChange(draft.copy(replyDelayMinutes = it.filter(Char::isDigit))) }
                SendSimPicker(draft, sims, onChange)
            }
            ActionKind.OPEN_LINK -> Field(draft.url, R.string.scr_auto_field_url, KeyboardType.Uri) { onChange(draft.copy(url = it)) }
            ActionKind.WEBHOOK -> {
                Field(draft.url, R.string.scr_auto_field_url, KeyboardType.Uri) { onChange(draft.copy(url = it)) }
                Field(draft.webhookSecret, R.string.scr_auto_field_secret) { onChange(draft.copy(webhookSecret = it)) }
                Field(draft.template, R.string.scr_auto_field_template) { onChange(draft.copy(template = it)) }
            }
            ActionKind.ARCHIVE, ActionKind.DELETE -> Unit
        }
        if (issues.isNotEmpty()) {
            issues.forEach { Text(issueText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onSave, enabled = !isLocked(draft.action)) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendSimPicker(draft: RuleDraft, sims: List<SimInfo>, onChange: (RuleDraft) -> Unit) {
    if (sims.size < 2) return
    Text(stringResource(R.string.scr_auto_send_from), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = draft.sendSubId == null, onClick = { onChange(draft.copy(sendSubId = null)) }, label = { Text(stringResource(R.string.scr_auto_default_sim)) })
        sims.forEach { sim ->
            FilterChip(selected = draft.sendSubId == sim.subId, onClick = { onChange(draft.copy(sendSubId = sim.subId)) }, label = { Text(sim.displayName) })
        }
    }
}

@Composable
private fun EditorSection(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Field(value: String, label: Int, keyboard: KeyboardType = KeyboardType.Text, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}

@Composable
private fun actionLabel(kind: ActionKind): String = stringResource(
    when (kind) {
        ActionKind.LABEL -> R.string.scr_auto_action_label
        ActionKind.ARCHIVE -> R.string.scr_auto_action_archive
        ActionKind.NOTIFY -> R.string.scr_auto_action_notify
        ActionKind.FORWARD_SMS -> R.string.scr_auto_action_forward
        ActionKind.SCHEDULE_REPLY -> R.string.scr_auto_action_reply
        ActionKind.DELETE -> R.string.scr_auto_action_delete
        ActionKind.OPEN_LINK -> R.string.scr_auto_action_open
        ActionKind.WEBHOOK -> R.string.scr_auto_action_webhook
        ActionKind.RELAY -> R.string.scr_auto_action_relay
    },
)

@Composable
private fun issueText(issue: ValidationIssue): String = when (issue) {
    is ValidationIssue.InvalidRegex -> stringResource(R.string.scr_auto_issue_regex, issue.pattern)
    is ValidationIssue.MissingRecipient -> stringResource(R.string.scr_auto_issue_recipient)
    is ValidationIssue.PremiumActionInFreeTier -> stringResource(R.string.scr_auto_issue_premium)
    is ValidationIssue.ForwardingLoop -> stringResource(R.string.scr_auto_issue_loop, issue.address)
    ValidationIssue.NoActions -> stringResource(R.string.scr_auto_issue_incomplete)
}
