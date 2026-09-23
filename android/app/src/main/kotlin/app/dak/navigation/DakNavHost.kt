package app.dak.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.dak.R
import app.dak.ui.automations.AutomationsScreen
import app.dak.ui.birthdays.BirthdaysScreen
import app.dak.ui.broadcast.BroadcastListScreen
import app.dak.ui.broadcast.BroadcastsScreen
import app.dak.ui.forwarding.AutomationHistoryScreen
import app.dak.ui.forwarding.ForwardingScreen
import app.dak.ui.fraud.FraudHelpScreen
import app.dak.ui.notifications.NotificationChannelsScreen
import app.dak.ui.sendergroups.SenderGroupsScreen
import app.dak.ui.backup.BackupScreen
import app.dak.ui.bin.BinScreen
import app.dak.ui.blocked.BlockedScreen
import app.dak.ui.conversation.ConversationScreen
import app.dak.ui.conversation.NewConversationScreen
import app.dak.ui.inbox.InboxScreen
import app.dak.ui.lock.AppLockScreen
import app.dak.ui.lock.SensitiveScreenGate
import app.dak.ui.onboarding.OnboardingScreen
import app.dak.ui.passbook.AccountScreen
import app.dak.ui.passbook.PassbookScreen
import app.dak.ui.search.SearchScreen
import app.dak.ui.selftest.SelfTestScreen
import app.dak.ui.settings.SettingsGroupScreen
import app.dak.ui.settings.SettingsScreen

private fun optionalString(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

private fun requiredString(name: String) = navArgument(name) { type = NavType.StringType }

/**
 * The app's single NavHost. Every destination is registered here with its arguments; screens read their
 * arguments from `SavedStateHandle` in their ViewModel using the `Routes.ARG_*` names.
 */
@Composable
fun DakNavHost(navController: NavHostController, startDestination: String, modifier: Modifier = Modifier) {
    val navigator = remember(navController) { DakNavigator(navController) }
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onFinished = { navigator.replaceAll(Routes.INBOX) })
        }
        composable(Routes.INBOX) { InboxScreen(navigator) }
        composable(
            Routes.CONVERSATION,
            arguments = listOf(requiredString(Routes.ARG_CONVERSATION_ID), optionalString(Routes.ARG_HIGHLIGHT)),
        ) { ConversationScreen(navigator) }
        composable(
            Routes.COMPOSE,
            arguments = listOf(optionalString(Routes.ARG_TO), optionalString(Routes.ARG_BODY)),
        ) { NewConversationScreen(navigator) }
        composable(Routes.SEARCH, arguments = listOf(optionalString(Routes.ARG_Q))) { SearchScreen(navigator) }
        composable(Routes.SETTINGS, arguments = listOf(optionalString(Routes.ARG_FOCUS))) { SettingsScreen(navigator) }
        composable(
            Routes.SETTINGS_GROUP,
            arguments = listOf(requiredString(Routes.ARG_GROUP), optionalString(Routes.ARG_FOCUS)),
        ) { SettingsGroupScreen(navigator) }
        composable(Routes.BIN) {
            SensitiveScreenGate(stringResource(R.string.scr_bin_title), onBack = { navigator.back() }) { BinScreen(navigator) }
        }
        composable(Routes.PASSBOOK) {
            SensitiveScreenGate(stringResource(R.string.scr_passbook_title), onBack = { navigator.back() }) { PassbookScreen(navigator) }
        }
        composable(Routes.PASSBOOK_ACCOUNT, arguments = listOf(requiredString(Routes.ARG_ACCOUNT_ID))) {
            SensitiveScreenGate(stringResource(R.string.scr_passbook_title), onBack = { navigator.back() }) { AccountScreen(navigator) }
        }
        composable(Routes.AUTOMATIONS) {
            SensitiveScreenGate(stringResource(R.string.scr_automations_title), onBack = { navigator.back() }) { AutomationsScreen(navigator) }
        }
        composable(Routes.BACKUP) {
            SensitiveScreenGate(stringResource(R.string.scr_backup_title), onBack = { navigator.back() }) { BackupScreen(navigator) }
        }
        composable(Routes.BLOCKED) { BlockedScreen(navigator) }
        composable(Routes.SELF_TEST) { SelfTestScreen(navigator) }
        composable(Routes.FORWARDING) {
            SensitiveScreenGate(stringResource(R.string.fw_title), onBack = { navigator.back() }) { ForwardingScreen(navigator) }
        }
        composable(Routes.AUTOMATION_HISTORY, arguments = listOf(optionalString(Routes.ARG_RULE_ID))) {
            // Lists forwarded message text: gated like the Forwarding screen.
            SensitiveScreenGate(stringResource(R.string.fw_history_menu), onBack = { navigator.back() }) { AutomationHistoryScreen(navigator) }
        }
        composable(Routes.BIRTHDAYS) { BirthdaysScreen(navigator) }
        composable(Routes.FRAUD_HELP, arguments = listOf(optionalString(Routes.ARG_MESSAGE))) { FraudHelpScreen(navigator) }
        composable(Routes.SENDER_GROUPS) { SenderGroupsScreen(navigator) }
        composable(Routes.NOTIFICATION_CHANNELS) { NotificationChannelsScreen(navigator) }
        composable(Routes.APP_LOCK) { AppLockScreen(navigator) }
        composable(Routes.BROADCASTS) { BroadcastsScreen(navigator) }
        composable(Routes.BROADCAST, arguments = listOf(requiredString(Routes.ARG_BROADCAST_ID))) { BroadcastListScreen(navigator) }
    }
}
