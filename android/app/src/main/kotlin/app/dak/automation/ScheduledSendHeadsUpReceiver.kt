package app.dak.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Target of the scheduled-message heads-up: its wakeup alarm ([ACTION_HEADS_UP_DUE], posts the heads-ups now due) and
 * its actions (`app.dak.action.SCHEDULED_HEADS_UP.<action>`, see [HeadsUpAction]) carrying only the scheduled-send id
 * ([EXTRA_ID]). Declared `android:exported="false"` with no intent filter: only Dak's own explicit, immutable
 * PendingIntents reach it. What each action may do is decided by [ScheduledSendHeadsUpActions].
 */
class ScheduledSendHeadsUpReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun headsUp(): ScheduledSendHeadsUp
        fun actions(): ScheduledSendHeadsUpActions
    }

    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val name = intent.action ?: return
        val pending = goAsync()
        scope.launch {
            try {
                if (name == ACTION_HEADS_UP_DUE) {
                    deps.headsUp().refresh()
                } else {
                    val action = HeadsUpAction.fromWire(name.removePrefix(ACTION_PREFIX).takeIf { name.startsWith(ACTION_PREFIX) })
                    val id = intent.getLongExtra(EXTRA_ID, -1L)
                    if (action != null && id > 0) deps.actions().perform(action, id)
                }
            } catch (e: Exception) {
                // The WorkManager safety net (or the next refresh) catches up; a failed action leaves the send as it was.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_HEADS_UP_DUE = "app.dak.action.SCHEDULED_HEADS_UP_DUE"
        const val ACTION_PREFIX = "app.dak.action.SCHEDULED_HEADS_UP."
        const val EXTRA_ID = "app.dak.extra.SCHEDULED_SEND_ID"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
