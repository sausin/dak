package app.dak.ui.broadcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.automations.broadcast.BroadcastLimits
import app.dak.automations.broadcast.BroadcastList
import app.dak.automations.broadcast.BroadcastLists
import app.dak.automations.broadcast.Member
import app.dak.ui.common.Avatar
import app.dak.ui.conversation.ContactSuggestion
import kotlinx.coroutines.delay

/**
 * Create / edit a broadcast list: a name, people from the contacts search and typed numbers. Duplicates are skipped
 * and the list stops at [BroadcastLimits.HARD_MAX_RECIPIENTS] members.
 *
 * @param initial the list being edited, or null for a new one.
 * @param search contacts search (empty without the permission).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastListEditor(
    initial: BroadcastList?,
    canReadContacts: Boolean,
    onRequestContacts: () -> Unit,
    search: suspend (String) -> List<ContactSuggestion>,
    onSave: (name: String, members: List<Member>) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val defaultName = stringResource(R.string.bc_edit_default_name)
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    val members = remember { mutableStateListOf<Member>().apply { addAll(initial?.members.orEmpty()) } }
    var query by rememberSaveable { mutableStateOf("") }
    var numbers by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<ContactSuggestion>>(emptyList()) }
    var overCap by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val max = BroadcastLimits.HARD_MAX_RECIPIENTS

    LaunchedEffect(query, canReadContacts) {
        if (query.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MILLIS)
        suggestions = runCatching { search(query) }.getOrDefault(emptyList()).take(MAX_SUGGESTIONS)
    }

    fun add(additions: List<Member>) {
        val (next, dropped) = BroadcastLists.addMembers(members.toList(), additions, max)
        members.clear()
        members.addAll(next)
        overCap = dropped > 0
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(if (initial == null) R.string.bc_edit_title_new else R.string.bc_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME) },
                label = { Text(stringResource(R.string.bc_edit_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.bc_edit_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!canReadContacts && query.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bc_edit_contacts_off), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRequestContacts) { Text(stringResource(R.string.bc_edit_allow)) }
                }
            }
            for (s in suggestions) {
                ListItem(
                    modifier = Modifier.clickable {
                        add(listOf(Member(contactId = null, displayName = s.name, address = s.number)))
                        query = ""
                    },
                    leadingContent = { Avatar(name = s.name, key = s.number, photoUri = s.photoUri) },
                    headlineContent = { Text(s.name) },
                    supportingContent = { Text(listOfNotNull(s.number, s.type).joinToString(" · ")) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = numbers,
                    onValueChange = { numbers = it },
                    label = { Text(stringResource(R.string.bc_edit_numbers)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        add(BroadcastLists.parseNumbers(numbers))
                        numbers = ""
                    }),
                )
                IconButton(onClick = {
                    add(BroadcastLists.parseNumbers(numbers))
                    numbers = ""
                }) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = stringResource(R.string.bc_edit_add))
                }
            }
            if (overCap) {
                Text(
                    stringResource(R.string.bc_edit_over_cap, max),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(R.string.bc_edit_members, members.size, max), style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            for (member in members.toList()) {
                val label = member.displayName.ifBlank { member.address }
                ListItem(
                    leadingContent = { Avatar(name = member.displayName.ifBlank { null }, key = member.address) },
                    headlineContent = { Text(label) },
                    supportingContent = if (member.displayName.isNotBlank()) { { Text(member.address) } } else null,
                    trailingContent = {
                        IconButton(onClick = {
                            members.remove(member)
                            overCap = false
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.bc_edit_remove, label))
                        }
                    },
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.bc_edit_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.bc_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(name.trim().ifBlank { defaultName }, members.toList()) },
                    enabled = members.isNotEmpty(),
                ) { Text(stringResource(R.string.bc_edit_save)) }
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.bc_edit_delete_confirm, name.ifBlank { defaultName })) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.bc_edit_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.bc_cancel)) } },
        )
    }
}

private const val SEARCH_DEBOUNCE_MILLIS = 250L
private const val MAX_SUGGESTIONS = 8
private const val MAX_NAME = 60
