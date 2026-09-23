package app.dak.settings

import android.util.Log
import app.dak.di.ApplicationScope
import app.dak.telephony.TelephonySettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Mirrors the MMS report settings of the settings screen ([DakSettings.mmsReadReceipts],
 * [DakSettings.mmsDeliveryToSenders]) into [TelephonySettings], the SharedPreferences that `:core-telephony`'s
 * receivers and workers read synchronously (a WAP push can arrive in a cold process before any settings flow runs;
 * it then sees the last mirrored value). Started from `DakApplication`, off the main thread.
 */
@Singleton
class TelephonySettingsSync @Inject constructor(
    private val settings: dagger.Lazy<SettingsStore>,
    private val telephony: dagger.Lazy<TelephonySettings>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching {
                settings.get().observe(DakSettings.mmsReadReceipts).distinctUntilChanged()
                    .collect { telephony.get().sendMmsReadReceipts = it }
            }.onFailure { Log.w(TAG, "read receipt setting not mirrored", it) }
        }
        scope.launch {
            runCatching {
                settings.get().observe(DakSettings.mmsDeliveryToSenders).distinctUntilChanged()
                    .collect { telephony.get().allowMmsDeliveryReportsToSenders = it }
            }.onFailure { Log.w(TAG, "report-allowed setting not mirrored", it) }
        }
    }

    private companion object {
        const val TAG = "DakTelephonySync"
    }
}
