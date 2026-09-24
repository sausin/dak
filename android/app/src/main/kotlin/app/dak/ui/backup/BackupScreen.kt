package app.dak.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.backup.BackupFailure
import app.dak.backup.BackupOpKind
import app.dak.backup.BackupOperation
import app.dak.backup.ExportFormat
import app.dak.navigation.DakNavigator
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.WarningBanner
import app.dak.ui.common.rememberRelativeTimeFormatter
import app.dak.ui.conversation.copyToClipboard
import app.dak.ui.theme.DakTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MIN_PASSPHRASE = 8

/**
 * Backup and data: pick a folder (Google Drive or Dropbox through their document providers, or local storage),
 * set the passphrase, see the recovery code once, back up now or on a schedule, export (open Dak format or SMS
 * Backup & Restore XML), import from other apps, and restore. Restores and imports only ever add messages.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: BackupViewModel = hiltViewModel()
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val hasPassphrase by viewModel.hasPassphrase.collectAsStateWithLifecycle()
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    val formatter = rememberRelativeTimeFormatter()
    var passphraseDialog by rememberSaveable { mutableStateOf(false) }
    var restoreDialog by rememberSaveable { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setDestination(uri)
    }
    val exportDak = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri, ExportFormat.DAK)
    }
    val exportXml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        if (uri != null) viewModel.export(uri, ExportFormat.SMS_BACKUP_RESTORE_XML)
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    val stamp = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val running = operation is BackupOperation.Running

    Scaffold(
        modifier = modifier,
        topBar = { DakTopAppBar(title = stringResource(R.string.scr_backup_title), onBack = { navigator.back() }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            OperationPanel(operation, onDismiss = viewModel::dismissResult, onCopy = { copyToClipboard(context, it, sensitive = true) })

            SectionTitle(R.string.scr_backup_section_encrypted)
            Text(
                stringResource(R.string.scr_backup_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.scr_backup_destination)) },
                supportingContent = { Text(status.destinationLabel ?: status.destination?.authority ?: stringResource(R.string.scr_backup_destination_none)) },
                trailingContent = { TextButton(onClick = { pickFolder.launch(null) }, enabled = !running) { Text(stringResource(R.string.scr_backup_choose)) } },
            )
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.scr_backup_passphrase)) },
                supportingContent = { Text(stringResource(if (hasPassphrase) R.string.scr_backup_passphrase_set else R.string.scr_backup_passphrase_none)) },
                trailingContent = {
                    TextButton(onClick = { passphraseDialog = true }, enabled = !running) {
                        Text(stringResource(if (hasPassphrase) R.string.scr_backup_change else R.string.scr_backup_set))
                    }
                },
            )
            ListItem(
                leadingContent = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.scr_backup_last)) },
                supportingContent = {
                    Column {
                        val last = status.lastBackupMillis
                        Text(
                            if (last == null) stringResource(R.string.scr_backup_never)
                            else stringResource(R.string.scr_backup_last_value, formatter.formatAbsolute(last), status.lastMessageCount ?: 0),
                        )
                        status.lastError?.let { Text(stringResource(R.string.scr_backup_last_error, it), color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::backupNow, enabled = !running && status.destination != null && hasPassphrase) {
                    Text(stringResource(R.string.scr_backup_now))
                }
                OutlinedButton(onClick = { restoreDialog = true }, enabled = !running && status.destination != null) {
                    Icon(Icons.Outlined.Restore, contentDescription = null)
                    Text(stringResource(R.string.scr_backup_restore), modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text(stringResource(R.string.scr_backup_schedule), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("daily" to R.string.scr_backup_daily, "weekly" to R.string.scr_backup_weekly, "manual" to R.string.scr_backup_manual).forEach { (value, label) ->
                    FilterChip(selected = schedule == value, onClick = { viewModel.setSchedule(value) }, label = { Text(stringResource(label)) })
                }
            }
            Text(
                stringResource(R.string.scr_backup_bin_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            HorizontalDivider()

            SectionTitle(R.string.scr_backup_section_export)
            ListItem(
                leadingContent = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.scr_backup_export_dak)) },
                supportingContent = { Text(stringResource(R.string.scr_backup_export_dak_body)) },
                trailingContent = { TextButton(onClick = { exportDak.launch("dak-export-$stamp.zip") }, enabled = !running) { Text(stringResource(R.string.scr_backup_export)) } },
            )
            ListItem(
                leadingContent = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.scr_backup_export_xml)) },
                supportingContent = { Text(stringResource(R.string.scr_backup_export_xml_body)) },
                trailingContent = { TextButton(onClick = { exportXml.launch("sms-backup-$stamp.xml") }, enabled = !running) { Text(stringResource(R.string.scr_backup_export)) } },
            )
            ListItem(
                leadingContent = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.scr_backup_import)) },
                supportingContent = { Text(stringResource(R.string.scr_backup_import_body)) },
                trailingContent = { TextButton(onClick = { importFile.launch(arrayOf("*/*")) }, enabled = !running) { Text(stringResource(R.string.scr_backup_choose)) } },
            )
            Text(
                stringResource(R.string.scr_backup_default_app_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (passphraseDialog) {
        PassphraseDialog(
            changing = hasPassphrase,
            onSave = { passphraseDialog = false; viewModel.setPassphrase(it) },
            onDismiss = { passphraseDialog = false },
        )
    }
    if (restoreDialog) {
        RestoreDialog(
            onPassphrase = { restoreDialog = false; viewModel.restoreWithPassphrase(it) },
            onRecoveryCode = { restoreDialog = false; viewModel.restoreWithRecoveryCode(it) },
            onDismiss = { restoreDialog = false },
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
private fun OperationPanel(operation: BackupOperation, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    when (operation) {
        BackupOperation.Idle -> Unit
        is BackupOperation.Running -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(runningLabel(operation.kind)), style = MaterialTheme.typography.bodyMedium)
            if (operation.total > 0) {
                LinearProgressIndicator(progress = { (operation.done.toFloat() / operation.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.scr_backup_progress, operation.done, operation.total), style = MaterialTheme.typography.labelSmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (operation.done > 0) Text(stringResource(R.string.scr_backup_progress_count, operation.done), style = MaterialTheme.typography.labelSmall)
            }
        }
        is BackupOperation.Failed -> WarningBanner(
            title = stringResource(R.string.scr_backup_failed_title),
            body = stringResource(failureLabel(operation.failure)) + (operation.detail?.let { "\n$it" }.orEmpty()),
            actionLabel = stringResource(R.string.action_got_it),
            onAction = onDismiss,
        )
        is BackupOperation.Finished -> {
            val code = operation.recoveryCode
            if (code != null) {
                RecoveryCodeDialog(code = code, onCopy = { onCopy(code) }, onDone = onDismiss)
            }
            Surface(color = DakTheme.colors.success.container, contentColor = DakTheme.colors.success.content, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(finishedText(operation), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (code == null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.state_ok), color = DakTheme.colors.success.content) }
                }
            }
        }
    }
}

@Composable
private fun finishedText(op: BackupOperation.Finished): String {
    val formatter = rememberRelativeTimeFormatter()
    return when (op.kind) {
        BackupOpKind.BACKUP -> stringResource(R.string.scr_backup_done_backup, op.count)
        BackupOpKind.RESTORE -> {
            val base = stringResource(R.string.scr_backup_done_restore, op.count, op.skipped)
            op.restoredUpToMillis?.let { base + " " + stringResource(R.string.scr_backup_done_restore_upto, formatter.formatAbsolute(it)) } ?: base
        }
        BackupOpKind.EXPORT_DAK, BackupOpKind.EXPORT_XML -> stringResource(R.string.scr_backup_done_export, op.count)
        BackupOpKind.IMPORT -> stringResource(R.string.scr_backup_done_import, op.count, op.skipped)
    }
}

private fun runningLabel(kind: BackupOpKind): Int = when (kind) {
    BackupOpKind.BACKUP -> R.string.scr_backup_running_backup
    BackupOpKind.RESTORE -> R.string.scr_backup_running_restore
    BackupOpKind.EXPORT_DAK, BackupOpKind.EXPORT_XML -> R.string.scr_backup_running_export
    BackupOpKind.IMPORT -> R.string.scr_backup_running_import
}

private fun failureLabel(failure: BackupFailure): Int = when (failure) {
    BackupFailure.NO_DESTINATION -> R.string.scr_backup_fail_destination
    BackupFailure.NO_PASSPHRASE -> R.string.scr_backup_fail_passphrase
    BackupFailure.WRONG_KEY -> R.string.scr_backup_fail_wrong_key
    BackupFailure.DAMAGED -> R.string.scr_backup_fail_damaged
    BackupFailure.NO_BACKUP_FOUND -> R.string.scr_backup_fail_none_found
    BackupFailure.UNRECOGNISED_FILE -> R.string.scr_backup_fail_unrecognised
    BackupFailure.IO -> R.string.scr_backup_fail_io
}

@Composable
private fun RecoveryCodeDialog(code: String, onCopy: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.scr_backup_recovery_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.scr_backup_recovery_body))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(code, style = DakTheme.typography.otpCode.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), modifier = Modifier.padding(12.dp))
                }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.scr_backup_recovery_copy)) }
            }
        },
        confirmButton = { Button(onClick = onDone) { Text(stringResource(R.string.scr_backup_recovery_saved)) } },
    )
}

