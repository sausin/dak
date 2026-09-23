package app.dak.ui.privacy

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.backup.DakDataEraser
import app.dak.backup.PersonalDataExporter
import app.dak.premium.Entitlements
import app.dak.premium.Feature
import app.dak.premium.consent.ConsentLedger
import app.dak.premium.consent.ConsentRecord
import app.dak.premium.consent.DataFlow
import app.dak.premium.consent.Disclosures
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One off-device data flow as the Privacy screen shows it. */
data class DataFlowState(
    val flow: DataFlow,
    val title: String,
    val granted: Boolean,
    /** False when the feature does not exist in this build or tier (it can still be withdrawn if granted). */
    val available: Boolean,
    val lastChangedAt: Long?,
)

sealed interface ExportState {
    data object Idle : ExportState
    data object Running : ExportState
    data class Done(val sections: Int, val rows: Int) : ExportState
    data class Failed(val reason: String?) : ExportState
}

/**
 * Settings → Privacy: consent state per data flow (grant, decline, withdraw), the consent record history, "Export my
 * Dak data" and "Delete my Dak data". Withdrawing turns the feature off at once: the gates in `premium-api`
 * ([app.dak.premium.consent.ConsentGatedPremiumGateway] and friends) and [ConsentGatedCloudClassifier] read the
 * ledger on every call, and the Jev toggle is switched off here too.
 */
@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val consents: ConsentLedger,
    private val settings: SettingsStore,
    private val entitlements: Entitlements,
    private val exporter: PersonalDataExporter,
    private val eraser: DakDataEraser,
) : ViewModel() {

    val flows: StateFlow<List<DataFlowState>> = combine(consents.records, entitlements.granted) { records, _ ->
        DataFlow.entries.map { flow ->
            DataFlowState(
                flow = flow,
                title = Disclosures.forFlow(flow).title,
                granted = consents.isGranted(flow),
                available = isAvailable(flow),
                lastChangedAt = records.lastOrNull { it.flow == flow.id }?.atMillis,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Consent records, newest first. */
    val records: StateFlow<List<ConsentRecord>> = consents.records
        .map { it.reversed() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = exportState.asStateFlow()

    fun grant(flow: DataFlow) {
        consents.grant(flow, SOURCE)
        if (flow == DataFlow.CLOUD_CLASSIFICATION) settings.set(DakSettings.jevOptIn, true)
    }

    fun decline(flow: DataFlow) {
        consents.decline(flow, SOURCE)
    }

    fun withdraw(flow: DataFlow) {
        consents.withdraw(flow, SOURCE)
        if (flow == DataFlow.CLOUD_CLASSIFICATION) settings.set(DakSettings.jevOptIn, false)
    }

    fun exportTo(uri: Uri) {
        if (exportState.value == ExportState.Running) return
        exportState.value = ExportState.Running
        viewModelScope.launch {
            exportState.value = try {
                val result = exporter.export(uri)
                ExportState.Done(sections = result.sections.size, rows = result.sections.values.sumOf { it ?: 0 })
            } catch (e: Exception) {
                ExportState.Failed(e.message)
            }
        }
    }

    fun clearExportResult() {
        if (exportState.value != ExportState.Running) exportState.value = ExportState.Idle
    }

    /** Erases Dak's data and ends the process (the UI has already confirmed and authenticated). */
    fun eraseEverything(onFailed: () -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { eraser.eraseAndExit() }
            if (!ok) onFailed()
        }
    }

    private fun isAvailable(flow: DataFlow): Boolean = when (flow) {
        // The Jev switch exists in every build; nothing is sent until a classifier is bound (see ConsentGatedCloudClassifier).
        DataFlow.CLOUD_CLASSIFICATION -> true
        DataFlow.WEBHOOKS -> entitlements.has(Feature.WEBHOOKS)
        DataFlow.WEB_RELAY -> entitlements.has(Feature.WEB_CLIENT) || entitlements.has(Feature.RELAY_RULES)
        DataFlow.AI_SEARCH -> entitlements.has(Feature.AI_SEARCH)
    }

    private companion object {
        const val SOURCE = "privacy"
    }
}
