package app.dak.di

import android.content.Context
import app.dak.index.IndexSchedule
import app.dak.index.sync.IndexMaintenance
import app.dak.index.sync.IndexSync
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.ui.onboarding.SmsRole
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's handle on :core-index lifecycle: start sync once Dak is the default SMS app, apply the index schedule
 * chosen in onboarding or Settings, and rebuild on request. The index graph is resolved lazily on a background
 * thread so opening the encrypted database never happens on the main thread.
 */
@Singleton
class IndexControl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: Lazy<IndexSync>,
    private val maintenance: Lazy<IndexMaintenance>,
    private val settings: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val watchingSettings = AtomicBoolean(false)

    /** Starts index sync (stage-1 backfill, reconcile, provider observer) if Dak holds the SMS role. Idempotent. */
    fun startInitialSync() {
        if (!SmsRole.isDefault(context)) return
        scope.launch(Dispatchers.IO) { runCatching { sync.get().start() } }
        watchScheduleSetting()
    }

    /** Applies an index schedule chosen by the user (registry values `now` / `plugged` / `tonight`). */
    fun scheduleBackfill(settingValue: String) {
        scope.launch(Dispatchers.IO) { runCatching { maintenance.get().chooseSchedule(scheduleOf(settingValue)) } }
    }

    /** Settings → Backup and data → Rebuild index now. Returns true once the rebuild is started. */
    fun rebuildIndex(): Boolean {
        if (!SmsRole.isDefault(context)) return false
        scope.launch(Dispatchers.IO) { runCatching { maintenance.get().rebuild() } }
        return true
    }

    /** Later changes of the Settings row reschedule the backfill. */
    private fun watchScheduleSetting() {
        if (!watchingSettings.compareAndSet(false, true)) return
        scope.launch {
            settings.observe(DakSettings.indexSchedule).distinctUntilChanged().drop(1).collect { scheduleBackfill(it) }
        }
    }

    private fun scheduleOf(value: String): IndexSchedule = when (value) {
        "now" -> IndexSchedule.NOW
        "tonight" -> IndexSchedule.TONIGHT
        else -> IndexSchedule.WHEN_CHARGING
    }
}