@Composable
private fun PassphraseDialog(changing: Boolean, onSave: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val tooShort = first.isNotEmpty() && first.length < MIN_PASSPHRASE
    val mismatch = second.isNotEmpty() && first != second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (changing) R.string.scr_backup_change_passphrase else R.string.scr_backup_set_passphrase)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scr_backup_passphrase_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.scr_backup_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = tooShort,
                    supportingText = if (tooShort) { { Text(stringResource(R.string.scr_backup_passphrase_short, MIN_PASSPHRASE)) } } else null,
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.scr_backup_passphrase_repeat)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    supportingText = if (mismatch) { { Text(stringResource(R.string.scr_backup_passphrase_mismatch)) } } else null,
                )
                if (changing) Text(stringResource(R.string.scr_backup_passphrase_change_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(first.toCharArray()) }, enabled = first.length >= MIN_PASSPHRASE && first == second) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RestoreDialog(onPassphrase: (CharArray) -> Unit, onRecoveryCode: (String) -> Unit, onDismiss: () -> Unit) {
    var useCode by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scr_backup_restore)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scr_backup_restore_body), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !useCode, onClick = { useCode = false; value = "" }, label = { Text(stringResource(R.string.scr_backup_passphrase)) })
                    FilterChip(selected = useCode, onClick = { useCode = true; value = "" }, label = { Text(stringResource(R.string.scr_backup_recovery_code)) })
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(if (useCode) R.string.scr_backup_recovery_code else R.string.scr_backup_passphrase)) },
                    visualTransformation = if (useCode) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (useCode) onRecoveryCode(value) else onPassphrase(value.toCharArray()) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.scr_backup_restore))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
