package app.dak.ui.forwarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.automations.forwarding.RecipientRisk

/**
 * The scam warning shown before the biometric check for a high-risk forwarding rule: what forwarded messages let
 * someone do, that scammers ask people to set this up, why this rule triggered the warning, and an explicit "nobody
 * asked me to" acknowledgement before Continue is enabled.
 */
@Composable
internal fun ForwardingRiskDialog(risk: ForwardingRisk, onContinue: () -> Unit, onDismiss: () -> Unit) {
    var acknowledged by rememberSaveable(risk) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.fw_risk_title), color = MaterialTheme.colorScheme.error) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.fw_risk_body), style = MaterialTheme.typography.bodyMedium)
                val reasons = reasonLines(risk)
                if (reasons.isNotEmpty()) {
                    Text(stringResource(R.string.fw_risk_reasons), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { acknowledged = !acknowledged }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Text(stringResource(R.string.fw_risk_ack), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue, enabled = acknowledged) {
                Text(stringResource(R.string.fw_risk_continue), color = if (acknowledged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun reasonLines(risk: ForwardingRisk): List<String> = buildList {
    if (risk.openEnded) add(stringResource(R.string.fw_risk_reason_open))
    if (risk.longPeriod) add(stringResource(R.string.fw_risk_reason_long))
    if (risk.extends) add(stringResource(R.string.fw_risk_reason_extend))
    if (risk.includesOtp) add(stringResource(R.string.fw_risk_reason_otp))
    risk.riskyRecipients.forEach { (recipient, flags) ->
        add(stringResource(R.string.fw_risk_reason_recipient, recipient.label, riskText(flags)))
    }
}

/** "saved or edited in the last 7 days, a foreign number" for a recipient's risk flags. */
@Composable
internal fun riskText(flags: Set<RecipientRisk>): String = flags.map { flag ->
    stringResource(
        when (flag) {
            RecipientRisk.RECENTLY_CHANGED_CONTACT -> R.string.fw_risk_recent_contact
            RecipientRisk.NO_MESSAGE_HISTORY -> R.string.fw_risk_no_history
            RecipientRisk.INTERNATIONAL_NUMBER -> R.string.fw_risk_international
            RecipientRisk.UNUSUAL_NUMBER -> R.string.fw_risk_unusual
        },
    )
}.joinToString(", ")
