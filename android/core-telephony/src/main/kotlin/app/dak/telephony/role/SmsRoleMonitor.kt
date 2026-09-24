package app.dak.telephony.role

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import app.dak.telephony.DefaultSmsRole
import app.dak.telephony.di.TelephonyScope
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.runAsync
import app.dak.telephony.mms.MmsDownloadManager
import app.dak.telephony.send.HeldSendStore
import app.dak.telephony.send.TelephonyMessageSender
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks whether Dak holds the default SMS role (`RoleManager.ROLE_SMS`, or `Telephony.Sms.getDefaultSmsPackage`
 * below Android 10) and reacts to changes:
 * - **Lost** (another app was made default): nothing is cancelled or dropped. Sends that come due are held instead
 *   of attempted: QUEUED / outbox rows stay where they are and are remembered by key, texts that never reached the
 *   provider (scheduled sends) are kept as copies ([app.dak.telephony.send.HeldSendStore]); MMS auto-download stops
 *   (and the platform stops delivering SMS / WAP push to us anyway). Texts to emergency numbers are never held.
 *   The composer turns read-only through [isDefault].
 * - **Regained**: held sends go out (through the normal rate limiter) and pending MMS downloads resume.
 *
 * Fed by [DefaultSmsChangedReceiver] (`Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED`), by [refresh],
 * which the UI calls on start / resume (the broadcast is not delivered to a stopped app on every OEM), by the sender
 * before each attempt ([isDefaultNow]) and at boot ([resumeIfDefault]). The broadcast's extra is never trusted: the
 * role is always re-read from the platform.
 */
@Singleton
class SmsRoleMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    @TelephonyScope private val scope: CoroutineScope,
    private val held: HeldSendStore,
    // Providers: the sender and the download manager themselves consult this monitor.
    private val sender: Provider<TelephonyMessageSender>,
    private val downloads: Provider<MmsDownloadManager>,
) {
    private val state = MutableStateFlow(read(fallback = true))
    private val transitionLock = Mutex()

    /** True while Dak is the default SMS app. Updated by [refresh] and the role broadcast. */
    val isDefault: StateFlow<Boolean> = state.asStateFlow()

    /** Re-reads the role now (one binder call) and returns it, acting on a change. */
    fun isDefaultNow(): Boolean {
        val (now, change) = reread()
        if (change == RoleChange.REGAINED) scope.launch { onRegained() }
        return now
    }

    /**
     * Re-reads the role and handles a lost / regained transition. Held work is resumed in the background whenever
     * the role is held and something is still held (so a resume cut short by process death is picked up by the next
     * refresh, e.g. on app start / resume). Safe to call often and from any thread.
     */
    fun refresh(): Boolean {
        val (now, change) = reread()
        if (now && (change == RoleChange.REGAINED || !held.isEmpty())) scope.launch { onRegained() }
        return now
    }

    /** At boot / app update: resumes work held before the restart when the role is back. */
    suspend fun resumeIfDefault() {
        if (reread().first) onRegained()
    }

    private fun reread(): Pair<Boolean, RoleChange> {
        val now = read(fallback = state.value)
        val change = RoleTransitions.between(state.getAndUpdate { now }, now)
        if (change == RoleChange.LOST) Log.i(TAG, "no longer the default SMS app: holding sends and downloads")
        return now to change
    }

    private suspend fun onRegained() {
        transitionLock.withLock {
            Log.i(TAG, "default SMS app: resuming held sends and downloads")
            runCatching { sender.get().resumeHeld() }
                .onFailure { Log.w(TAG, "resuming held sends failed: ${it.javaClass.simpleName}") }
            runCatching { downloads.get().resumePending() }
                .onFailure { Log.w(TAG, "resuming downloads failed: ${it.javaClass.simpleName}") }
        }
    }

    /** A role-service hiccup must not flip Dak to read-only: keep [fallback] (the last known state). */
    private fun read(fallback: Boolean): Boolean = try {
        DefaultSmsRole.isDefault(context)
    } catch (e: RuntimeException) {
        fallback
    }
}

/**
 * `android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED`: sent (Android 7+) to the previous and the new default SMS
 * app. Exported so the platform can reach it; the action is a protected broadcast and [SmsRoleMonitor] re-reads the
 * role anyway, so a spoofed intent can at most trigger a harmless re-check.
 */
class DefaultSmsChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) return
        runAsync(context) { it.smsRoleMonitor().refresh() }
    }
}
