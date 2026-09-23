package app.dak.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.settings.DakSettings

/**
 * What an Action row does when tapped: navigate to a screen, open a system settings page, or run a command.
 * Returns false when the row has nothing to open yet (the caller shows a short message).
 */
internal object SettingsActions {

    fun perform(key: String, context: Context, navigator: DakNavigator, commands: SettingsCommands): Boolean {
        val route = routeFor(key)
        if (route != null) {
            navigator.navigate(route)
            return true
        }
        return when (key) {
            DakSettings.exactAlarmPermission.key -> openExactAlarmSettings(context)
            DakSettings.rebuildIndex.key -> commands.rebuildIndex()
            else -> false
        }
    }

    private fun routeFor(key: String): String? = when (key) {
        DakSettings.selfTest.key -> Routes.SELF_TEST
        // Per-category and per-SIM sounds are channel settings; the channels screen lists and opens them.
        DakSettings.notificationChannels.key,
        DakSettings.perCategoryAlerts.key,
        DakSettings.soundPerSim.key,
        -> Routes.NOTIFICATION_CHANNELS
        DakSettings.blockList.key -> Routes.BLOCKED
        DakSettings.accounts.key -> Routes.PASSBOOK
        DakSettings.backupDestination.key,
        DakSettings.encryptionKeyRecovery.key,
        DakSettings.exportData.key,
        DakSettings.importData.key,
        -> Routes.BACKUP
        DakSettings.rulesList.key,
        DakSettings.scheduledSends.key,
        DakSettings.webhooks.key,
        DakSettings.sendApiKeys.key,
        DakSettings.auditLog.key,
        -> Routes.AUTOMATIONS
        DakSettings.forwarding.key -> Routes.FORWARDING
        DakSettings.birthdayWishes.key -> Routes.BIRTHDAYS
        DakSettings.broadcastLists.key -> Routes.BROADCASTS
        else -> null
    }

    fun openAppNotificationSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return start(
            context,
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)),
        )
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Commands Action rows can run that are not navigation (provided by the ViewModel). */
fun interface SettingsCommands {
    /** Starts a full index rebuild; returns true when it was scheduled. */
    fun rebuildIndex(): Boolean
}
