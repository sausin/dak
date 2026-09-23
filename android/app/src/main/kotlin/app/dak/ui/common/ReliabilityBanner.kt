package app.dak.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dak.R
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.notifications.ReliabilityCheckId
import app.dak.notifications.ReliabilityChecker
import app.dak.notifications.ReliabilityReport
import app.dak.settings.AppStateStore
import app.dak.telephony.DefaultSmsRole
import app.dak.telephony.role.SmsRoleMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.dak.telephony.R as TelephonyR

/**
 * Backs [ReliabilityBanner]: re-checks on every resume; a dismissal hides it for a day. Each check also re-reads the
 * default-SMS role through [SmsRoleMonitor], so a role lost or regained while Dak was in the background (the
 * DEFAULT_SMS_PACKAGE_CHANGED broadcast does not reach a stopped app on every OEM) holds or resumes sends at once.
 */
@HiltViewModel
class ReliabilityBannerViewModel @Inject constructor(
    private val checker: ReliabilityChecker,
    private val appState: AppStateStore,
    private val role: SmsRoleMonitor,
) : ViewModel() {
    private val report = MutableStateFlow(checker.check())

    /** The report to warn about, or null when everything is fine or the banner was dismissed recently. */
    val visibleReport: StateFlow<ReliabilityReport?> = combine(report, appState.reliabilityBannerDismissedAt) { r, dismissedAt ->
        val dismissed = System.currentTimeMillis() - dismissedAt < DISMISS_MILLIS
        // Losing the SMS role is never dismissible: nothing works without it.
        val critical = r.failures.any { it.id == ReliabilityCheckId.DEFAULT_SMS_APP }
        r.takeIf { it.restricted && (critical || !dismissed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() {
        runCatching { role.refresh() }
        report.value = checker.check()
    }

    fun dismiss() {
        viewModelScope.launch { appState.dismissReliabilityBanner() }
    }

    private companion object {
        const val DISMISS_MILLIS = 24 * 60 * 60_000L
    }
}

/**
 * Persistent warning shown at the top of the inbox while anything can delay notifications (not default SMS app,
 * notifications blocked, battery optimisation, background restriction). Tapping opens the self-test screen.
 * Renders nothing when all checks pass.
 */
@Composable
fun ReliabilityBanner(navigator: DakNavigator, modifier: Modifier = Modifier, viewModel: ReliabilityBannerViewModel = hiltViewModel()) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val context = LocalContext.current
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }
    val report by viewModel.visibleReport.collectAsStateWithLifecycle()
    val current = report ?: return
    val notDefault = current.failures.any { it.id == ReliabilityCheckId.DEFAULT_SMS_APP }
    WarningBanner(
        title = stringResource(if (notDefault) R.string.reliability_banner_not_default else R.string.reliability_banner_title),
        body = stringResource(R.string.reliability_banner_body),
        modifier = modifier,
        actionLabel = stringResource(if (notDefault) TelephonyR.string.dak_telephony_make_default else R.string.action_fix),
        onAction = {
            // Not the default SMS app: ask for the role right here (the self-test screen is one tap further away).
            val request = if (notDefault) runCatching { DefaultSmsRole.requestIntent(context) }.getOrNull() else null
            if (request == null || runCatching { roleLauncher.launch(request) }.isFailure) navigator.navigate(Routes.SELF_TEST)
        },
    )
}
