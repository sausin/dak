package app.dak.ui.notifications

import android.content.ActivityNotFoundException
import android.content.Context
import app.dak.notifications.ConversationChannels
import app.dak.notifications.NotificationChannels
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * "Custom notifications" from a conversation's menu: creates the conversation's own channel (and conversation
 * shortcut) if needed, then opens its system settings page, where the user picks sound, vibration, priority and
 * bubbles. Works for folded sender groups (`m:` ids) as well as threads.
 */
object CustomNotifications {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun conversationChannels(): ConversationChannels
    }

    /** Returns false when no settings screen could be opened. */
    fun open(context: Context, conversationId: String, title: String, addresses: List<String>): Boolean {
        val channels = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).conversationChannels()
        val channelId = channels.enable(conversationId, title, addresses)
        val intent = NotificationChannels.settingsIntent(context, channelId, ConversationChannels.shortcutIdFor(conversationId))
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
