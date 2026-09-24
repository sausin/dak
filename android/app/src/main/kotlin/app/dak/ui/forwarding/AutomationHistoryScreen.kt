package app.dak.ui.forwarding

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.automations.history.RunHistory
import app.dak.automations.history.RunOutcome
import app.dak.automations.history.RunRecord
import app.dak.automations.history.RunSummary
import app.dak.navigation.DakNavigator
import app.dak.ui.common.DakTopAppBar
import app.dak.ui.common.EmptyState
import app.dak.ui.common.TokenChip
import app.dak.ui.theme.DakTheme
import app.dak.ui.theme.TonalColors
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/**
 * What an automation sent, and when: the run log of one rule (from its row's History button) or of every rule
 * (Forwarding → ⋮ → Forwarding history, including deleted rules). A header totals it ("43 messages sent to Sharma CA
 * between 1 Jul and 31 Jul · 2 failed · 5 skipped"); runs are grouped by day, newest first, each with the time, the
 * source sender and a one-line preview (tap to open the original message in its conversation), the destination and
 * the outcome.
 */
@Composable
fun AutomationHistoryScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: AutomationHistoryViewModel = hiltViewModel()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val ruleName by viewModel.ruleName.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val goneText = stringResource(R.string.fw_history_message_gone)
    val list = records.orEmpty()
    val title = when {
        viewModel.ruleId == null -> stringResource(R.string.fw_history_menu)
        else -> stringResource(R.string.fw_history_rule_title, ruleName ?: list.firstOrNull()?.ruleName.orEmpty())
    }
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(list) { RunHistory.groupByDay(list, zone) }
    val summary = remember(list) { RunHistory.summarize(list) }
    val dayFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { DakTopAppBar(title = title, onBack = { navigator.back() }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (records != null && list.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.fw_history_empty_title),
                        body = stringResource(R.string.fw_history_empty_body),
                    )
                }
            }
            if (list.isNotEmpty()) {
                item(key = "summary") { SummaryCard(summary) }
            }
            for (day in days) {
                item(key = "day-${day.date}") {
                    Text(
                        day.date.format(dayFormat),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(day.runs, key = { "run-${it.id}" }) { record ->
                    RunRow(
                        record = record,
                        showRule = viewModel.ruleId == null,
                        onOpen = {
                            scope.launch {
                                val route = viewModel.routeTo(record)
                                if (route != null) navigator.navigate(route) else snackbar.showSnackbar(goneText)
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** "43 messages sent to Sharma CA between 1 Jul and 31 Jul" plus failed / skipped counts. */
@Composable
private fun SummaryCard(summary: RunSummary) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getMediumDateFormat(context) }
    val first = summary.firstAtMillis
    val last = summary.lastAtMillis
    val to = summary.destinations.take(MAX_DESTINATIONS).joinToString(", ") +
        if (summary.destinations.size > MAX_DESTINATIONS) " +${summary.destinations.size - MAX_DESTINATIONS}" else ""
    val sent = if (summary.sent == 0) {
        stringResource(R.string.fw_history_sent_none)
    } else {
        pluralStringResource(R.plurals.fw_history_sent, summary.sent, summary.sent, to)
    }
    val range = if (first != null && last != null) {
        stringResource(R.string.fw_history_between, dateFormat.format(Date(first)), dateFormat.format(Date(last)))
    } else {
        ""
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(listOf(sent, range).filter { it.isNotEmpty() }.joinToString(" "), style = MaterialTheme.typography.bodyLarge)
            if (summary.failed > 0 || summary.skipped > 0) {
                Text(
                    listOfNotNull(
                        summary.failed.takeIf { it > 0 }?.let { stringResource(R.string.fw_history_failed, it) },
                        summary.skipped.takeIf { it > 0 }?.let { stringResource(R.string.fw_history_skipped, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunRow(record: RunRecord, showRule: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    val time = remember(record.atMillis) { DateFormat.getTimeFormat(context).format(Date(record.atMillis)) }
    val outcome = outcomeLabel(record.outcome)
    val openLabel = stringResource(R.string.fw_history_open_message_cd)
    ListItem(
        modifier = Modifier
            .clickable(enabled = record.messageKey != null, role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
            .semantics { stateDescription = outcome },
        overlineContent = {
            Text(
                listOfNotNull(time, record.ruleName.takeIf { showRule }, record.sourceLabel?.let { stringResource(R.string.fw_history_from, it) })
                    .joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        headlineContent = {
            Text(record.textPreview.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                if (record.destinationText.isNotBlank()) {
                    Text(stringResource(R.string.fw_history_to, record.destinationText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AutomationHistoryViewModel.reasonText(record.reason)?.let { res ->
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            Row { TokenChip(label = outcome, colors = outcomeColors(record.outcome)) }
        },
    )
}

@Composable
private fun outcomeLabel(outcome: RunOutcome): String = stringResource(
    when (outcome) {
        RunOutcome.SENT -> R.string.fw_outcome_sent
        RunOutcome.FAILED -> R.string.fw_outcome_failed
        RunOutcome.SKIPPED -> R.string.fw_outcome_skipped
    },
)

@Composable
private fun outcomeColors(outcome: RunOutcome): TonalColors = when (outcome) {
    RunOutcome.SENT -> DakTheme.colors.success
    RunOutcome.SKIPPED -> DakTheme.colors.warning
    RunOutcome.FAILED -> TonalColors(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        accent = MaterialTheme.colorScheme.error,
    )
}

private const val MAX_DESTINATIONS = 3
