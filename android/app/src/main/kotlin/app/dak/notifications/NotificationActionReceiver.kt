package app.dak.notifications

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.dak.R
import app.dak.core.model.MessageKey
import app.dak.core.model.NO_SUB_ID
import app.dak.index.bin.DeletedBy
import app.dak.index.bin.RecycleBin
import app.dak.index.otp.OtpLifecycle
import app.dak.di.ApplicationScope
import app.dak.safety.SendCostGuard
import app.dak.telephony.MessageSender
import app.dak.telephony.OutgoingSms
import app.dak.telephony.ProviderWriter
import app.dak.telephony.SendResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Handles notification actions: Copy code, Delete now (soft delete into the recycle bin), Mark read and inline Reply.
 * Not exported; only Dak's own PendingIntents reach it.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var writer: ProviderWriter
    @Inject lateinit var sender: MessageSender
    @Inject lateinit var otpLifecycle: OtpLifecycle
    @Inject lateinit var recycleBin: RecycleBin
    @Inject lateinit var notifier: MessageNotifier
    @Inject lateinit var costGuard: SendCostGuard
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val target = NotificationActions.targetOf(intent) ?: return
        when (intent.action) {
            NotificationActions.ACTION_COPY_CODE -> copyCode(context, intent.getStringExtra(NotificationActions.EXTRA_CODE))
            NotificationActions.ACTION_DELETE -> runAsync(context) { delete(context, intent, target) }
            NotificationActions.ACTION_MARK_READ -> runAsync(context) { markRead(context, intent, target) }
            NotificationActions.ACTION_REPLY -> runAsync(context) { reply(context, intent, target) }
        }
    }

    private fun runAsync(context: Context, block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }

    private fun copyCode(context: Context, code: String?) {
        if (code.isNullOrEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(context.getString(R.string.clip_label_code), code)
        // Keep the code out of clipboard previews (Android 13+ honours the flag; the key is a plain string below 33).
        clip.description.extras = PersistableBundle().apply {
            putBoolean(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE",
                true,
            )
        }
        clipboard.setPrimaryClip(clip)
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, context.getString(R.string.toast_code_copied, code), Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun delete(context: Context, intent: Intent, target: NotificationActions.Target) {
        val key = intent.getStringExtra(NotificationActions.EXTRA_MESSAGE_KEY)?.let(MessageKey::parse) ?: return
        val ok = runCatching {
            if (target.tag.startsWith("otp:")) otpLifecycle.deleteNow(key)
            else recycleBin.moveToBin(listOf(key), DeletedBy.Manual).binIds.isNotEmpty()
        }.getOrDefault(false)
        if (ok) {
            NotificationManagerCompat.from(context).cancel(target.tag, target.id)
            notifier.refreshSummaries()
        } else {
            toast(context, R.string.toast_delete_failed)
        }
    }

    private suspend fun markRead(context: Context, intent: Intent, target: NotificationActions.Target) {
        val threadId = intent.getLongExtra(NotificationActions.EXTRA_THREAD_ID, -1L)
        val key = intent.getStringExtra(NotificationActions.EXTRA_MESSAGE_KEY)?.let(MessageKey::parse)
        runCatching {
            if (target.tag.startsWith("thread:") && threadId >= 0) writer.markThreadRead(threadId)
            else if (key != null) writer.markRead(key)
        }
        NotificationManagerCompat.from(context).cancel(target.tag, target.id)
        if (threadId >= 0 && target.tag.startsWith("thread:")) notifier.cancelForThread(threadId)
        notifier.refreshSummaries()
    }

    private suspend fun reply(context: Context, intent: Intent, target: NotificationActions.Target) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationActions.REMOTE_INPUT_REPLY)
            ?.toString()
            ?.trim()
        val address = intent.getStringExtra(NotificationActions.EXTRA_ADDRESS)
        if (text.isNullOrEmpty() || address.isNullOrEmpty()) return
        val threadId = intent.getLongExtra(NotificationActions.EXTRA_THREAD_ID, -1L).takeIf { it >= 0 }
        val subId = intent.getIntExtra(NotificationActions.EXTRA_SUB_ID, NO_SUB_ID)
        // No dialog is possible from a notification: premium-rate replies must be confirmed in the conversation.
        if (!runCatching { costGuard.allowUnattended(address.split(' ').filter { it.isNotBlank() }, subId) }.getOrDefault(true)) {
            toast(context, R.string.safe_reply_needs_confirmation)
            return
        }
        val result = runCatching {
            sender.sendSms(OutgoingSms(addresses = address.split(' ').filter { it.isNotBlank() }, body = text, subId = subId, threadId = threadId))
        }.getOrElse { SendResult.Failed(it.message ?: "error") }
        if (result is SendResult.Queued) {
            threadId?.let { runCatching { writer.markThreadRead(it) } }
            NotificationManagerCompat.from(context).cancel(target.tag, target.id)
            notifier.refreshSummaries()
        } else {
            toast(context, R.string.toast_reply_failed)
        }
    }

    private suspend fun toast(context: Context, res: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
    }
}
