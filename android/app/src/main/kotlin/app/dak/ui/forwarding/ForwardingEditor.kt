package app.dak.ui.forwarding

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.automations.forwarding.ForwardingRecipient
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.safety.ValidationIssue
import app.dak.core.model.Category
import app.dak.core.model.SimInfo
import app.dak.ui.common.categoryLabel
import app.dak.ui.theme.DakTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The forwarding rule editor (bottom sheet body). */
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
    var typedNumber by remember { mutableStateOf("") }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) { readPickedPhone(context, uri) }
                if (picked != null && spec.recipients.none { it.number == picked.number }) {
                    onChange(spec.copy(recipients = spec.recipients + picked))
                }
            }
        }
    }

    fun pickDate(initialMillis: Long, onPicked: (LocalDate) -> Unit) {
        val initial = Instant.ofEpochMilli(initialMillis).atZone(zone).toLocalDate()
        DatePickerDialog(
            context,
            { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }

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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            spec.recipients.forEach { r ->
                AssistChip(
                    onClick = { onChange(spec.copy(recipients = spec.recipients - r)) },
                    label = { Text(if (r.name.isNullOrBlank()) r.number else "${r.name} · ${r.number}") },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.fw_remove), modifier = Modifier.size(16.dp)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = typedNumber,
                onValueChange = { typedNumber = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.fw_field_number)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            IconButton(
                onClick = {
                    val n = typedNumber.trim()
                    if (n.isNotEmpty() && spec.recipients.none { it.number == n }) onChange(spec.copy(recipients = spec.recipients + ForwardingRecipient(n)))
                    typedNumber = ""
                },
                enabled = typedNumber.isNotBlank(),
            ) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.fw_add_number)) }
        }
        OutlinedButton(onClick = {
            try {
                contactPicker.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI))
            } catch (e: ActivityNotFoundException) {
                // No contacts app: typing the number still works.
            }
        }) {
            Icon(Icons.Outlined.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.fw_pick_contact), modifier = Modifier.padding(start = 8.dp))
        }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fw_start), modifier = Modifier.weight(1f))
            TextButton(onClick = { pickDate(spec.startMillis) { onChange(spec.copy(startMillis = startOfDay(it, zone))) } }) {
                Text(formatDate(spec.startMillis))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                onChange(spec.copy(endMillis = if (spec.endMillis == null) endOfDay(LocalDate.now(zone).plusDays(30), zone) else null))
            },
        ) {
            Text(stringResource(R.string.fw_until_stopped), modifier = Modifier.weight(1f))
            Switch(
                checked = spec.endMillis == null,
                onCheckedChange = { forever -> onChange(spec.copy(endMillis = if (forever) null else endOfDay(LocalDate.now(zone).plusDays(30), zone))) },
            )
        }
        val end = spec.endMillis
        if (end != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fw_end), modifier = Modifier.weight(1f))
                TextButton(onClick = { pickDate(end) { onChange(spec.copy(endMillis = endOfDay(it, zone))) } }) { Text(formatDate(end)) }
            }
            if (end < spec.startMillis) {
                Text(stringResource(R.string.fw_end_before_start), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Section(R.string.fw_section_message)
        OutlinedTextField(
            value = spec.template,
            onValueChange = { onChange(spec.copy(template = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.scr_auto_field_template)) },
        )
        Text(stringResource(R.string.fw_template_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Number and name of the phone row the system contact picker returned (readable without READ_CONTACTS). */
private fun readPickedPhone(context: android.content.Context, uri: Uri): ForwardingRecipient? = try {
    context.contentResolver.query(uri, arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val number = c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@use null
        ForwardingRecipient(number, c.getString(1))
    }
} catch (e: SecurityException) {
    null
} catch (e: IllegalArgumentException) {
    null
}
