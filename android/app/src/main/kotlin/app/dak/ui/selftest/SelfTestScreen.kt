package app.dak.ui.selftest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.index.sync.BackgroundActivityLog
import app.dak.navigation.DakNavigator
import app.dak.notifications.ReliabilityCheck
import app.dak.notifications.ReliabilityCheckId
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.WarningBanner
import app.dak.ui.theme.DakTheme

/**
 * Notification self-test (Settings → Notifications → Notification self-test): every check that can silently break
 * notifications with a one-tap fix, a local test notification, and "send yourself a test SMS" on a chosen SIM that
 * measures the full receive → notify round trip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelfTestScreen(navigator: DakNavigator, modifier: Modifier = Modifier, viewModel: SelfTestViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val fix = rememberReliabilityFixer(onReturn = viewModel::refresh)

    Scaffold(
        modifier = modifier,
        topBar = { DakTopAppBar(title = stringResource(R.string.selftest_title), onBack = { navigator.back() }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.report.restricted) {
                WarningBanner(
                    title = stringResource(R.string.reliability_banner_title),
                    body = stringResource(R.string.selftest_restricted_body),
                )
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.selftest_checks), style = MaterialTheme.typography.titleMedium)
                state.report.checks.forEach { check ->
                    CheckRow(check, onFix = { check.fix?.let(fix) })
                }
            }

            Card(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.selftest_local_section), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.selftest_local_explain), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = viewModel::postLocalTest) { Text(stringResource(R.string.selftest_local_button)) }
                    when (state.localNotificationPosted) {
                        true -> Text(stringResource(R.string.selftest_local_posted), color = DakTheme.colors.success.accent)
                        false -> Text(stringResource(R.string.selftest_local_blocked), color = MaterialTheme.colorScheme.error)
                        null -> Unit
                    }
                }
            }

            Card(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.selftest_sms_section), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.selftest_sms_explain), style = MaterialTheme.typography.bodyMedium)
                    if (state.sims.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.sims.forEach { sim ->
                                FilterChip(
                                    selected = sim.subId == state.selectedSubId,
                                    onClick = { viewModel.selectSim(sim.subId) },
                                    label = {
                                        Text(sim.displayName.ifBlank { stringResource(R.string.sim_n, (sim.slotIndex + 1).toString()) })
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.number,
                        onValueChange = viewModel::onNumberChange,
                        label = { Text(stringResource(R.string.selftest_own_number)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = viewModel::sendTestSms,
                        enabled = state.number.isNotBlank() && state.sms !is SmsTestState.Sending && state.sms !is SmsTestState.Waiting,
                    ) { Text(stringResource(R.string.selftest_sms_button)) }
                    SmsStatus(state.sms)
                    Text(
                        stringResource(R.string.selftest_sms_carrier_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.activity.isNotEmpty()) BackgroundActivityCard(state.activity)
        }
    }
}

/** Debug builds only: how often Dak ran in the background per day (see docs/battery.md for expected numbers). */
@Composable
private fun BackgroundActivityCard(days: List<BackgroundActivityLog.Day>) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.battery_activity_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.battery_activity_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            days.forEach { day ->
                Text(day.date, style = MaterialTheme.typography.labelLarge)
                Text(
                    day.counts.entries.joinToString("\n") { (source, count) -> "$source: $count" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CheckRow(check: ReliabilityCheck, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (check.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(if (check.ok) R.string.state_ok else R.string.state_problem),
            tint = if (check.ok) DakTheme.colors.success.accent else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(checkTitle(check.id)), style = MaterialTheme.typography.bodyLarge)
            if (!check.ok) {
                Text(
                    stringResource(checkProblem(check.id)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!check.ok && check.fix != null) TextButton(onClick = onFix) { Text(stringResource(R.string.action_fix)) }
    }
}

@Composable
private fun SmsStatus(sms: SmsTestState) {
    when (sms) {
        SmsTestState.Idle -> Unit
        SmsTestState.Sending, SmsTestState.Waiting -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(if (sms is SmsTestState.Sending) R.string.selftest_sms_sending else R.string.selftest_sms_waiting))
        }
        is SmsTestState.Received -> Text(
            stringResource(
                if (sms.notificationPosted) R.string.selftest_sms_received else R.string.selftest_sms_received_no_notification,
                sms.seconds,
            ),
            color = if (sms.notificationPosted) DakTheme.colors.success.accent else MaterialTheme.colorScheme.error,
        )
        is SmsTestState.Failed -> Text(stringResource(R.string.selftest_sms_failed, sms.reason), color = MaterialTheme.colorScheme.error)
        SmsTestState.TimedOut -> Text(stringResource(R.string.selftest_sms_timeout), color = MaterialTheme.colorScheme.error)
    }
}

private fun checkTitle(id: ReliabilityCheckId): Int = when (id) {
    ReliabilityCheckId.DEFAULT_SMS_APP -> R.string.check_default_sms
    ReliabilityCheckId.NOTIFICATION_PERMISSION -> R.string.check_notification_permission
    ReliabilityCheckId.APP_NOTIFICATIONS -> R.string.check_app_notifications
    ReliabilityCheckId.CHANNELS -> R.string.check_channels
    ReliabilityCheckId.BATTERY_OPTIMIZATION -> R.string.check_battery
    ReliabilityCheckId.BACKGROUND_RESTRICTED -> R.string.check_background
}

private fun checkProblem(id: ReliabilityCheckId): Int = when (id) {
    ReliabilityCheckId.DEFAULT_SMS_APP -> R.string.check_default_sms_problem
    ReliabilityCheckId.NOTIFICATION_PERMISSION -> R.string.check_notification_permission_problem
    ReliabilityCheckId.APP_NOTIFICATIONS -> R.string.check_app_notifications_problem
    ReliabilityCheckId.CHANNELS -> R.string.check_channels_problem
    ReliabilityCheckId.BATTERY_OPTIMIZATION -> R.string.check_battery_problem
    ReliabilityCheckId.BACKGROUND_RESTRICTED -> R.string.check_background_problem
}
