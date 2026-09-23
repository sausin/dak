package app.dak.ui.conversation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.ux.UxPrefsViewModel
import app.dak.ui.common.Avatar
import app.dak.ui.common.DakTopAppBar

/** New message: pick recipients (contacts or raw numbers), then compose. Opens the thread after the first send. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: NewConversationViewModel = hiltViewModel()
    val context = LocalContext.current
    val recipients by viewModel.recipients.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val composerUi by viewModel.composer.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val strings = remember(context) { ComposerStrings.load(context) }

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.resolveNames()
    }
    LaunchedEffect(Unit) {
        if (viewModel.canReadContacts) viewModel.resolveNames() else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }
    LaunchedEffect(Unit) {
        viewModel.openRoute.collect { route ->
            navigator.back()
            navigator.navigate(route)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.composerEvents.collect { snackbar.showSnackbar(strings.message(it)) }
    }

    Scaffold(
        modifier = modifier,
        topBar = { DakTopAppBar(title = stringResource(R.string.scr_new_title), onBack = { navigator.back() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recipients.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (r in recipients) {
                            InputChip(
                                selected = false,
                                onClick = { viewModel.remove(r) },
                                label = { Text(r.name ?: r.address) },
                                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.scr_new_remove_recipient), modifier = Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = viewModel.queryText,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.scr_new_to)) },
                    placeholder = { Text(stringResource(R.string.scr_new_to_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.addTyped() }),
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (query.isBlank() && recipients.isEmpty()) {
                    item(key = "broadcast") {
                        ListItem(
                            modifier = Modifier.clickable { navigator.navigate(Routes.BROADCASTS) },
                            leadingContent = { Icon(Icons.Outlined.Campaign, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.bc_new_message_entry)) },
                        )
                    }
                }
                if (viewModel.looksLikeAddress(query)) {
                    item {
                        ListItem(
                            modifier = Modifier.clickable { viewModel.addTyped() },
                            leadingContent = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.scr_new_send_to_number, query.trim())) },
                        )
                    }
                }
                items(suggestions, key = { it.name + "|" + it.number }) { s ->
                    ListItem(
                        modifier = Modifier.clickable { viewModel.add(s.number, s.name) },
                        leadingContent = { Avatar(name = s.name, key = s.number, photoUri = s.photoUri) },
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(listOfNotNull(s.number, s.type).joinToString(" · ")) },
                    )
                }
                if (query.isNotBlank() && suggestions.isEmpty() && !viewModel.canReadContacts) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.scr_new_contacts_off), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) }) {
                                Text(stringResource(R.string.scr_action_allow))
                            }
                        }
                    }
                }
            }
            val enterToSend by hiltViewModel<UxPrefsViewModel>().enterToSend.collectAsStateWithLifecycle()
            Composer(ui = composerUi, text = viewModel.composer.draftText, actions = viewModel.composer, enterToSend = enterToSend)
        }
    }
}

/** Snackbar texts for composer outcomes. */
internal class ComposerStrings(
    private val sendFailed: String,
    private val noSim: String,
    private val tooLarge: String,
    private val scheduled: String,
    private val textOnly: String,
) {
    fun message(event: ComposerEvent): String = when (event) {
        is ComposerEvent.SendFailed -> when (event.problem) {
            SendProblem.NO_SIM -> noSim
            SendProblem.ATTACHMENT_TOO_LARGE, SendProblem.ATTACHMENT_UNREADABLE -> tooLarge
            else -> if (event.detail.isNullOrBlank()) sendFailed else "$sendFailed (${event.detail})"
        }
        is ComposerEvent.Scheduled -> scheduled
        ComposerEvent.ScheduleTextOnly -> textOnly
    }

    companion object {
        fun load(context: android.content.Context) = ComposerStrings(
            sendFailed = context.getString(R.string.scr_snack_send_failed),
            noSim = context.getString(R.string.scr_snack_no_sim),
            tooLarge = context.getString(R.string.scr_snack_attachment_too_large),
            scheduled = context.getString(R.string.scr_snack_scheduled),
            textOnly = context.getString(R.string.scr_snack_schedule_text_only),
        )
    }
}
