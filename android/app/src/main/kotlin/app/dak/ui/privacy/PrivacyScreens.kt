package app.dak.ui.privacy

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.premium.consent.ConsentRecord
import app.dak.premium.consent.DataFlow
import app.dak.premium.consent.Disclosures
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.lock.ConfirmResult
import app.dak.ui.lock.rememberAppAuthGate
import app.dak.ui.onboarding.startSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the policy is published. TODO(launch): replace with the real URL before Play submission (docs/play-submission.md). */
const val HOSTED_PRIVACY_POLICY_URL: String = "https://dak.example/privacy"

/** Bundled copy of `docs/privacy-policy.md`, shown offline. */
private const val POLICY_ASSET = "privacy-policy.md"

private enum class Screen { HUB, POLICY, SHARING, DELETE }

/** The Privacy area: hub, policy, data sharing (consents), delete. Export runs from the hub. */
@Composable
internal fun PrivacyApp(start: PrivacyActivity.Start, onClose: () -> Unit, viewModel: PrivacyViewModel = hiltViewModel()) {
    val entry = when (start) {
        PrivacyActivity.Start.HUB, PrivacyActivity.Start.EXPORT -> Screen.HUB
        PrivacyActivity.Start.POLICY -> Screen.POLICY
        PrivacyActivity.Start.SHARING -> Screen.SHARING
        PrivacyActivity.Start.DELETE -> Screen.DELETE
    }
    var screen by rememberSaveable { mutableStateOf(entry) }
    val back: () -> Unit = { if (screen == entry) onClose() else screen = Screen.HUB }
    BackHandler(enabled = screen != entry) { screen = Screen.HUB }

    when (screen) {
        Screen.HUB -> PrivacyHub(viewModel, autoExport = start == PrivacyActivity.Start.EXPORT, onBack = back, onOpen = { screen = it })
        Screen.POLICY -> PolicyScreen(onBack = back)
        Screen.SHARING -> DataSharingScreen(viewModel, onBack = back)
        Screen.DELETE -> DeleteScreen(viewModel, onBack = back)
    }
}

@Composable
private fun PrivacyHub(viewModel: PrivacyViewModel, autoExport: Boolean, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val flows by viewModel.flows.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    var showExport by rememberSaveable { mutableStateOf(autoExport) }
    val allowed = flows.count { it.granted }
    // Kept here (not in the dialog) so they outlive the dialog while the auth prompt and file picker are open.
    val gate = rememberAppAuthGate()
    val authTitle = stringResource(R.string.privacy_export_auth)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val startExport: () -> Unit = {
        // Same check as other sensitive screens; with no screen lock and no app PIN there is nothing to ask.
        gate.authenticate(authTitle) { result ->
            if (result != ConfirmResult.DENIED) {
                val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
                runCatching { launcher.launch("dak-my-data-$date.zip") }
            }
        }
    }

    Scaffold(topBar = { DakTopAppBar(title = stringResource(R.string.privacy_title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.privacy_summary_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.privacy_summary_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            HubRow(Icons.Outlined.Description, stringResource(R.string.privacy_policy_title), stringResource(R.string.privacy_policy_summary)) {
                onOpen(Screen.POLICY)
            }
            HubRow(
                Icons.Outlined.CloudUpload,
                stringResource(R.string.privacy_sharing_title),
                if (allowed == 0) stringResource(R.string.privacy_sharing_summary) else "${stringResource(R.string.privacy_flow_on)}: $allowed",
            ) { onOpen(Screen.SHARING) }
            HubRow(Icons.Outlined.FileDownload, stringResource(R.string.privacy_export_title), stringResource(R.string.privacy_export_summary)) {
                viewModel.clearExportResult()
                showExport = true
            }
            ExportStatus(export)
            HubRow(Icons.Outlined.DeleteForever, stringResource(R.string.privacy_delete_title), stringResource(R.string.privacy_delete_summary)) {
                onOpen(Screen.DELETE)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OpenOnlineRow()
        }
    }
    if (showExport) {
        ExportDialog(
            onConfirm = {
                showExport = false
                startExport()
            },
            onDismiss = { showExport = false },
        )
    }
}

@Composable
private fun HubRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
    )
}

@Composable
private fun OpenOnlineRow() {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.clickable { openUrl(context, HOSTED_PRIVACY_POLICY_URL) },
        leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.privacy_policy_online)) },
        supportingContent = { Text(HOSTED_PRIVACY_POLICY_URL) },
    )
}

