package app.dak.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dak.R
import app.dak.premium.consent.DataFlow
import app.dak.premium.consent.Disclosure
import app.dak.premium.consent.Disclosures

/**
 * The prominent disclosure shown before an off-device data flow is turned on (Google Play User Data policy: in-app,
 * separate from the privacy policy, says what data, to whom and why, and ends in an affirmative choice). Full screen,
 * so it cannot be mistaken for a routine dialog. "Allow" stays disabled until the user confirms they are 18 or older
 * (DPDP Act 2023 s.9: no consent-based processing of a child's data without verifiable parental consent).
 *
 * Nothing is recorded here: the caller records the grant or decline in the consent ledger.
 */
@Composable
fun DisclosureDialog(flow: DataFlow, onAllow: () -> Unit, onDecline: () -> Unit) {
    val disclosure = Disclosures.forFlow(flow)
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DisclosureContent(disclosure, onAllow = onAllow, onDecline = onDecline, modifier = Modifier.safeDrawingPadding())
        }
    }
}

@Composable
internal fun DisclosureContent(disclosure: Disclosure, onAllow: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var adult by rememberSaveable(disclosure.flow) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.privacy_disclosure_kicker),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(disclosure.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            DisclosureSection(stringResource(R.string.privacy_disclosure_what), disclosure.whatIsSent)
            DisclosureSection(stringResource(R.string.privacy_disclosure_not), disclosure.whatIsNotSent)
            DisclosureSection(stringResource(R.string.privacy_disclosure_to), listOf(disclosure.sentTo))
            DisclosureSection(stringResource(R.string.privacy_disclosure_why), listOf(disclosure.why))
            DisclosureSection(stringResource(R.string.privacy_disclosure_when), listOf(disclosure.whenSent))
            DisclosureSection(stringResource(R.string.privacy_disclosure_retention), listOf(disclosure.retention))
            DisclosureSection(stringResource(R.string.privacy_disclosure_off), listOf(disclosure.howToTurnOff))
            TextButton(onClick = { context.startActivity(PrivacyActivity.intent(context, PrivacyActivity.Start.POLICY)) }) {
                Text(stringResource(R.string.privacy_disclosure_read_policy))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = adult, role = Role.Checkbox, onValueChange = { adult = it })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = adult, onCheckedChange = null)
                Text(stringResource(R.string.privacy_disclosure_adult), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
            }
            Text(
                stringResource(R.string.privacy_disclosure_version, disclosure.version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.privacy_disclosure_decline)) }
            Button(onClick = onAllow, enabled = adult) { Text(stringResource(R.string.privacy_disclosure_allow)) }
        }
    }
}

@Composable
private fun DisclosureSection(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        lines.forEach { line ->
            Text(
                if (lines.size > 1) "• $line" else line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
