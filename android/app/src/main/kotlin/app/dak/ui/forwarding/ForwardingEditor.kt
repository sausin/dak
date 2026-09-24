package app.dak.ui.forwarding

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.automations.forwarding.ForwardingPolicy
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.safety.ValidationIssue
import app.dak.core.model.Category
import app.dak.core.model.SimInfo
import app.dak.ui.common.categoryLabel
import app.dak.ui.theme.DakTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/**
 * The forwarding rule editor (bottom sheet body). Recipients come only from the system contact picker, after
 * READ_CONTACTS is granted so every forward can re-check them; there is no way to type a number. The active period
 * is a start and an end date *and* time (the time picker follows the device's 12/24-hour setting), one hour by
 * default; a longer or open-ended period is flagged here and confirmed with a biometric check on save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ForwardingEditor(
    spec: ForwardingSpec,
    sims: List<SimInfo>,
    issues: List<ValidationIssue>,
    incomplete: Boolean,
    viewModel: ForwardingViewModel,
    onChange: (ForwardingSpec) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    var pickingSources by remember { mutableStateOf(false) }
    var contactsDenied by remember { mutableStateOf(false) }
    val risks by viewModel.recipientRisks.collectAsStateWithLifecycle()

    LaunchedEffect(spec.recipients, spec.subId) { viewModel.assessRecipients(spec.recipients, spec.subId) }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            scope.launch {
                val picked = viewModel.recipientFromPicker(uri)
                if (picked != null && spec.recipients.none { it.number == picked.number }) {
                    onChange(spec.copy(recipients = spec.recipients + picked))
                }
            }
        }
    }

    fun launchContactPicker() {
        try {
            contactPicker.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI))
        } catch (e: ActivityNotFoundException) {
            // No contacts app: there is no other way to add a recipient.
        }
    }

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsDenied = !granted
        if (granted) launchContactPicker()
    }

    /** Date picker that keeps the time of day of [initialMillis]. */
    fun pickDate(initialMillis: Long, onPicked: (Long) -> Unit) {
        val initial = Instant.ofEpochMilli(initialMillis).atZone(zone)
        DatePickerDialog(
            context,
            { _, year, month, day -> onPicked(initial.with(LocalDate.of(year, month + 1, day)).toInstant().toEpochMilli()) },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }

    /** Time picker that keeps the date of [initialMillis]; seconds are zeroed. */
    fun pickTime(initialMillis: Long, onPicked: (Long) -> Unit) {
        val initial = Instant.ofEpochMilli(initialMillis).atZone(zone)
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(initial.with(LocalTime.of(hour, minute)).toInstant().toEpochMilli()) },
            initial.hour,
            initial.minute,
            DateFormat.is24HourFormat(context),
        ).show()
    }

    /** Where the end goes when "until I stop it" is turned off: the default hour, from the start or from now. */
    fun defaultEnd(): Long = maxOf(spec.startMillis, System.currentTimeMillis()) + ForwardingPolicy.DEFAULT_DURATION_MILLIS

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(if (spec.id == null) R.string.fw_new_rule else R.string.fw_edit_rule), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = spec.name,
            onValueChange = { onChange(spec.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.fw_field_name)) },
            placeholder = { Text(stringResource(R.string.fw_field_name_hint)) },
        )

        Section(R.string.fw_section_from)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            spec.sources.forEach { source ->
                AssistChip(
                    onClick = { onChange(spec.copy(sources = spec.sources - source)) },
                    label = { Text(source.name) },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.fw_remove), modifier = Modifier.size(16.dp)) },
                )
            }
            AssistChip(
                onClick = { pickingSources = true },
                label = { Text(stringResource(R.string.fw_add_channel)) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
        Text(stringResource(R.string.fw_filter_category), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = spec.categories.isEmpty(), onClick = { onChange(spec.copy(categories = emptySet())) }, label = { Text(stringResource(R.string.scr_auto_any)) })
            Category.entries.filter { it != Category.UNKNOWN && it != Category.OTP && it != Category.SPAM }.forEach { c ->
                val selected = c in spec.categories
                FilterChip(
                    selected = selected,
                    onClick = { onChange(spec.copy(categories = if (selected) spec.categories - c else spec.categories + c)) },
                    label = { Text(categoryLabel(c)) },
                )
            }
        }
        OutlinedTextField(
            value = spec.keyword,
            onValueChange = { onChange(spec.copy(keyword = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.fw_field_keyword)) },
        )

        Section(R.string.fw_section_to)
        spec.recipients.forEach { r ->
            AssistChip(
                onClick = { onChange(spec.copy(recipients = spec.recipients - r)) },
                label = { Text(if (r.name.isNullOrBlank()) r.number else "${r.name} · ${r.number}") },
                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.fw_remove), modifier = Modifier.size(16.dp)) },
            )
            val flags = risks[r.number].orEmpty()
            if (!r.fromContacts) {
                Text(stringResource(R.string.fw_recipient_not_contact), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (flags.isNotEmpty()) {
                Text(
                    stringResource(R.string.fw_recipient_risky, r.label, riskText(flags)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedButton(onClick = {
            if (viewModel.canReadContacts()) launchContactPicker() else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }) {
            Icon(Icons.Outlined.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.fw_pick_contact), modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            stringResource(if (contactsDenied) R.string.fw_contacts_permission_needed else R.string.fw_contacts_only_note),
            style = MaterialTheme.typography.bodySmall,
            color = if (contactsDenied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sims.size > 1) {
            Text(stringResource(R.string.scr_auto_send_from), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = spec.subId == null, onClick = { onChange(spec.copy(subId = null)) }, label = { Text(stringResource(R.string.scr_auto_default_sim)) })
                sims.forEach { sim ->
                    FilterChip(selected = spec.subId == sim.subId, onClick = { onChange(spec.copy(subId = sim.subId)) }, label = { Text(sim.displayName) })
                }
            }
        }

        Section(R.string.fw_section_when)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fw_start), modifier = Modifier.weight(1f))
            TextButton(onClick = { pickDate(spec.startMillis) { onChange(spec.copy(startMillis = it)) } }) { Text(formatDay(context, spec.startMillis)) }
            TextButton(onClick = { pickTime(spec.startMillis) { onChange(spec.copy(startMillis = it)) } }) { Text(formatTime(context, spec.startMillis)) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onChange(spec.copy(endMillis = if (spec.endMillis == null) defaultEnd() else null)) },
        ) {
            Text(stringResource(R.string.fw_until_stopped), modifier = Modifier.weight(1f))
            Switch(
                checked = spec.endMillis == null,
                onCheckedChange = { forever -> onChange(spec.copy(endMillis = if (forever) null else defaultEnd())) },
            )
        }
        val end = spec.endMillis
        if (end != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fw_end), modifier = Modifier.weight(1f))
                TextButton(onClick = { pickDate(end) { onChange(spec.copy(endMillis = it)) } }) { Text(formatDay(context, end)) }
                TextButton(onClick = { pickTime(end) { onChange(spec.copy(endMillis = it)) } }) { Text(formatTime(context, end)) }
            }
            if (end < spec.startMillis) {
                Text(stringResource(R.string.fw_end_before_start), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (spec.isLongPeriod) {
            Text(stringResource(R.string.fw_period_long_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        } else {
            Text(stringResource(R.string.fw_period_default_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section(R.string.fw_section_message)
        OutlinedTextField(
            value = spec.template,
            onValueChange = { onChange(spec.copy(template = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.scr_auto_field_template)) },
        )
        Text(stringResource(R.string.fw_template_help, ForwardingSpec.defaultTemplate(stringResource(R.string.fw_default_template))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(spec.copy(includeOtp = !spec.includeOtp)) }) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fw_include_otp))
                Text(stringResource(R.string.fw_include_otp_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = spec.includeOtp, onCheckedChange = { onChange(spec.copy(includeOtp = it)) })
        }
        if (spec.includeOtp) {
            Text(stringResource(R.string.fw_include_otp_warning), style = MaterialTheme.typography.bodySmall, color = DakTheme.colors.warning.accent)
        }
        Text(stringResource(R.string.fw_free_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (incomplete) {
            Text(stringResource(R.string.fw_incomplete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        issues.forEach { Text(issueText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        }
    }

    if (pickingSources) {
        SourcePickerDialog(
            viewModel = viewModel,
            selectedIds = spec.sources.map { it.conversationId }.toSet(),
            onDone = { checkedIds, loaded ->
                pickingSources = false
                val kept = spec.sources.filter { it.conversationId in checkedIds }
                val keptIds = kept.map { it.conversationId }.toSet()
                scope.launch {
                    val added = loaded.values.filter { it.conversationId !in keptIds }.map { viewModel.sourceOf(it) }
                    onChange(spec.copy(sources = kept + added))
                }
            },
            onDismiss = { pickingSources = false },
        )
    }
}

@Composable
private fun Section(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun issueText(issue: ValidationIssue): String = when (issue) {
    is ValidationIssue.InvalidRegex -> stringResource(R.string.scr_auto_issue_regex, issue.pattern)
    is ValidationIssue.MissingRecipient -> stringResource(R.string.scr_auto_issue_recipient)
    is ValidationIssue.PremiumActionInFreeTier -> stringResource(R.string.scr_auto_issue_premium)
    is ValidationIssue.ForwardingLoop -> stringResource(R.string.fw_issue_loop, issue.address)
    ValidationIssue.NoActions -> stringResource(R.string.fw_incomplete)
}

/** The date part of [millis] in the locale's medium format. */
private fun formatDay(context: Context, millis: Long): String = DateFormat.getMediumDateFormat(context).format(Date(millis))

/** The time of [millis] in the device's 12/24-hour setting. */
private fun formatTime(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))
