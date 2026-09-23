package app.dak.ui.fraud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.safety.FraudReport
import app.dak.safety.helplines.Helpline
import app.dak.safety.helplines.HelplineAction
import app.dak.safety.helplines.HelplineCategory
import app.dak.safety.helplines.UserHelpline
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.theme.DakTheme

/**
 * Report fraud: "lost money? call 1930" first, then — when opened for a message — the report flows for it (TRAI
 * 1909 complaint via the composer, Chakshu / cybercrime.gov.in with the details copied, block sender, copy
 * details), then quick-dial tiles for the official helplines and the user's own bank number.
 */
@Composable
fun FraudHelpScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: FraudHelpViewModel = hiltViewModel()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<UserHelpline?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val text = context.getString(
                when (event) {
                    FraudHelpEvent.BLOCKED -> R.string.safe_blocked
                    FraudHelpEvent.BLOCK_FAILED -> R.string.safe_block_failed
                    FraudHelpEvent.HELPLINE_ADDED -> R.string.safe_bank_added
                    FraudHelpEvent.HELPLINE_INVALID -> R.string.safe_bank_invalid
                },
            )
            snackbar.showSnackbar(text)
        }
    }

    val detailLabels = remember(context) {
        FraudReport.DetailLabels(
            sender = context.getString(R.string.safe_detail_sender),
            received = context.getString(R.string.safe_detail_received),
            sim = context.getString(R.string.safe_detail_sim),
            message = context.getString(R.string.safe_detail_message),
        )
    }
    fun copyDetails(): Boolean {
        val details = viewModel.details(detailLabels) { subId ->
            viewModel.slotOf(subId).takeIf { it >= 0 }?.let { context.getString(R.string.sim_n, (it + 1).toString()) }
        } ?: return false
        copyPlain(context, details)
        return true
    }
    fun openHelpline(helpline: Helpline) {
        val ok = when (helpline.action) {
            HelplineAction.CALL -> FraudIntents.dial(context, helpline.target)
            HelplineAction.URL -> FraudIntents.openUrl(context, helpline.target)
            HelplineAction.SMS -> {
                val route = viewModel.traiComplaintRoute().takeIf { helpline == ui.traiSms }
                    ?: Routes.compose(to = helpline.target)
                navigator.navigate(route)
                true
            }
        }
        if (!ok) Toast.makeText(context, R.string.safe_no_app, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { DakTopAppBar(title = stringResource(R.string.safe_title), onBack = { navigator.back() }) },
    ) { padding ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.urgent?.let { urgent ->
                item(key = "urgent") { UrgentCard(urgent, onCall = { openHelpline(urgent) }) }
            }
            val message = ui.message
            if (message != null) {
                item(key = "report") {
                    ReportCard(
                        ui = ui,
                        message = message,
                        onTrai = { viewModel.traiComplaintRoute()?.let(navigator::navigate) },
                        onChakshu = { helpline ->
                            copyDetails()
                            Toast.makeText(context, R.string.safe_details_copied_paste, Toast.LENGTH_LONG).show()
                            openHelpline(helpline)
                        },
                        onCyber = { helpline ->
                            copyDetails()
                            Toast.makeText(context, R.string.safe_details_copied_paste, Toast.LENGTH_LONG).show()
                            openHelpline(helpline)
                        },
                        onBlock = viewModel::blockSender,
                        onCopy = { copyDetails() },
                    )
                }
            } else if (ui.messageMissing) {
                item(key = "missing") {
                    Text(stringResource(R.string.safe_message_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "helplines-title") { SectionTitle(stringResource(R.string.safe_section_helplines)) }
            items(ui.helplines, key = { "h:" + it.id }) { helpline ->
                HelplineTile(helpline, onOpen = { openHelpline(helpline) }, onSource = { FraudIntents.openUrl(context, helpline.sourceUrl) })
            }
            item(key = "bank-title") { SectionTitle(stringResource(R.string.safe_section_bank)) }
            items(ui.userHelplines, key = { "u:" + it.id }) { mine ->
                UserHelplineTile(mine, onCall = { FraudIntents.dial(context, mine.number) }, onRemove = { removing = mine })
            }
            item(key = "bank-add") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (ui.userHelplines.isEmpty()) {
                        Text(stringResource(R.string.safe_bank_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { adding = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.safe_bank_add), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            item(key = "footer") {
                Text(
                    stringResource(R.string.safe_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }

    if (adding) {
        AddBankNumberDialog(
            onAdd = { label, number -> adding = false; viewModel.addUserHelpline(label, number) },
            onDismiss = { adding = false },
        )
    }
    removing?.let { mine ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.safe_bank_remove_title)) },
            text = { Text("${mine.label} · ${mine.number}") },
            confirmButton = { TextButton(onClick = { removing = null; viewModel.removeUserHelpline(mine.id) }) { Text(stringResource(R.string.safe_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun UrgentCard(helpline: Helpline, onCall: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.safe_urgent_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.safe_urgent_body, helpline.target), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onCall, colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)) {
                Icon(Icons.Outlined.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.safe_call_n, helpline.target), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ReportCard(
    ui: FraudHelpUi,
    message: ReportedMessage,
    onTrai: () -> Unit,
    onChakshu: (Helpline) -> Unit,
    onCyber: (Helpline) -> Unit,
    onBlock: () -> Unit,
    onCopy: () -> Unit,
) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(stringResource(R.string.safe_report_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val date = DateUtils.formatDateTime(context, message.dateMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                Text("${message.sender} · $date", style = MaterialTheme.typography.labelLarge)
                Text(message.body, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (message.isIncoming) {
                ActionRow(
                    icon = Icons.Outlined.Sms,
                    title = stringResource(R.string.safe_report_trai),
                    body = stringResource(R.string.safe_report_trai_body),
                    onClick = onTrai,
                )
            }
            ui.chakshu?.let { chakshu ->
                ActionRow(
                    icon = Icons.Outlined.WarningAmber,
                    title = stringResource(R.string.safe_report_chakshu),
                    body = stringResource(R.string.safe_report_chakshu_body),
                    onClick = { onChakshu(chakshu) },
                )
            }
            ui.cyberPortal?.let { portal ->
                ActionRow(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.safe_report_cyber),
                    body = stringResource(R.string.safe_report_cyber_body),
                    onClick = { onCyber(portal) },
                )
            }
            if (message.isIncoming) {
                ActionRow(
                    icon = Icons.Outlined.Block,
                    title = stringResource(if (ui.senderBlocked) R.string.safe_sender_blocked else R.string.safe_block_sender),
                    body = stringResource(R.string.safe_block_sender_body),
                    onClick = onBlock,
                    enabled = !ui.senderBlocked,
                )
            }
            ActionRow(
                icon = Icons.Outlined.ContentCopy,
                title = stringResource(R.string.safe_copy_details),
                body = null,
                onClick = onCopy,
            )
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, body: String?, onClick: () -> Unit, enabled: Boolean = true) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = if (body != null) {
            { Text(body, style = MaterialTheme.typography.bodySmall) }
        } else {
            null
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun HelplineTile(helpline: Helpline, onOpen: () -> Unit, onSource: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(iconFor(helpline), contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(helpline.name, style = MaterialTheme.typography.titleSmall)
                Text(actionLabel(helpline), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(helpline.purpose, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (helpline.needsVerification) {
                        Text(
                            stringResource(R.string.safe_pending_verification),
                            style = MaterialTheme.typography.labelSmall,
                            color = DakTheme.colors.warning.accent,
                        )
                    }
                    TextButton(onClick = onSource) {
                        Text(stringResource(R.string.safe_source, hostOf(helpline.sourceUrl)), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserHelplineTile(helpline: UserHelpline, onCall: () -> Unit, onRemove: () -> Unit) {
    Card(onClick = onCall, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountBalance, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(helpline.label, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.safe_call_n, helpline.number), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.safe_bank_unverified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.safe_remove)) }
        }
    }
}

@Composable
private fun AddBankNumberDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    val defaultLabel = stringResource(R.string.safe_bank_default_label)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.safe_bank_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.safe_bank_add_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.safe_bank_label)) },
                    placeholder = { Text(defaultLabel) },
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.safe_bank_number)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(label.ifBlank { defaultLabel }, number) }, enabled = number.any { it.isDigit() }) {
                Text(stringResource(R.string.safe_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun actionLabel(helpline: Helpline): String = when (helpline.action) {
    HelplineAction.CALL -> stringResource(R.string.safe_call_n, helpline.target)
    HelplineAction.SMS -> stringResource(R.string.safe_sms_n, helpline.target)
    HelplineAction.URL -> hostOf(helpline.target)
}

private fun iconFor(helpline: Helpline): ImageVector = when {
    helpline.category == HelplineCategory.BANKING || helpline.category == HelplineCategory.BANK_CARD_BLOCK -> Icons.Outlined.AccountBalance
    helpline.action == HelplineAction.CALL -> Icons.Outlined.Call
    helpline.action == HelplineAction.SMS -> Icons.Outlined.Sms
    else -> Icons.Outlined.Language
}

private fun hostOf(url: String): String =
    runCatching { android.net.Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url

/** Copies report details (not sensitive: the user is about to paste them into a government form). */
private fun copyPlain(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.safe_clip_label), text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.safe_details_copied, Toast.LENGTH_SHORT).show()
    }
}
