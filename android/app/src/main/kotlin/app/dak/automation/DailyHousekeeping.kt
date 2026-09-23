package app.dak.automation

import android.content.Context
import android.util.Log
import app.dak.automations.rule.Rule
import app.dak.automations.rule.isExpired
import app.dak.birthdays.BirthdayScheduler
import app.dak.index.repo.AuditLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Once-a-day upkeep for time-boxed automations, piggybacking on work that already wakes the app — an incoming
 * message ([AutomationRunner]), a scheduled-send run ([ScheduledSendWorker]) or a reboot/clock change
 * ([ScheduledSendReceiver]) — instead of adding a periodic wakeup of its own:
 * - disables expired rules (forwarding "until 31 Jul") and logs it;
 * - refreshes the "Forwarding active" notification;
 * - re-scans contacts for birthdays and schedules the next wish per enabled contact.
 */
@Singleton
class DailyHousekeeping @Inject constructor(
    @ApplicationContext context: Context,
    private val rules: RuleRepository,
    private val audit: AuditLogRepository,
    private val forwardingStatus: ForwardingStatusNotifier,
    private val birthdays: BirthdayScheduler,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** Runs [runNow] when the last run is at least ~a day old. Cheap to call often. */
    suspend fun runIfDue(nowMillis: Long = System.currentTimeMillis()) {
        val last = prefs.getLong(KEY_LAST_RUN, 0L)
        if (nowMillis - last in 0 until MIN_INTERVAL_MILLIS) return
        mutex.withLock {
            val again = prefs.getLong(KEY_LAST_RUN, 0L)
            if (nowMillis - again in 0 until MIN_INTERVAL_MILLIS) return
            prefs.edit().putLong(KEY_LAST_RUN, nowMillis).apply()
            runNow(nowMillis)
        }
    }

    /** All upkeep, now. Each step is independent: one failing does not stop the others. */
    suspend fun runNow(nowMillis: Long = System.currentTimeMillis()) {
        runCatching { expire(rules.enabledRules(), nowMillis) }.onFailure { Log.w(TAG, "expiry failed", it) }
        runCatching { forwardingStatus.refresh(nowMillis) }.onFailure { Log.w(TAG, "status refresh failed", it) }
        runCatching { birthdays.syncFromContacts(nowMillis) }.onFailure { Log.w(TAG, "birthday sync failed", it) }
    }

    /**
     * Disables the rules among [enabled] whose validity window has ended, logs each, refreshes the forwarding
     * notification when anything changed, and returns the rules that are still live.
     */
    suspend fun expire(enabled: List<Rule>, nowMillis: Long): List<Rule> {
        val (expired, live) = enabled.partition { it.isExpired(nowMillis) }
        if (expired.isEmpty()) return live
        for (rule in expired) {
            rules.setEnabled(rule.id, false)
            audit.log("rule:${rule.name}", ACTION_EXPIRED, rule.id, "window ended")
        }
        forwardingStatus.refresh(nowMillis)
        return live
    }

    companion object {
        const val ACTION_EXPIRED = "automation.expired"
        private const val PREFS = "dak_daily_housekeeping"
        private const val KEY_LAST_RUN = "last_run"
        private const val MIN_INTERVAL_MILLIS = 20L * 60 * 60 * 1000
        private const val TAG = "DakHousekeeping"
    }
}
