package app.dak.automation

import android.content.Context
import android.util.Log
import app.dak.automations.rule.Rule
import app.dak.automations.rule.isExpired
import app.dak.birthdays.BirthdayScheduler
import app.dak.index.repo.AuditLogRepository
import app.dak.index.repo.AutomationRunStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Once-a-day upkeep for time-boxed automations. It never schedules a wakeup of its own: it runs from the daily
 * `dak-maintenance` job ([AutomationMaintenanceTask]) and piggybacks on work that already woke the app — an incoming
 * message ([AutomationRunner]) or a scheduled-send run ([ScheduledSendWorker]) — whichever comes first each day:
 * - disables expired rules (forwarding "until 31 Jul") and logs it (they stay saved, ready to use again);
 * - turns off automations that send messages off the phone if no app lock is set up any more
 *   ([OutboundAutomationGuard.enforce], e.g. the phone's screen lock was removed while Dak was not running);
 * - refreshes the "Forwarding active" notification;
 * - trims the automation run log to its retention ([AutomationRunStore.trim]: a year, or the newest 5,000 runs);
 * - re-scans contacts for birthdays and re-arms the next wish per enabled contact.
 */
@Singleton
class DailyHousekeeping @Inject constructor(
    @ApplicationContext context: Context,
    private val rules: RuleRepository,
    private val audit: AuditLogRepository,
    private val forwardingStatus: ForwardingStatusNotifier,
    private val birthdays: BirthdayScheduler,
    private val outboundGuard: OutboundAutomationGuard,
    private val runLog: AutomationRunStore,
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
        runCatching { outboundGuard.enforce() }.onFailure { Log.w(TAG, "app lock check failed", it) }
        runCatching { forwardingStatus.refresh(nowMillis) }.onFailure { Log.w(TAG, "status refresh failed", it) }
        runCatching { runLog.trim(nowMillis) }.onFailure { Log.w(TAG, "run log trim failed", it) }
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
            // Not "by the user": an ended rule stays saved for re-use and keeps its "Was this you?" reminder.
            rules.setEnabled(rule.id, false, byUser = false)
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
