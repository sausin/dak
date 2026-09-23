package app.dak.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dak.R
import app.dak.classify.scam.ScamLabels
import app.dak.classify.scam.ScamLevel
import app.dak.classify.scam.ScamReason
import app.dak.core.model.MessageKey
import app.dak.di.ApplicationScope
import app.dak.index.MessageItem
import app.dak.index.scam.ScamRepository
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.BlockedNumbers
import app.dak.ui.common.text.BidiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [ScamWarningBanner]: the "Warn about fake credit alerts" switch, "Not a scam" (stored by the index, so the
 * message is never re-flagged and a genuine credit re-enters the ledger) and blocking the sender.
 */
@HiltViewModel
class ScamWarningViewModel @Inject constructor(
    settings: SettingsStore,
    private val scams: ScamRepository,
    private val blocked: BlockedNumbers,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = settings.observe(DakSettings.fakeCreditWarnings)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _dismissed = MutableStateFlow<Set<MessageKey>>(emptySet())

    /** Messages dismissed in this session (hidden at once, before the index catches up). */
    val dismissed: StateFlow<Set<MessageKey>> = _dismissed.asStateFlow()

    private val _blocked = MutableStateFlow<Set<String>>(emptySet())

    /** Addresses blocked from a banner in this session. */
    val blockedAddresses: StateFlow<Set<String>> = _blocked.asStateFlow()

    fun notAScam(key: MessageKey) {
        _dismissed.update { it + key }
        // Application scope: the decision must be saved even if the user leaves the screen right away.
        appScope.launch { runCatching { scams.dismiss(key) } }
    }

    fun block(address: String) {
        viewModelScope.launch {
            val ok = runCatching { blocked.block(address) }.getOrDefault(false)
            if (ok) _blocked.update { it + address }
        }
    }
}

/**
 * Prominent warning above an incoming message flagged as a possible fake credit alert (labels from
 * `app.dak.classify.scam.ScamLabels`). Warns, never hides: the message stays fully readable below it.
 * Renders nothing for unflagged, outgoing or dismissed messages, or when warnings are turned off.
 *
 * @param onReport opens the fraud-help screen for the message key (`Routes.fraudHelp`).
 */
@Composable
fun ScamWarningBanner(item: MessageItem, onReport: (String) -> Unit, modifier: Modifier = Modifier) {
    if (item.isOutgoing) return
    val verdict = ScamLabels.fromLabels(item.labels) ?: return
    val viewModel: ScamWarningViewModel = hiltViewModel()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()
    val blockedAddresses by viewModel.blockedAddresses.collectAsStateWithLifecycle()
    if (!enabled || item.key in dismissed) return

    val likely = verdict.level == ScamLevel.LIKELY_SCAM
    val container = if (likely) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (likely) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = if (likely) MaterialTheme.colorScheme.error else content,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(if (likely) R.string.scam_banner_title_likely else R.string.scam_banner_title_suspicious),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            verdict.claimedInstitution?.let {
                Text(stringResource(R.string.scam_banner_claims, BidiText.isolate(it)), style = MaterialTheme.typography.bodySmall)
            }
            verdict.reasons.take(MAX_REASONS).forEach { reason ->
                Text("• " + stringResource(reasonText(reason)), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.scam_banner_verify), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.scam_banner_advice), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onReport(item.key.toString()) }) { Text(stringResource(R.string.scam_banner_report)) }
                if (item.address in blockedAddresses) {
                    TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.scam_banner_blocked)) }
                } else {
                    TextButton(onClick = { viewModel.block(item.address) }) { Text(stringResource(R.string.scam_banner_block)) }
                }
            }
            TextButton(onClick = { viewModel.notAScam(item.key) }) { Text(stringResource(R.string.scam_banner_not_scam)) }
        }
    }
}

private const val MAX_REASONS = 3

/** Plain-language explanation of one [ScamReason]. */
@StringRes
internal fun reasonText(reason: ScamReason): Int = when (reason) {
    ScamReason.CREDIT_ALERT_FROM_PHONE_NUMBER -> R.string.scam_reason_phone_credit_alert
    ScamReason.UNKNOWN_SENDER_ALERT -> R.string.scam_reason_unknown_sender_alert
    ScamReason.DEBIT_ALERT_FROM_PHONE_NUMBER -> R.string.scam_reason_phone_debit_alert
    ScamReason.PHONE_NUMBER_CLAIMS_BANK -> R.string.scam_reason_phone_claims_bank
    ScamReason.LOOKALIKE_SENDER -> R.string.scam_reason_lookalike_sender
    ScamReason.UNVERIFIED_SENDER -> R.string.scam_reason_unverified_sender
    ScamReason.UNPREFIXED_BANK_HEADER -> R.string.scam_reason_unprefixed_header
    ScamReason.PROMOTIONAL_ROUTE -> R.string.scam_reason_promotional_route
    ScamReason.BRAND_MISMATCH -> R.string.scam_reason_brand_mismatch
    ScamReason.UNKNOWN_ACCOUNT -> R.string.scam_reason_unknown_account
    ScamReason.NO_ACCOUNT_AT_BANK -> R.string.scam_reason_no_account_at_bank
    ScamReason.RETURN_REQUEST -> R.string.scam_reason_return_request
    ScamReason.MOBILE_NUMBER_IN_ALERT -> R.string.scam_reason_mobile_in_alert
    ScamReason.PAYMENT_HANDLE_WITH_RETURN -> R.string.scam_reason_payment_handle
    ScamReason.LINK_IN_ALERT -> R.string.scam_reason_link_in_alert
    ScamReason.PIN_TO_RECEIVE -> R.string.scam_reason_pin_to_receive
    ScamReason.COLLECT_REQUEST -> R.string.scam_reason_collect_request
    ScamReason.FOLLOW_UP_AFTER_CREDIT -> R.string.scam_reason_follow_up
    ScamReason.RETURN_AFTER_GENUINE_CREDIT -> R.string.scam_reason_return_after_credit
}
