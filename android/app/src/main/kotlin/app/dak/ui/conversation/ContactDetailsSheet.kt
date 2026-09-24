package app.dak.ui.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.di.ContactMatch
import app.dak.ui.common.Avatar
import app.dak.ui.common.text.BidiText

/**
 * Who this thread is with, opened by tapping the header: each participant's name and number with Call, Copy, and
 * either "View contact" (saved) or "Save as new contact" / "Add to existing contact" (unsaved numbers). Business
 * senders (alphanumeric ids) cannot be called or saved, so they only get Copy and Block. [onContactsChanged] runs when
 * the user comes back from the Contacts app, so the header picks up a newly saved name.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactDetailsSheet(
    header: ConversationHeader,
    conversationKey: String,
    onBlock: () -> Unit,
    onContactsChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val contactsApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onContactsChanged() }
    fun launch(intent: Intent) {
        try {
            contactsApp.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast(context, R.string.ent_no_app)
        } catch (e: SecurityException) {
            toast(context, R.string.ent_no_app)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(
                name = header.title,
                key = header.addresses.firstOrNull() ?: conversationKey,
                size = 72.dp,
                photoUri = header.photoUri,
                isBusiness = header.isBusiness,
            )
            Text(
                BidiText.displaySafe(header.title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (header.isGroup) {
                Text(
                    stringResource(R.string.scr_conv_group_members, header.addresses.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!header.canReadContacts) {
                Text(
                    stringResource(R.string.cd_no_contacts_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            for (address in header.addresses) {
                Participant(
                    address = address,
                    contact = header.contacts[address],
                    showName = header.isGroup,
                    onCall = { start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", address, null))) },
                    onCopy = {
                        copyToClipboard(context, address, sensitive = false)
                        toast(context, R.string.cd_number_copied)
                    },
                    onView = { lookupKey ->
                        launch(Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)))
                    },
                    onSaveNew = { launch(newContactIntent(address)) },
                    onAddToExisting = { launch(addToExistingIntent(address)) },
                )
                HorizontalDivider()
            }
            TextButton(onClick = { onDismiss(); onBlock() }, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Outlined.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.scr_action_block),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Participant(
    address: String,
    contact: ContactMatch?,
    showName: Boolean,
    onCall: () -> Unit,
    onCopy: () -> Unit,
    onView: (String) -> Unit,
    onSaveNew: () -> Unit,
    onAddToExisting: () -> Unit,
) {
    val business = address.none { it.isDigit() }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(if (showName && contact != null) BidiText.displaySafe(contact.displayName) else BidiText.isolate(address))
            },
            supportingContent = {
                val detail = when {
                    business -> stringResource(R.string.cd_business_sender)
                    contact == null -> stringResource(R.string.cd_not_saved)
                    showName -> BidiText.isolate(address)
                    else -> stringResource(R.string.cd_saved_as, contact.displayName)
                }
                Text(detail)
            },
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!business) Chip(Icons.Outlined.Call, R.string.cd_call, onCall)
            Chip(Icons.Outlined.ContentCopy, R.string.cd_copy_number, onCopy)
            contact?.lookupKey?.let { key -> Chip(Icons.Outlined.AccountCircle, R.string.cd_view_contact) { onView(key) } }
            if (contact == null && !business) Chip(Icons.Outlined.PersonAddAlt, R.string.cd_add_to_existing, onAddToExisting)
        }
        // The one thing an unsaved number most often needs: a clear, primary "Save".
        if (contact == null && !business) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilledTonalButton(onClick = onSaveNew, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                    Text(stringResource(R.string.cd_save_contact), modifier = Modifier.padding(start = 8.dp))
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

/** The Contacts app's "new contact" editor with the number filled in. */
private fun newContactIntent(number: String): Intent =
    Intent(ContactsContract.Intents.Insert.ACTION)
        .setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.PHONE, number)
        .putExtra(EXTRA_FINISH_ON_SAVE, true)

/** The Contacts app's contact picker, then that contact's editor with the number added. */
private fun addToExistingIntent(number: String): Intent =
    Intent(Intent.ACTION_INSERT_OR_EDIT)
        .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
        .putExtra(ContactsContract.Intents.Insert.PHONE, number)
        .putExtra(EXTRA_FINISH_ON_SAVE, true)

private fun start(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(context, R.string.ent_no_app)
    } catch (e: SecurityException) {
        toast(context, R.string.ent_no_app)
    }
}

private fun toast(context: Context, message: Int) = Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()

/** Asks the Contacts editor to return here after Save instead of showing the contact (honoured by most apps). */
private const val EXTRA_FINISH_ON_SAVE = "finishActivityOnSaveCompleted"
