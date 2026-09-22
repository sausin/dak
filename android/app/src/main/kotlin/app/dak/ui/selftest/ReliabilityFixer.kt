package app.dak.ui.selftest

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.dak.notifications.ReliabilityFix
import app.dak.ui.onboarding.BatteryOptimization
import app.dak.ui.onboarding.RuntimePermissions
import app.dak.ui.onboarding.SmsRole
import app.dak.ui.onboarding.appDetailsIntent
import app.dak.ui.onboarding.startSafely
import app.dak.ui.settings.SettingsActions

/**
 * Returns a function that performs a [ReliabilityFix] (system dialogs and settings pages), calling [onReturn] when
 * the user comes back from a dialog so the caller can re-run its checks.
 */
@Composable
fun rememberReliabilityFixer(onReturn: () -> Unit): (ReliabilityFix) -> Unit {
    val context = LocalContext.current
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onReturn() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onReturn() }
    return remember(context, activityLauncher, permissionLauncher) {
        val perform: (ReliabilityFix) -> Unit = { fix ->
            when (fix) {
                ReliabilityFix.RequestSmsRole -> runCatching { activityLauncher.launch(SmsRole.requestIntent(context)) }
                ReliabilityFix.RequestNotificationPermission -> {
                    val perms = RuntimePermissions.notifications
                    if (perms.isEmpty()) SettingsActions.openAppNotificationSettings(context)
                    else permissionLauncher.launch(perms.toTypedArray())
                }
                ReliabilityFix.OpenAppNotificationSettings -> SettingsActions.openAppNotificationSettings(context)
                is ReliabilityFix.OpenChannelSettings -> context.startSafely(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, fix.channelId),
                )
                ReliabilityFix.RequestBatteryExemption -> {
                    val ok = runCatching { activityLauncher.launch(BatteryOptimization.requestIntent(context)) }.isSuccess
                    if (!ok) context.startSafely(BatteryOptimization.settingsIntent())
                }
                ReliabilityFix.OpenAppDetails -> context.startSafely(appDetailsIntent(context))
            }
        }
        perform
    }
}
