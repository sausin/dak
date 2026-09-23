package app.dak.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.dak.R
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.premium.Feature
import app.dak.settings.ControlType
import app.dak.settings.DakSettings
import app.dak.settings.SettingTier
import app.dak.ui.privacy.DisclosureDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Shared tap handling for Settings rows (root search results and section screens): editors for value rows,
 * the upgrade sheet for locked rows, [SettingsActions] for action rows, and the prominent disclosure for rows that
 * would send data off the phone (see [SettingsViewModel.pendingDisclosure]). Renders its own dialogs/sheet and
 * hands [content] the click handler.
 */
@Composable
fun SettingsInteractionHost(
    viewModel: SettingsViewModel,
    navigator: DakNavigator,
    snackbar: SnackbarHostState,
    content: @Composable (onRowClick: (RowState) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<RowState?>(null) }
    var upgradeFor by remember { mutableStateOf<Feature?>(null) }
    var showUpgrade by remember { mutableStateOf(false) }
    val unavailable = stringResource(R.string.settings_action_unavailable)

    val onRowClick: (RowState) -> Unit = { row ->
        val tier = row.def.tier
        when {
            row.locked -> {
                upgradeFor = (tier as? SettingTier.Premium)?.feature
                showUpgrade = true
            }
            // The app lock needs verification (system prompt / PIN setup), never a plain value editor.
            row.key == DakSettings.appLock.key -> navigator.navigate(Routes.APP_LOCK)
            row.def.control is ControlType.Action -> {
                if (!SettingsActions.perform(row.key, context, navigator, viewModel)) {
                    scope.launch { snackbar.showSnackbar(unavailable) }
                }
            }
            row.def.control is ControlType.Toggle -> viewModel.toggle(row)
            else -> editing = row
        }
    }

    content(onRowClick)

    // Prominent disclosure before a row that sends data off the phone can be turned on (Play User Data policy).
    val pendingDisclosure by viewModel.pendingDisclosure.collectAsStateWithLifecycle()
    pendingDisclosure?.let { flow ->
        DisclosureDialog(
            flow = flow,
            onAllow = { viewModel.acceptDisclosure(flow) },
            onDecline = { viewModel.declineDisclosure(flow) },
        )
    }

    editing?.let { row ->
        SettingEditorDialog(
            row = row,
            onDismiss = { editing = null },
            onSave = { raw ->
                viewModel.setRaw(row.def, raw)
                editing = null
            },
        )
    }
    if (showUpgrade) {
        UpgradeSheet(feature = upgradeFor, onDismiss = { showUpgrade = false })
    }
}
