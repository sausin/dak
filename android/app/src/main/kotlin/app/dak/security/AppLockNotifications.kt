package app.dak.security

import android.app.PendingIntent
import android.content.Context
import app.dak.core.model.Message
import app.dak.index.enrich.ConversationIds
import app.dak.navigation.IntentRoutes
import app.dak.navigation.Routes
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore

/**
 * Notification-action policy while the app lock is on. Copy code and Mark read keep working from the notification
 * (what they touch is already visible there). Reply and Delete would act on the user's messages without unlocking,
 * so they become "open the conversation" actions instead: MainActivity holds the route until the user unlocks.
 */
object AppLockNotifications {

    /** True when Reply/Delete must open the app (and so pass the lock) instead of acting in the background. */
    fun actionsNeedUnlock(settings: SettingsStore): Boolean =
        LockMethodChoice.fromValue(settings.get(DakSettings.appLock)) != LockMethodChoice.OFF

    /** Opens [message]'s conversation, highlighting it; used for Reply/Delete while the app lock is on. */
    fun openInApp(context: Context, message: Message, action: String): PendingIntent {
        val route = Routes.conversation(ConversationIds.forThread(message.threadId), highlight = message.key.toString())
        return PendingIntent.getActivity(
            context,
            (route + "|" + action).hashCode(),
            IntentRoutes.open(context, route),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