@Composable
private fun ExportStatus(state: ExportState) {
    when (state) {
        ExportState.Idle -> Unit
        ExportState.Running -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.privacy_export_running), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        is ExportState.Done -> Text(
            stringResource(R.string.privacy_export_done, state.sections, state.rows),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        is ExportState.Failed -> Text(
            stringResource(R.string.privacy_export_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** Explains what the export holds (unencrypted, no SMS) before the auth check and the file picker. */
@Composable
private fun ExportDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacy_export_title)) },
        text = { Text(stringResource(R.string.privacy_export_warning)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.privacy_export_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun PolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val blocks by produceState<List<PolicyMarkdown.Block>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(POLICY_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() } }
                .map(PolicyMarkdown::parse)
                .getOrDefault(emptyList())
        }
    }
    Scaffold(topBar = { DakTopAppBar(title = stringResource(R.string.privacy_policy_title), onBack = onBack) }) { padding ->
        val loaded = blocks
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            if (loaded != null && loaded.isEmpty()) {
                item { Text(stringResource(R.string.privacy_policy_unavailable), style = MaterialTheme.typography.bodyLarge) }
            }
            items(loaded.orEmpty()) { block -> PolicyBlock(block) }
            item { OpenOnlineRow() }
        }
    }
}

@Composable
private fun PolicyBlock(block: PolicyMarkdown.Block) {
    val linkColor = MaterialTheme.colorScheme.primary
    when (block) {
        is PolicyMarkdown.Block.Heading -> Text(
            policyInline(block.text, linkColor),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            modifier = Modifier.padding(top = if (block.level == 1) 0.dp else 16.dp, bottom = 8.dp).semantics { heading() },
        )
        is PolicyMarkdown.Block.Paragraph -> Text(
            policyInline(block.text, linkColor),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        is PolicyMarkdown.Block.Bullet -> Row(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
            Text("•  ", style = MaterialTheme.typography.bodyMedium)
            Text(policyInline(block.text, linkColor), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun policyInline(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (span in PolicyMarkdown.spans(text)) {
        when {
            span.url != null -> withLink(
                LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
            ) { append(span.text) }
            span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
            else -> append(span.text)
        }
    }
}

@Composable
private fun DataSharingScreen(viewModel: PrivacyViewModel, onBack: () -> Unit) {
    val flows by viewModel.flows.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    var disclosureFor by rememberSaveable { mutableStateOf<DataFlow?>(null) }
    var withdrawFor by rememberSaveable { mutableStateOf<DataFlow?>(null) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Scaffold(topBar = { DakTopAppBar(title = stringResource(R.string.privacy_sharing_title), onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.privacy_sharing_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(flows, key = { it.flow.id }) { state ->
                val enabled = state.granted || state.available
                val status = when {
                    state.granted -> stringResource(R.string.privacy_flow_on)
                    !state.available -> stringResource(R.string.privacy_flow_unavailable)
                    else -> stringResource(R.string.privacy_flow_off)
                }
                val changed = state.lastChangedAt?.let { stringResource(R.string.privacy_flow_changed, dateFormat.format(Date(it))) }
                ListItem(
                    modifier = Modifier.toggleable(
                        value = state.granted,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { on -> if (on) disclosureFor = state.flow else withdrawFor = state.flow },
                    ),
                    headlineContent = { Text(state.title) },
                    supportingContent = { Text(listOfNotNull(status, changed).joinToString(" · ")) },
                    trailingContent = { Switch(checked = state.granted, onCheckedChange = null, enabled = enabled) },
                )
                TextButton(onClick = { disclosureFor = state.flow }, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.privacy_flow_details))
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.privacy_records_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                )
                if (records.isEmpty()) {
                    Text(
                        stringResource(R.string.privacy_records_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(records) { record -> ConsentRecordRow(record, dateFormat) }
        }
    }

    disclosureFor?.let { flow ->
        val current = flows.firstOrNull { it.flow == flow }
        val canAllow = current != null && !current.granted && current.available
        if (canAllow) {
            DisclosureDialog(
                flow = flow,
                onAllow = {
                    disclosureFor = null
                    viewModel.grant(flow)
                },
                onDecline = {
                    disclosureFor = null
                    viewModel.decline(flow)
                },
            )
        } else {
            // Read-only view of the explanation (already allowed, or not available in this build).
            DisclosureReadOnly(flow, onClose = { disclosureFor = null })
        }
    }
    withdrawFor?.let { flow ->
        AlertDialog(
            onDismissRequest = { withdrawFor = null },
            title = { Text(stringResource(R.string.privacy_withdraw_title)) },
            text = { Text(stringResource(R.string.privacy_withdraw_body, Disclosures.forFlow(flow).title)) },
            confirmButton = {
                TextButton(onClick = {
                    withdrawFor = null
                    viewModel.withdraw(flow)
                }) { Text(stringResource(R.string.privacy_withdraw_confirm)) }
            },
            dismissButton = { TextButton(onClick = { withdrawFor = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun DisclosureReadOnly(flow: DataFlow, onClose: () -> Unit) {
    val d = Disclosures.forFlow(flow)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(d.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.privacy_disclosure_what), style = MaterialTheme.typography.titleSmall)
                d.whatIsSent.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.privacy_disclosure_to), style = MaterialTheme.typography.titleSmall)
                Text(d.sentTo, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.privacy_disclosure_retention), style = MaterialTheme.typography.titleSmall)
                Text(d.retention, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.privacy_disclosure_off), style = MaterialTheme.typography.titleSmall)
                Text(d.howToTurnOff, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
private fun ConsentRecordRow(record: ConsentRecord, dateFormat: DateFormat) {
    val flow = DataFlow.byId(record.flow)
    val title = flow?.let { Disclosures.forFlow(it).title } ?: record.flow
    ListItem(
        headlineContent = { Text(title) },
        overlineContent = {
            Text(stringResource(if (record.granted) R.string.privacy_record_granted else R.string.privacy_record_declined))
        },
        supportingContent = {
            Text(stringResource(R.string.privacy_record_meta, dateFormat.format(Date(record.atMillis)), record.disclosureVersion, record.source))
        },
    )
}

@Composable
private fun DeleteScreen(viewModel: PrivacyViewModel, onBack: () -> Unit) {
    val gate = rememberAppAuthGate()
    val authTitle = stringResource(R.string.privacy_delete_auth)
    var understood by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var working by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { DakTopAppBar(title = stringResource(R.string.privacy_delete_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.privacy_delete_erased_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.privacy_delete_erased_body), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.privacy_delete_kept_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.privacy_delete_kept_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                stringResource(R.string.privacy_delete_backup_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = understood, onCheckedChange = null)
                Text(stringResource(R.string.privacy_delete_confirm_check), modifier = Modifier.padding(start = 12.dp))
            }
            if (failed) {
                Text(stringResource(R.string.privacy_delete_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    gate.authenticate(authTitle) { result ->
                        // DENIED stops here. UNAVAILABLE (no screen lock, no app PIN) has nothing to check against;
                        // the explicit checkbox above is the confirmation then.
                        if (result != ConfirmResult.DENIED) {
                            working = true
                            viewModel.eraseEverything(onFailed = {
                                working = false
                                failed = true
                            })
                        }
                    }
                },
                enabled = understood && !working,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.privacy_delete_button)) }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(android.R.string.cancel)) }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
