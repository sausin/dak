package app.dak.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.RemoteInput
import app.dak.core.model.MessageKey

/** Intent contract between posted notifications and [NotificationActionReceiver]. */
object NotificationActions {
    const val ACTION_COPY_CODE = "app.dak.notification.COPY_CODE"
    const val ACTION_DELETE = "app.dak.notification.DELETE"
    const val ACTION_MARK_READ = "app.dak.notification.MARK_READ"
    const val ACTION_REPLY = "app.dak.notification.REPLY"

    const val EXTRA_TAG = "tag"
    const val EXTRA_ID = "id"
    const val EXTRA_CODE = "code"
    const val EXTRA_MESSAGE_KEY = "messageKey"
    const val EXTRA_THREAD_ID = "threadId"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_SUB_ID = "subId"
    const val REMOTE_INPUT_REPLY = "reply_text"

    /** Where a posted notification lives, so an action can cancel or update it. */
    data class Target(val tag: String, val id: Int)

    fun copyCode(context: Context, target: Target, code: String): PendingIntent =
        broadcast(context, ACTION_COPY_CODE, target) { putExtra(EXTRA_CODE, code) }

    fun delete(context: Context, target: Target, key: MessageKey): PendingIntent =
        broadcast(context, ACTION_DELETE, target) { putExtra(EXTRA_MESSAGE_KEY, key.toString()) }

    fun markRead(context: Context, target: Target, key: MessageKey, threadId: Long): PendingIntent =
        broadcast(context, ACTION_MARK_READ, target) {
            putExtra(EXTRA_MESSAGE_KEY, key.toString())
            putExtra(EXTRA_THREAD_ID, threadId)
        }

    /** Reply PendingIntent; mutable (required so the system can attach the RemoteInput text). */
    fun reply(context: Context, target: Target, address: String, subId: Int, threadId: Long): PendingIntent {
        val intent = baseIntent(context, ACTION_REPLY, target)
            .putExtra(EXTRA_ADDRESS, address)
            .putExtra(EXTRA_SUB_ID, subId)
            .putExtra(EXTRA_THREAD_ID, threadId)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutable
        return PendingIntent.getBroadcast(context, requestCode(ACTION_REPLY, target), intent, flags)
    }

    fun replyRemoteInput(label: String): RemoteInput =
        RemoteInput.Builder(REMOTE_INPUT_REPLY).setLabel(label).build()

    fun targetOf(intent: Intent): Target? {
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return null
        return Target(tag, intent.getIntExtra(EXTRA_ID, 0))
    }

    private inline fun broadcast(context: Context, action: String, target: Target, extras: Intent.() -> Unit): PendingIntent {
        val intent = baseIntent(context, action, target).apply(extras)
        return PendingIntent.getBroadcast(
            context,
            requestCode(action, target),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun baseIntent(context: Context, action: String, target: Target): Intent =
        Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .setPackage(context.packageName)
            .putExtra(EXTRA_TAG, target.tag)
            .putExtra(EXTRA_ID, target.id)

    private fun requestCode(action: String, target: Target): Int = (action + "|" + target.tag).hashCode()
}
