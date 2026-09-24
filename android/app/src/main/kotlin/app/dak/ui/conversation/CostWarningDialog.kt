package app.dak.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
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
import app.dak.telephony.cost.CostKind
import app.dak.telephony.cost.CostSeverity
import app.dak.telephony.cost.CostVerdict
import app.dak.ui.theme.DakTheme
import java.util.Locale

/**
 * A pending send that needs a cost confirmation.
 *
 * @property verdicts destinations to confirm, loudest first (never empty).
 * @property subId the SIM the composer had selected when the prompt was raised.
 * @property scheduleAtMillis null for "send now", else the send-later time.
 */
data class CostPrompt(val verdicts: List<CostVerdict>, val subId: Int, val scheduleAtMillis: Long? = null) {
    /** A premium-rate destination is involved: strong warning. */
    val isStrong: Boolean get() = verdicts.any { it.severity == CostSeverity.STRONG }
}

/**
 * Confirmation before a costly SMS: strong for premium-rate destinations, mild for unknown short codes,
 * international numbers, roaming abroad and alphanumeric ids. "Don't ask again for this number" is remembered
 * per number and SIM (and also lets automations send to a premium-rate number).
 */
@Composable
fun CostWarningDialog(prompt: CostPrompt, onConfirm: (dontAskAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontAskAgain by rememberSaveable(prompt) { mutableStateOf(false) }
    val strong = prompt.isStrong
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (strong) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
                contentDescription = null,
                tint = if (strong) MaterialTheme.colorScheme.error else DakTheme.colors.warning.accent,
            )
        },
        title = { Text(stringResource(if (strong) R.string.safe_cost_title_premium else R.string.safe_cost_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (verdict in prompt.verdicts) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(verdict.destination, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(costReason(verdict), style = MaterialTheme.typography.bodyMedium)
                        if (verdict.roaming && verdict.kind != CostKind.ROAMING) {
                            Text(stringResource(R.string.safe_cost_also_roaming), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (prompt.scheduleAtMillis != null) {
                    Text(stringResource(R.string.safe_cost_scheduled_note), style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { dontAskAgain = !dontAskAgain }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = null)
                    Text(
                        stringResource(if (prompt.verdicts.size > 1) R.string.safe_cost_dont_ask_numbers else R.string.safe_cost_dont_ask_number),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAskAgain) }) {
                Text(
                    stringResource(if (prompt.scheduleAtMillis != null) R.string.safe_cost_schedule_anyway else R.string.safe_cost_send_anyway),
                    color = if (strong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.safe_cost_dont_send)) } },
    )
}

/** Why sending to [verdict]'s destination may cost more. */
@Composable
fun costReason(verdict: CostVerdict): String = when (verdict.kind) {
    CostKind.PREMIUM_RATE -> stringResource(R.string.safe_cost_premium)
    CostKind.UNKNOWN_SHORT_CODE -> stringResource(R.string.safe_cost_unknown_short_code)
    CostKind.INTERNATIONAL -> verdict.destinationRegion
        ?.let { stringResource(R.string.safe_cost_international_to, countryName(it)) }
        ?: stringResource(R.string.safe_cost_international)
    CostKind.ROAMING -> stringResource(R.string.safe_cost_roaming)
    CostKind.ALPHANUMERIC -> stringResource(R.string.safe_cost_alphanumeric)
    CostKind.EMERGENCY -> stringResource(R.string.safe_cost_emergency)
    CostKind.STANDARD_SHORT_CODE -> stringResource(R.string.safe_cost_standard_short_code)
    CostKind.TOLL_FREE -> stringResource(R.string.safe_cost_toll_free)
    CostKind.NORMAL -> ""
}

private fun countryName(region: String): String =
    runCatching { Locale.Builder().setRegion(region).build().displayCountry }.getOrNull()?.ifBlank { null } ?: region
