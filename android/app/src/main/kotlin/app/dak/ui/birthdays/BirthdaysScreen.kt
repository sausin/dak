package app.dak.ui.birthdays

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.dak.R
import app.dak.automations.birthdays.OccasionKind
import app.dak.automations.birthdays.WishTemplates
import app.dak.birthdays.BirthdaySettings
import app.dak.birthdays.WishMode
import app.dak.core.model.SimInfo
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.forwarding.AppLockNeededDialog
import app.dak.ui.common.Avatar
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the template sheet edits: a global default, or one contact's own wish. */
private sealed interface TemplateTarget {
    data class Default(val kind: OccasionKind) : TemplateTarget
    data class Contact(val item: UpcomingOccasion) : TemplateTarget
}

/**
 * Birthday wishes picked up from contacts. Global opt-in (off by default); even when on, nothing is sent for a
 * contact until the user turns on "auto-send wish" for them. "Ask me first" (default) posts a Send / Edit / Skip
 * notification on the day; "Send automatically" sends at the chosen time from the chosen SIM.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BirthdaysScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: BirthdaysViewModel = hiltViewModel()
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val sims by viewModel.simList.collectAsStateWithLifecycle()
    var templateTarget by remember { mutableStateOf<TemplateTarget?>(null) }
    var askedOnce by remember { mutableStateOf(false) }
    var lockNeeded by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askedOnce = true
        viewModel.refresh()
    }
    // Coming back from system settings (permission granted there) re-checks.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        var first = true
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!first) viewModel.refresh()
            first = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { DakTopAppBar(title = stringResource(R.string.fw_bd_title), onBack = { navigator.back() }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                GlobalSettings(
                    settings = state.settings,
                    sims = sims.filter { it.isActive },
                    onEnabled = viewModel::setEnabled,
                    onMode = { mode -> if (!viewModel.setMode(mode)) lockNeeded = true },
                    onTime = viewModel::setTime,
                    onSim = viewModel::setSim,
                    onAnniversaries = viewModel::setIncludeAnniversaries,
                    onEditTemplate = { templateTarget = TemplateTarget.Default(it) },
                )
            }
            item { HorizontalDivider() }
            if (!state.hasPermission) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Contacts,
                        title = stringResource(R.string.fw_bd_permission_title),
                        body = stringResource(R.string.fw_bd_permission_body),
                        actionLabel = stringResource(if (askedOnce) R.string.fw_bd_open_settings else R.string.fw_bd_allow),
                        onAction = {
                            if (!askedOnce) {
                                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            } else {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                try { context.startActivity(intent) } catch (e: ActivityNotFoundException) { }
                            }
                        },
                    )
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.fw_bd_upcoming),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                val upcoming = state.upcoming
                if (upcoming != null && upcoming.isEmpty()) {
                    item {
                        EmptyState(icon = Icons.Outlined.Cake, title = stringResource(R.string.fw_bd_none_title), body = stringResource(R.string.fw_bd_none_body))
                    }
                }
                items(upcoming.orEmpty(), key = { "${it.occasion.contactId}:${it.occasion.kind}" }) { item ->
                    OccasionRow(
                        item = item,
                        featureOn = state.settings.enabled,
                        onToggle = { viewModel.setContactEnabled(item, it) },
                        onNumber = { viewModel.setContactNumber(item, it) },
                        onEditTemplate = { templateTarget = TemplateTarget.Contact(item) },
                    )
                    HorizontalDivider()
                }
            }
            item { Box(Modifier.padding(32.dp)) }
        }
    }

    templateTarget?.let { target ->
        val initial = when (target) {
            is TemplateTarget.Default -> state.settings.templateFor(target.kind)
            is TemplateTarget.Contact -> target.item.config?.template ?: state.settings.templateFor(target.item.occasion.kind)
        }
        val kind = when (target) {
            is TemplateTarget.Default -> target.kind
            is TemplateTarget.Contact -> target.item.occasion.kind
        }
        ModalBottomSheet(onDismissRequest = { templateTarget = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            TemplateEditor(
                initial = initial,
                kind = kind,
                canReset = target is TemplateTarget.Contact,
                onSave = { text ->
                    when (target) {
                        is TemplateTarget.Default -> viewModel.setDefaultTemplate(target.kind, text.ifBlank { defaultFor(target.kind) })
                        is TemplateTarget.Contact -> viewModel.setContactTemplate(target.item, text)
                    }
                    templateTarget = null
                },
                onReset = {
                    if (target is TemplateTarget.Contact) viewModel.setContactTemplate(target.item, null)
                    templateTarget = null
                },
                onCancel = { templateTarget = null },
            )
        }
    }

    // "Send automatically" is an unattended send: it needs app lock, like auto-forwarding.
    if (lockNeeded) {
        AppLockNeededDialog(
            onSetUp = {
                lockNeeded = false
                navigator.navigate(Routes.APP_LOCK)
            },
            onDismiss = { lockNeeded = false },
        )
    }
}

private fun defaultFor(kind: OccasionKind): String =
    if (kind == OccasionKind.ANNIVERSARY) WishTemplates.DEFAULT_ANNIVERSARY else WishTemplates.DEFAULT_BIRTHDAY

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalSettings(
    settings: BirthdaySettings,
    sims: List<SimInfo>,
    onEnabled: (Boolean) -> Unit,
    onMode: (WishMode) -> Unit,
    onTime: (Int, Int) -> Unit,
    onSim: (Int?) -> Unit,
    onAnniversaries: (Boolean) -> Unit,
    onEditTemplate: (OccasionKind) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onEnabled(!settings.enabled) }) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fw_bd_enable), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.fw_bd_enable_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.enabled, onCheckedChange = onEnabled)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = settings.wishMode == WishMode.ASK, onClick = { onMode(WishMode.ASK) }, label = { Text(stringResource(R.string.fw_bd_mode_ask)) })
            FilterChip(selected = settings.wishMode == WishMode.AUTO, onClick = { onMode(WishMode.AUTO) }, label = { Text(stringResource(R.string.fw_bd_mode_auto)) })
        }
        Text(
            stringResource(if (settings.wishMode == WishMode.ASK) R.string.fw_bd_mode_ask_summary else R.string.fw_bd_mode_auto_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fw_bd_send_time), modifier = Modifier.weight(1f))
            TextButton(onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> onTime(hour, minute) },
                    settings.hour,
                    settings.minute,
                    DateFormat.is24HourFormat(context),
                ).show()
            }) {
                Text(LocalTime.of(settings.hour.coerceIn(0, 23), settings.minute.coerceIn(0, 59)).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
            }
        }
        if (sims.size > 1) {
            Text(stringResource(R.string.scr_auto_send_from), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = settings.subId == null, onClick = { onSim(null) }, label = { Text(stringResource(R.string.scr_auto_default_sim)) })
                sims.forEach { sim ->
                    FilterChip(selected = settings.subId == sim.subId, onClick = { onSim(sim.subId) }, label = { Text(sim.displayName) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onAnniversaries(!settings.includeAnniversaries) }) {
            Text(stringResource(R.string.fw_bd_anniversaries), modifier = Modifier.weight(1f))
            Switch(checked = settings.includeAnniversaries, onCheckedChange = onAnniversaries)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { onEditTemplate(OccasionKind.BIRTHDAY) }, label = { Text(stringResource(R.string.fw_bd_edit_birthday_template)) })
            if (settings.includeAnniversaries) {
                AssistChip(onClick = { onEditTemplate(OccasionKind.ANNIVERSARY) }, label = { Text(stringResource(R.string.fw_bd_edit_anniversary_template)) })
            }
        }
    }
}

@Composable
private fun OccasionRow(
    item: UpcomingOccasion,
    featureOn: Boolean,
    onToggle: (Boolean) -> Unit,
    onNumber: (String) -> Unit,
    onEditTemplate: () -> Unit,
) {
    val o = item.occasion
    var numberMenu by remember { mutableStateOf(false) }
    val whenText = when (item.daysUntil) {
        0 -> stringResource(R.string.fw_bd_today)
        1 -> stringResource(R.string.fw_bd_tomorrow)
        else -> stringResource(R.string.fw_bd_in_days, item.daysUntil)
    }
    // Day and month in the user's own order ("5 Mar" in India / UK, "Mar 5" in the US).
    val dateText = item.date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMM")))
    val anniversaryText = stringResource(R.string.fw_bd_anniversary)
    val detail = if (o.kind == OccasionKind.ANNIVERSARY) "$whenText · $dateText · $anniversaryText" else "$whenText · $dateText"
    val ageText = item.age?.let { stringResource(R.string.fw_bd_turns, it) }
    ListItem(
        modifier = Modifier.clickable(onClick = onEditTemplate),
        leadingContent = { Avatar(name = o.name, key = "contact:${o.contactId}", photoUri = o.photoUri) },
        headlineContent = { Text(o.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(if (ageText != null) "$detail · $ageText" else detail, style = MaterialTheme.typography.bodySmall)
                if (o.numbers.size > 1) {
                    Box {
                        TextButton(onClick = { numberMenu = true }) { Text(item.number ?: stringResource(R.string.fw_bd_choose_number)) }
                        DropdownMenu(expanded = numberMenu, onDismissRequest = { numberMenu = false }) {
                            o.numbers.forEach { n ->
                                DropdownMenuItem(
                                    text = { Text(if (n.label.isNullOrBlank()) n.number else "${n.number} · ${n.label}") },
                                    onClick = { numberMenu = false; onNumber(n.number) },
                                )
                            }
                        }
                    }
                } else if (o.numbers.isEmpty()) {
                    Text(stringResource(R.string.fw_bd_no_number), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "“" + item.preview + "”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.enabled && !featureOn) {
                    Text(stringResource(R.string.fw_bd_feature_off_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = item.enabled, onCheckedChange = onToggle, enabled = o.numbers.isNotEmpty())
                Text(stringResource(R.string.fw_bd_auto_send), style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateEditor(
    initial: String,
    kind: OccasionKind,
    canReset: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val presets = if (kind == OccasionKind.ANNIVERSARY) WishTemplates.anniversaryDefaults else WishTemplates.birthdayDefaults
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.fw_bd_template_title), style = MaterialTheme.typography.titleLarge)
        presets.forEach { preset ->
            ListItem(
                modifier = Modifier.clickable { text = preset.text },
                headlineContent = { Text(preset.text, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = { Text(preset.language, style = MaterialTheme.typography.labelSmall) },
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.fw_bd_template_field)) },
            minLines = 2,
        )
        Text(stringResource(R.string.fw_bd_template_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            if (canReset) TextButton(onClick = onReset) { Text(stringResource(R.string.fw_bd_template_reset)) }
            Button(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) }
        }
    }
}
