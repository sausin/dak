package app.dak.ui.lock

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.navigation.DakNavigator
import app.dak.security.AutoLockTimeout
import app.dak.security.EffectiveLock
import app.dak.security.LockMethodChoice
import app.dak.security.RecentsProtection
import app.dak.ui.common.DakTopAppBar
import kotlinx.coroutines.launch

/**
 * Settings → Backup, data and privacy → App lock. Turning the lock on verifies the chosen method first (the system
 * prompt for the phone's screen lock, or choosing and confirming an app PIN); turning protection down (lock off,
 * switching method, changing or removing the PIN, sensitive screens off) asks the current method first.
 */
@Composable
fun AppLockScreen(navigator: DakNavigator, modifier: Modifier = Modifier, viewModel: AppLockViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val gate = rememberAppAuthGate()
    val device = rememberDeviceAuthenticator()
    var pinSetupThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    var choosing by remember { mutableStateOf<ChoiceKind?>(null) }

    val confirmTitle = stringResource(R.string.lock_confirm_change_title)
    val verifyTitle = stringResource(R.string.lock_verify_device_title)
    val fingerprintTitle = stringResource(R.string.lock_verify_fingerprint_title)
    val usePin = stringResource(R.string.lock_use_pin)
    val privacySwitched = stringResource(R.string.lock_privacy_switched)
    val needsScreenLock = stringResource(R.string.lock_needs_screen_lock)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    /** Runs [action] after confirming with the current method (nothing to confirm with: runs it). */
    fun confirmed(action: () -> Unit) {
        gate.authenticate(confirmTitle) { result ->
            if (result == ConfirmResult.SUCCESS || result == ConfirmResult.UNAVAILABLE) action()
        }
    }

    fun applyMethod(choice: LockMethodChoice) {
        if (viewModel.setMethod(choice)) say(privacySwitched)
    }

    fun choose(target: LockMethodChoice) {
        val current = state.config.method
        if (target == current) return
        fun proceed() {
            when (target) {
                LockMethodChoice.OFF -> applyMethod(LockMethodChoice.OFF)
                LockMethodChoice.DEVICE -> when {
                    !state.device.deviceSecure -> say(needsScreenLock)
                    current == LockMethodChoice.OFF -> device.deviceLock(verifyTitle, null) { outcome ->
                        if (outcome == PromptOutcome.SUCCESS) applyMethod(LockMethodChoice.DEVICE)
                    }
                    else -> applyMethod(LockMethodChoice.DEVICE)
                }
                LockMethodChoice.APP_PIN -> {
                    pinSetupThen = { applyMethod(LockMethodChoice.APP_PIN) }
                }
            }
        }
        if (current == LockMethodChoice.OFF) proceed() else confirmed { proceed() }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { DakTopAppBar(title = stringResource(R.string.lock_settings_title), onBack = { navigator.back() }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            DeviceStatusCard(
                state = state,
                onOpenSecuritySettings = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                },
            )

            val on = state.config.enabled
            SwitchRow(
                title = stringResource(R.string.lock_toggle_title),
                summary = stringResource(if (on) R.string.lock_toggle_on else R.string.lock_toggle_off),
                checked = on,
                onChange = { enable ->
                    if (enable) {
                        choose(if (state.device.deviceSecure) LockMethodChoice.DEVICE else LockMethodChoice.APP_PIN)
                    } else {
                        choose(LockMethodChoice.OFF)
                    }
                },
            )

            if (on) {
                SectionLabel(stringResource(R.string.lock_method_label))
                Column(Modifier.selectableGroup()) {
                    RadioRow(
                        title = stringResource(R.string.lock_method_device),
                        summary = stringResource(
                            when {
                                !state.device.deviceSecure -> R.string.lock_method_device_unavailable
                                state.device.biometricEnrolled -> R.string.lock_method_device_summary
                                else -> R.string.lock_method_device_no_biometric
                            },
                        ),
                        selected = state.config.method == LockMethodChoice.DEVICE,
                        enabled = state.device.deviceSecure,
                        onClick = { choose(LockMethodChoice.DEVICE) },
                    )
                    RadioRow(
                        title = stringResource(R.string.lock_method_pin),
                        summary = stringResource(R.string.lock_method_pin_summary),
                        selected = state.config.method == LockMethodChoice.APP_PIN,
                        enabled = true,
                        onClick = { choose(LockMethodChoice.APP_PIN) },
                    )
                }
                if (state.config.method != state.effective.asChoice()) {
                    Text(
                        stringResource(R.string.lock_method_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            if (state.hasPin || state.config.method == LockMethodChoice.APP_PIN) {
                if (!state.hasPin) {
                    // App PIN chosen but none stored on this phone (e.g. settings restored from a backup).
                    ActionRow(
                        title = stringResource(R.string.lock_set_pin),
                        summary = stringResource(R.string.lock_set_pin_summary),
                        onClick = { confirmed { pinSetupThen = {} } },
                    )
                }
                if (state.hasPin) {
                    ActionRow(
                        title = stringResource(R.string.lock_change_pin),
                        summary = stringResource(R.string.lock_change_pin_summary),
                        onClick = { confirmed { pinSetupThen = {} } },
                    )
                }
                if (state.hasPin && state.config.method != LockMethodChoice.APP_PIN) {
                    ActionRow(
                        title = stringResource(R.string.lock_remove_pin),
                        summary = stringResource(R.string.lock_remove_pin_summary),
                        onClick = { confirmed { viewModel.removePin() } },
                    )
                }
                if (state.config.method == LockMethodChoice.APP_PIN && state.hasPin) {
                    if (state.device.strongBiometricEnrolled) {
                        SwitchRow(
                            title = stringResource(R.string.lock_fingerprint_title),
                            summary = stringResource(R.string.lock_fingerprint_summary),
                            checked = state.fingerprintInsteadOfPin,
                            onChange = { enable ->
                                if (enable) {
                                    device.biometricOnly(fingerprintTitle, null, usePin) { outcome ->
                                        if (outcome == PromptOutcome.SUCCESS) viewModel.setFingerprintInsteadOfPin(true)
                                    }
                                } else {
                                    viewModel.setFingerprintInsteadOfPin(false)
                                }
                            },
                        )
                    } else if (state.device.biometricHardware) {
                        HintText(stringResource(R.string.lock_fingerprint_enroll_hint))
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.lock_when_label))
            ActionRow(
                title = stringResource(R.string.lock_auto_title),
                summary = stringResource(autoLockLabel(state.config.autoLock)),
                onClick = { choosing = ChoiceKind.AUTO_LOCK },
            )
            SwitchRow(
                title = stringResource(R.string.lock_screen_off_title),
                summary = stringResource(R.string.lock_screen_off_summary),
                checked = state.config.lockOnScreenOff,
                onChange = { viewModel.setLockOnScreenOff(it) },
            )
            ActionRow(
                title = stringResource(R.string.lock_recents_title),
                summary = stringResource(recentsLabel(state.config.recents)) + "\n" + stringResource(R.string.lock_recents_summary),
                onClick = { choosing = ChoiceKind.RECENTS },
            )
            SwitchRow(
                title = stringResource(R.string.lock_sensitive_setting_title),
                summary = stringResource(R.string.lock_sensitive_setting_summary),
                checked = state.config.protectSensitiveScreens,
                onChange = { enable ->
                    if (enable) viewModel.setProtectSensitive(true) else confirmed { viewModel.setProtectSensitive(false) }
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            InfoRow(stringResource(R.string.lock_notifications_info))
            if (on && state.lockScreenPrivacy == PRIVACY_FULL) {
                InfoRow(stringResource(R.string.lock_privacy_full_warning), warning = true)
            }
            if (state.effective != EffectiveLock.NONE) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { viewModel.lockNow() }) {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                        Text(stringResource(R.string.lock_now), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    pinSetupThen?.let { then ->
        PinSetupDialog(
            onDismiss = { pinSetupThen = null },
            onDone = {
                pinSetupThen = null
                then()
            },
        )
    }

    when (choosing) {
        ChoiceKind.AUTO_LOCK -> ChoiceDialog(
            title = stringResource(R.string.lock_auto_title),
            options = AutoLockTimeout.entries.map { it to stringResource(autoLockLabel(it)) },
            selected = state.config.autoLock,
            onSelect = { viewModel.setAutoLock(it); choosing = null },
            onDismiss = { choosing = null },
        )
        ChoiceKind.RECENTS -> ChoiceDialog(
            title = stringResource(R.string.lock_recents_title),
            options = RecentsProtection.entries.map { it to stringResource(recentsLabel(it)) },
            selected = state.config.recents,
            onSelect = { viewModel.setRecents(it); choosing = null },
            onDismiss = { choosing = null },
        )
        null -> Unit
    }
}

private enum class ChoiceKind { AUTO_LOCK, RECENTS }

private const val PRIVACY_FULL = "full"

private fun EffectiveLock.asChoice(): LockMethodChoice = when (this) {
    EffectiveLock.NONE -> LockMethodChoice.OFF
    EffectiveLock.DEVICE -> LockMethodChoice.DEVICE
    EffectiveLock.APP_PIN -> LockMethodChoice.APP_PIN
}

internal fun autoLockLabel(timeout: AutoLockTimeout): Int = when (timeout) {
    AutoLockTimeout.IMMEDIATELY -> R.string.lock_auto_immediately
    AutoLockTimeout.SECONDS_30 -> R.string.lock_auto_30s
    AutoLockTimeout.MINUTE_1 -> R.string.lock_auto_1m
    AutoLockTimeout.MINUTES_5 -> R.string.lock_auto_5m
    AutoLockTimeout.MINUTES_15 -> R.string.lock_auto_15m
}

private fun recentsLabel(protection: RecentsProtection): Int = when (protection) {
    RecentsProtection.WHEN_LOCK_ON -> R.string.lock_recents_when_on
    RecentsProtection.ALWAYS -> R.string.lock_recents_always
    RecentsProtection.NEVER -> R.string.lock_recents_never
}

@Composable
private fun DeviceStatusCard(state: AppLockUiState, onOpenSecuritySettings: () -> Unit) {
    val device = state.device
    val (text, warn) = when {
        !device.deviceSecure -> stringResource(R.string.lock_status_no_screen_lock) to true
        !device.biometricEnrolled -> stringResource(R.string.lock_status_no_biometric) to false
        else -> stringResource(R.string.lock_status_ready) to false
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(if (warn) Icons.Outlined.WarningAmber else Icons.Outlined.Info, contentDescription = null)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            if (!device.deviceSecure || !device.biometricEnrolled) {
                TextButton(onClick = onOpenSecuritySettings) { Text(stringResource(R.string.lock_open_security_settings)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun RadioRow(title: String, summary: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun ActionRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun InfoRow(text: String, warning: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            if (warning) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
            contentDescription = null,
            tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                            .padding(vertical = 12.dp),
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lock_cancel)) } },
    )
}
