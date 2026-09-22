package app.dak.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.ui.theme.DakTheme

/**
 * First-run flow. Order is deliberate (Play policy + "reliability is the product"):
 * 1. what SMS can't do and the RCS trade-off, *before* the role dialog;
 * 2. the default-SMS role request;
 * 3. only then notifications, contacts and phone permissions;
 * 4. when to index the full history (recent messages are indexed immediately);
 * 5. battery-optimisation exemption and OEM background-killer guidance.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit, modifier: Modifier = Modifier, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    BackHandler(enabled = state.step != OnboardingStep.WELCOME) { viewModel.back() }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onRoleResult()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionsResult()
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refresh()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            LinearProgressIndicator(
                progress = { (state.step.ordinal + 1f) / OnboardingStep.entries.size },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
            AnimatedContent(targetState = state.step, label = "onboardingStep", modifier = Modifier.weight(1f)) { step ->
                when (step) {
                    OnboardingStep.WELCOME -> WelcomeStep(onContinue = viewModel::next)
                    OnboardingStep.DEFAULT_APP -> DefaultAppStep(
                        declined = state.roleDeclined,
                        onRequest = { roleLauncher.launch(SmsRole.requestIntent(context)) },
                    )
                    OnboardingStep.PERMISSIONS -> PermissionsStep(
                        state = state,
                        onGrant = {
                            val missing = RuntimePermissions.all.filterNot { RuntimePermissions.granted(context, it) }
                            if (missing.isEmpty()) viewModel.onPermissionsResult() else permissionLauncher.launch(missing.toTypedArray())
                        },
                        onSkip = viewModel::onPermissionsResult,
                    )
                    OnboardingStep.INDEXING -> IndexingStep(
                        choice = state.indexChoice,
                        onChoose = viewModel::chooseIndex,
                        onContinue = viewModel::confirmIndex,
                    )
                    OnboardingStep.RELIABILITY -> ReliabilityStep(
                        state = state,
                        onBattery = {
                            val launched = runCatching { batteryLauncher.launch(BatteryOptimization.requestIntent(context)) }.isSuccess
                            if (!launched) context.startSafely(BatteryOptimization.settingsIntent())
                        },
                        onOemSettings = { state.oem?.open(context) },
                        onFinish = { viewModel.finish(onFinished) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepScaffold(
    title: String,
    body: String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            if (secondaryLabel != null && onSecondary != null) TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            Button(onClick = onPrimary) { Text(primaryLabel) }
        }
    }
}

@Composable
private fun Bullet(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.onboarding_welcome_title),
        body = stringResource(R.string.onboarding_welcome_body),
        primaryLabel = stringResource(R.string.action_continue),
        onPrimary = onContinue,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.onboarding_limits_title), style = MaterialTheme.typography.titleMedium)
                listOf(
                    R.string.onboarding_limit_typing,
                    R.string.onboarding_limit_receipts,
                    R.string.onboarding_limit_reactions,
                    R.string.onboarding_limit_media,
                ).forEach { res ->
                    Text("• " + stringResource(res), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.onboarding_limit_delivery), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = DakTheme.colors.warning.container, contentColor = DakTheme.colors.warning.content)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.onboarding_rcs_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.onboarding_rcs_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Bullet(Icons.Outlined.Info, stringResource(R.string.onboarding_private_title), stringResource(R.string.onboarding_private_body))
    }
}

@Composable
private fun DefaultAppStep(declined: Boolean, onRequest: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.onboarding_role_title),
        body = stringResource(R.string.onboarding_role_body),
        primaryLabel = stringResource(if (declined) R.string.action_try_again else R.string.onboarding_role_button),
        onPrimary = onRequest,
    ) {
        Bullet(Icons.Outlined.Sms, stringResource(R.string.onboarding_role_point_receive), stringResource(R.string.onboarding_role_point_receive_body))
        Bullet(Icons.Outlined.CheckCircle, stringResource(R.string.onboarding_role_point_switch), stringResource(R.string.onboarding_role_point_switch_body))
        if (declined) {
            Text(
                stringResource(R.string.onboarding_role_declined),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PermissionsStep(state: OnboardingUiState, onGrant: () -> Unit, onSkip: () -> Unit) {
    val allGranted = state.notificationsGranted && state.contactsGranted && state.phoneGranted
    StepScaffold(
        title = stringResource(R.string.onboarding_permissions_title),
        body = stringResource(R.string.onboarding_permissions_body),
        primaryLabel = stringResource(if (allGranted) R.string.action_continue else R.string.onboarding_permissions_button),
        onPrimary = onGrant,
        secondaryLabel = if (allGranted) null else stringResource(R.string.action_skip),
        onSecondary = onSkip,
    ) {
        PermissionLine(Icons.Outlined.Notifications, R.string.onboarding_perm_notifications, R.string.onboarding_perm_notifications_body, state.notificationsGranted)
        PermissionLine(Icons.Outlined.Contacts, R.string.onboarding_perm_contacts, R.string.onboarding_perm_contacts_body, state.contactsGranted)
        PermissionLine(Icons.Outlined.PhoneAndroid, R.string.onboarding_perm_phone, R.string.onboarding_perm_phone_body, state.phoneGranted)
    }
}

@Composable
private fun PermissionLine(icon: ImageVector, title: Int, body: Int, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Bullet(icon, stringResource(title), stringResource(body)) }
        if (granted) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = stringResource(R.string.state_granted),
                tint = DakTheme.colors.success.accent,
            )
        }
    }
}

@Composable
private fun IndexingStep(choice: IndexChoice, onChoose: (IndexChoice) -> Unit, onContinue: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.onboarding_index_title),
        body = stringResource(R.string.onboarding_index_body),
        primaryLabel = stringResource(R.string.action_continue),
        onPrimary = onContinue,
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Triple(IndexChoice.NOW, R.string.onboarding_index_now, R.string.onboarding_index_now_body),
                Triple(IndexChoice.PLUGGED_IN, R.string.onboarding_index_plugged, R.string.onboarding_index_plugged_body),
                Triple(IndexChoice.TONIGHT, R.string.onboarding_index_tonight, R.string.onboarding_index_tonight_body),
            ).forEach { (option, title, body) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = option == choice, role = Role.RadioButton, onClick = { onChoose(option) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == choice, onClick = null)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReliabilityStep(state: OnboardingUiState, onBattery: () -> Unit, onOemSettings: () -> Unit, onFinish: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.onboarding_reliability_title),
        body = stringResource(R.string.onboarding_reliability_body),
        primaryLabel = stringResource(R.string.action_finish),
        onPrimary = onFinish,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bullet(
                    Icons.Outlined.BatteryAlert,
                    stringResource(R.string.onboarding_battery_title),
                    stringResource(
                        if (state.batteryOptimizationIgnored) R.string.onboarding_battery_done else R.string.onboarding_battery_body,
                    ),
                )
                if (!state.batteryOptimizationIgnored) {
                    OutlinedButton(onClick = onBattery) { Text(stringResource(R.string.onboarding_battery_button)) }
                }
                if (state.backgroundRestricted) {
                    Text(
                        stringResource(R.string.reliability_background_restricted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        val oem = state.oem
        if (oem != null) {
            Card(colors = CardDefaults.cardColors(containerColor = DakTheme.colors.warning.container, contentColor = DakTheme.colors.warning.content)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.onboarding_oem_title, oem.brand), style = MaterialTheme.typography.titleMedium)
                    oem.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(onClick = onOemSettings) { Text(stringResource(R.string.onboarding_oem_button)) }
                }
            }
        }
        Text(
            stringResource(R.string.onboarding_selftest_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
