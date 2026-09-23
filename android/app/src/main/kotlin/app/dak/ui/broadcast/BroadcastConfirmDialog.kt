package app.dak.ui.broadcast

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.automations.broadcast.BroadcastLimits
import app.dak.automations.broadcast.ExclusionReason
import app.dak.automations.broadcast.PlanProblem
import app.dak.automations.broadcast.SpamRiskLevel
import app.dak.automations.broadcast.SpamSignal
import app.dak.broadcast.BroadcastPreview
import app.dak.telephony.cost.CostKind
import java.util.Locale

/**
 * The confirmation before every broadcast: recipients, SIM, SMS count and charges note, international / roaming
 * warnings from the cost guard, the spread-out ETA, who was left out and why, and the spam-risk warning. Flagged or
 * large sends need the "These people know me and expect this message" box ticked. A plan that cannot be sent shows
 * its problems and only a Cancel button.
 */
@Composable
fun BroadcastConfirmDialog(preview: BroadcastPreview, simName: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val plan = preview.plan
    val n = plan.copies.size
    val scheduled = preview.scheduledAtMillis != null
    val risk = plan.risk
    var expected by rememberSaveable(preview) { mutableStateOf(false) }
    val strong = risk.level == SpamRiskLevel.HIGH
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (strong || !plan.canSend) Icons.Outlined.WarningAmber else Icons.Outlined.Campaign,
                contentDescription = null,
                tint = if (strong || !plan.canSend) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(stringResource(if (scheduled) R.string.bc_confirm_title_scheduled else R.string.bc_confirm_title, n))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!plan.canSend) {
                    for (problem in plan.problems) {
                        Text(problemText(problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (risk.looksPromotional) {
                    RiskCard(stringResource(R.string.bc_risk_promo_title), stringResource(R.string.bc_risk_promo_body))
                } else if (SpamSignal.LARGE_LIST in risk.signals) {
                    RiskCard(stringResource(R.string.bc_risk_large_title), stringResource(R.string.bc_risk_large_body))
                }
                if (simName != null) Text(stringResource(R.string.bc_sim_label, simName), style = MaterialTheme.typography.bodyMedium)
                if (n > 0) {
                    Text(
                        stringResource(R.string.bc_confirm_cost, preview.totalSegments, n, preview.maxSegmentsPerCopy),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(etaText(context, preview), style = MaterialTheme.typography.bodyMedium)
                }
                val international = preview.costVerdicts.count { it.kind == CostKind.INTERNATIONAL }
                if (international > 0) {
                    Text(stringResource(R.string.bc_confirm_international, international), style = MaterialTheme.typography.bodyMedium)
                }
                if (preview.roaming || preview.costVerdicts.any { it.kind == CostKind.ROAMING || it.roaming }) {
                    Text(stringResource(R.string.bc_confirm_roaming), style = MaterialTheme.typography.bodyMedium)
                }
                if (plan.excluded.isNotEmpty()) {
                    Text(
                        stringResource(R.string.bc_confirm_excluded, plan.excluded.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    for (e in plan.excluded.take(MAX_EXCLUDED_SHOWN)) {
                        val who = e.member.displayName.ifBlank { e.member.address }
                        Text("$who: ${exclusionText(e.reason)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (plan.excluded.size > MAX_EXCLUDED_SHOWN) {
                        Text("…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (plan.canSend) {
                    Text(
                        stringResource(
                            R.string.bc_confirm_quota,
                            (preview.remainingToday - n).coerceAtLeast(0),
                            BroadcastLimits.HARD_MAX_MESSAGES_PER_DAY,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.bc_confirm_reminder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (plan.canSend && plan.requiresExtraConfirmation) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { expected = !expected }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = expected, onCheckedChange = null)
                        Text(
                            stringResource(R.string.bc_confirm_expect),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (plan.canSend) {
                TextButton(onClick = onConfirm, enabled = !plan.requiresExtraConfirmation || expected) {
                    Text(
                        stringResource(if (scheduled) R.string.bc_confirm_schedule else R.string.bc_confirm_send),
                        color = if (strong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bc_cancel)) } },
    )
}

@Composable
private fun RiskCard(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun problemText(problem: PlanProblem): String = when (problem) {
    PlanProblem.EmptyMessage -> stringResource(R.string.bc_problem_empty)
    PlanProblem.NoRecipients -> stringResource(R.string.bc_problem_no_recipients)
    is PlanProblem.TooManyRecipients -> stringResource(R.string.bc_problem_too_many, problem.count, problem.max)
    is PlanProblem.DailyLimit -> stringResource(R.string.bc_problem_daily, problem.remaining, problem.max)
    PlanProblem.ScheduledInPast -> stringResource(R.string.bc_problem_past)
    is PlanProblem.ScheduledTooFar -> stringResource(R.string.bc_problem_far)
}

@Composable
internal fun exclusionText(reason: ExclusionReason): String = stringResource(
    when (reason) {
        ExclusionReason.DUPLICATE -> R.string.bc_excl_duplicate
        ExclusionReason.BLOCKED -> R.string.bc_excl_blocked
        ExclusionReason.OWN_NUMBER -> R.string.bc_excl_own
        ExclusionReason.SHORT_CODE -> R.string.bc_excl_short_code
        ExclusionReason.ALPHANUMERIC -> R.string.bc_excl_alphanumeric
        ExclusionReason.SPECIAL_TARIFF -> R.string.bc_excl_special
        ExclusionReason.INVALID -> R.string.bc_excl_invalid
    },
)

private fun etaText(context: Context, preview: BroadcastPreview): String {
    val plan = preview.plan
    val spread = formatDuration(plan.spreadMillis)
    return when {
        preview.scheduledAtMillis != null ->
            context.getString(R.string.bc_confirm_eta_scheduled, formatWhen(context, plan.startAtMillis), spread)
        plan.spreadMillis <= 0L -> context.getString(R.string.bc_confirm_eta_single_batch)
        else -> context.getString(R.string.bc_confirm_eta, spread)
    }
}

/** A date and time in the user's locale, e.g. "24 Sept, 9:00 am". */
internal fun formatWhen(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
)

/** "40 minutes", "1 hour, 10 minutes" in the user's locale (rounded up to the minute). */
internal fun formatDuration(millis: Long): String {
    val totalMinutes = ((millis + 59_999L) / 60_000L).coerceAtLeast(0L)
    val format = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.WIDE)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> format.formatMeasures(Measure(minutes, MeasureUnit.MINUTE))
        minutes == 0L -> format.formatMeasures(Measure(hours, MeasureUnit.HOUR))
        else -> format.formatMeasures(Measure(hours, MeasureUnit.HOUR), Measure(minutes, MeasureUnit.MINUTE))
    }
}

private const val MAX_EXCLUDED_SHOWN = 8

/** A plural string with [count] as its only argument. */
@Composable
internal fun quantityString(id: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(id, count, count)
