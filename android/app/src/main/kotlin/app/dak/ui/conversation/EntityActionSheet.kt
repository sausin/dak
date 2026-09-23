package app.dak.ui.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.classify.entities.EntitySpan
import app.dak.classify.entities.EntityType
import app.dak.classify.scam.ScamLabels
import app.dak.index.MessageItem
import app.dak.navigation.Routes
import app.dak.ui.common.text.BidiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Courier websites for the "Track" action, opened only when the user taps it (the tracking id is copied first so it
 * can be pasted on the courier's page). Keys are `app.dak.classify.entities.Couriers` keys; https only.
 */
internal object CourierSites {
    private val SITES: Map<String, String> = mapOf(
        "bluedart" to "https://www.bluedart.com/",
        "delhivery" to "https://www.delhivery.com/",
        "dtdc" to "https://www.dtdc.in/",
        "ecomexpress" to "https://www.ecomexpress.in/",
        "xpressbees" to "https://www.xpressbees.com/",
        "ekart" to "https://ekartlogistics.com/",
        "shadowfax" to "https://www.shadowfax.in/",
        "indiapost" to "https://www.indiapost.gov.in/",
        "fedex" to "https://www.fedex.com/",
        "dhl" to "https://www.dhl.com/",
        "ups" to "https://www.ups.com/",
        "aramex" to "https://www.aramex.com/",
    )

    fun siteFor(courier: String?): String? = courier?.let { SITES[it] }
}

/**
 * Bottom sheet of actions for one tapped entity in a bubble. Every action is a user tap: calls open the dialer
 * (ACTION_DIAL, never a direct call), WhatsApp and courier sites open only on tap, nothing is fetched.
 *
 * For messages the fake-credit detector flagged (`scam:*` labels), phone and UPI sheets lead with a red warning and
 * do not put a one-tap call at the top.
 */
@Composable
fun EntityActionSheet(
    item: MessageItem,
    span: EntitySpan,
    onNavigate: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val flagged = ScamLabels.fromLabels(item.labels) != null
    val passbookAccount by produceState<String?>(initialValue = null, span, item.key) {
        value = if (span.type == EntityType.MASKED_ACCOUNT) matchingAccountId(context, item, span.value) else null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(typeLabel(span.type)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    BidiText.isolate(span.text),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            val warn = flagged && (span.type == EntityType.PHONE || span.type == EntityType.UPI_ID)
            if (warn) ScamEntityWarning()
            HorizontalDivider()

            @Composable
            fun row(icon: ImageVector, label: Int, action: () -> Unit) =
                EntityRow(icon, stringResource(label)) { onDismiss(); action() }

            val copy: () -> Unit = { copyToClipboard(context, copyText(span), sensitive = span.type == EntityType.OTP) }
            when (span.type) {
                EntityType.PHONE -> {
                    val call: () -> Unit = { start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", span.value, null))) }
                    // Flagged message: no one-tap call at the top; copying (to verify) comes first, calling last.
                    if (!warn) row(Icons.Outlined.Call, R.string.ent_action_call, call)
                    else row(Icons.Outlined.ContentCopy, R.string.ent_action_copy, copy)
                    row(Icons.AutoMirrored.Outlined.Message, R.string.ent_action_sms) { onNavigate(Routes.compose(to = span.value)) }
                    row(Icons.Outlined.PersonAdd, R.string.ent_action_save_contact) { start(context, saveContactIntent(span.value)) }
                    row(Icons.Outlined.Chat, R.string.ent_action_whatsapp) { openWhatsApp(context, span.value) }
                    if (!warn) row(Icons.Outlined.ContentCopy, R.string.ent_action_copy, copy)
                    else row(Icons.Outlined.Call, R.string.ent_action_call, call)
                }
                EntityType.AMOUNT -> {
                    row(Icons.Outlined.ContentCopy, R.string.ent_action_copy_amount, copy)
                    row(Icons.Outlined.Search, R.string.ent_action_search_amount) { onNavigate(Routes.search(span.value)) }
                }
                EntityType.MASKED_ACCOUNT -> {
                    passbookAccount?.let { id ->
                        row(Icons.Outlined.AccountBalance, R.string.ent_action_open_passbook) { onNavigate(Routes.passbookAccount(id)) }
                    }
                    row(Icons.Outlined.ContentCopy, R.string.ent_action_copy, copy)
                }
                EntityType.TRACKING -> {
                    row(Icons.Outlined.ContentCopy, R.string.ent_action_copy, copy)
                    CourierSites.siteFor(span.courier)?.let { site ->
                        row(Icons.Outlined.LocalShipping, R.string.ent_action_track) {
                            copyToClipboard(context, span.value, sensitive = false)
                            if (!LinkSafety.open(context, site)) toast(context, R.string.ent_no_app)
                        }
                    }
                }
                EntityType.EMAIL -> {
                    row(Icons.Outlined.Email, R.string.ent_action_email) {
                        start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", span.value, null)))
                    }
                    row(Icons.Outlined.ContentCopy, R.string.ent_action_copy, copy)
                }
                EntityType.URL -> {
                    row(Icons.AutoMirrored.Outlined.OpenInNew, R.string.ent_action_open_link) { onOpenLink(span.value) }
                    row(Icons.Outlined.ContentCopy, R.string.ent_action_copy, copy)
                }
                EntityType.OTP, EntityType.UPI_ID, EntityType.REFERENCE, EntityType.PNR ->
                    row(Icons.Outlined.ContentCopy, R.string.ent_action_copy, copy)
            }
        }
    }
}

@Composable
private fun ScamEntityWarning() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.ent_scam_warning), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EntityRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(label) },
    )
}

