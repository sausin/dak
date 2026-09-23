package app.dak.backup

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import app.dak.premium.consent.ConsentLedger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Delete my Dak data" (Settings → Privacy; GDPR Art. 17, DPDP Act 2023 s.12): erases everything Dak stores on this
 * phone, then ends the process. The caller must have confirmed with the user and passed the app's auth gate.
 *
 * Erased: the encrypted index (categories, ledger, rules, run history, activity log, bin, scheduled sends...),
 * settings and app state, the incoming-SMS journal, backup passphrase and schedule, app-lock settings, consent
 * records, caches, pending background work, Dak's notifications and every Android Keystore key Dak created.
 *
 * Not erased: the phone's shared SMS/MMS store (it belongs to the system and other apps read it too), backup and
 * export files in folders the user picked, and whether Dak is the default SMS app. The screen says so before asking.
 */
@Singleton
class DakDataEraser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consents: ConsentLedger,
) {
    /**
     * Erases and kills the process; returns only if the platform refused both the system wipe and the manual one.
     * Runs synchronously: call it off the main thread.
     */
    fun eraseAndExit(): Boolean {
        // Stop anything that could write while we wipe (scheduled sends, backups, index work).
        runCatching { WorkManager.getInstance(context).cancelAllWork() }.onFailure { Log.w(TAG, "cancel work failed", it) }
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
        runCatching { consents.clear() }
        // Keystore entries are app-scoped: every alias here is Dak's (index key wrap, backup passphrase, app lock).
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.aliases().toList().forEach { alias -> runCatching { keyStore.deleteEntry(alias) } }
        }.onFailure { Log.w(TAG, "keystore wipe failed", it) }

        // The system wipe also cancels alarms and jobs and kills the process; it returns only on failure.
        val am = context.getSystemService(ActivityManager::class.java)
        if (am != null && runCatching { am.clearApplicationUserData() }.getOrDefault(false)) return true

        // Fallback: delete Dak's private storage by hand, then end the process so nothing keeps stale state.
        val ok = manualWipe()
        Process.killProcess(Process.myPid())
        return ok
    }

    private fun manualWipe(): Boolean {
        val dataDir = context.applicationInfo.dataDir?.let { File(it) } ?: return false
        var ok = true
        dataDir.listFiles()?.forEach { child ->
            if (child.name == "lib") return@forEach // the platform's native-library link, not ours to delete
            if (!child.deleteRecursively()) ok = false
        }
        context.cacheDir.deleteRecursively()
        return ok
    }

    private companion object {
        const val TAG = "DakDataEraser"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
