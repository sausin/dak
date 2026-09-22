package app.dak.navigation

import androidx.navigation.NavHostController

/**
 * Thin wrapper over the [NavHostController] handed to every screen, so screens never touch the controller
 * directly. Build routes with [Routes] builder functions.
 */
class DakNavigator(private val nav: NavHostController) {

    /** Navigate to [route] (a concrete route from a [Routes] builder). Re-navigating to the top route is a no-op. */
    fun navigate(route: String) {
        if (currentRoute() == route) return
        nav.navigate(route) { launchSingleTop = true }
    }

    /** Pop one screen. Never pops the last screen; returns false when there was nothing to pop. */
    fun back(): Boolean = if (nav.previousBackStackEntry != null) nav.popBackStack() else false

    /** Pop back to the inbox, or navigate to it when it is not on the back stack. */
    fun toInbox() {
        if (!nav.popBackStack(Routes.INBOX, inclusive = false)) {
            nav.navigate(Routes.INBOX) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    /** Replace the whole back stack with [route] (used when onboarding finishes). */
    fun replaceAll(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    /** Open a conversation; [highlight] is an optional message key string to scroll to and flash. */
    fun openConversation(conversationId: String, highlight: String? = null) =
        navigate(Routes.conversation(conversationId, highlight))

    /** Open Settings, optionally deep-linked to one setting key (the row is scrolled to and highlighted). */
    fun openSetting(focusKey: String? = null) = navigate(Routes.settings(focusKey))

    private fun currentRoute(): String? {
        val entry = nav.currentBackStackEntry ?: return null
        val pattern = entry.destination.route ?: return null
        // Only exact matches for argument-free routes; argument routes always navigate.
        return if (pattern.contains('{')) null else pattern
    }
}