private fun typeLabel(type: EntityType): Int = when (type) {
    EntityType.OTP -> R.string.ent_type_otp
    EntityType.URL -> R.string.ent_type_url
    EntityType.EMAIL -> R.string.ent_type_email
    EntityType.MASKED_ACCOUNT -> R.string.ent_type_account
    EntityType.AMOUNT -> R.string.ent_type_amount
    EntityType.UPI_ID -> R.string.ent_type_upi
    EntityType.PNR -> R.string.ent_type_pnr
    EntityType.TRACKING -> R.string.ent_type_tracking
    EntityType.REFERENCE -> R.string.ent_type_reference
    EntityType.PHONE -> R.string.ent_type_phone
}

/** What "Copy" puts on the clipboard: the canonical value (E.164 number, plain amount, id), or the text as written. */
private fun copyText(span: EntitySpan): String = when (span.type) {
    EntityType.MASKED_ACCOUNT, EntityType.URL -> span.text
    else -> span.value
}

private fun saveContactIntent(number: String): Intent =
    Intent(ContactsContract.Intents.Insert.ACTION)
        .setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.PHONE, number)

/** `https://wa.me/<digits>`: only ever opened from the user's tap, through the http(s)-only opener. */
private fun openWhatsApp(context: Context, e164: String) {
    val digits = e164.filter { it.isDigit() }
    if (digits.isEmpty() || !LinkSafety.open(context, "https://wa.me/$digits")) toast(context, R.string.ent_no_app)
}

private fun start(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        toast(context, R.string.ent_no_app)
    } catch (e: SecurityException) {
        toast(context, R.string.ent_no_app)
    }
}

private fun toast(context: Context, message: Int) = Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()

/**
 * The ledger account a mask ("XX1234") refers to: the message's own parsed account when its digits match, else the
 * single ledger account with those last digits. Null when none or ambiguous, and for flagged fake credits (which
 * never enter the ledger).
 */
private suspend fun matchingAccountId(context: Context, item: MessageItem, digits: String): String? {
    if (digits.length < 3 || ScamLabels.excludedFromLedger(item.labels)) return null
    fun matches(last4: String?): Boolean = last4 != null && (last4.endsWith(digits) || digits.endsWith(last4))
    item.transaction?.let { txn -> if (txn.accountId != null && matches(txn.instrumentLast4)) return txn.accountId }
    return try {
        withContext(Dispatchers.IO) {
            val accounts = entityDeps(context).ledgerRepository().accounts().first()
            accounts.filter { matches(it.account.last4) }.singleOrNull()?.account?.id
        }
    } catch (e: RuntimeException) {
        null
    }
}
