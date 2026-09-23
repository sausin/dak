package app.dak.automation

import android.content.Context
import android.util.Log
import app.dak.automations.rule.Rule
import app.dak.automations.rule.sendsOffDevice
import app.dak.di.ApplicationScope
import app.dak.index.repo.AuditLogRepository
import app.dak.security.AppLockManager
import app.dak.security.EffectiveLock
import app.dak.security.LockMethodChoice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Pure decisions of [OutboundAutomationGuard], kept apart for unit tests. */
object OutboundGuardRules {

    /**
     * Why automations that send off the phone must be turned off when no app lock is enforceable: before the one-time
     * upgrade check ran ([migrated] false) it is "app lock needed" (they were set up before it was required); after
     * that, the lock was switched off, or the phone's screen lock was removed under a lock that relied on it.
     */
    fun holdFor(method: LockMethodChoice, deviceSecure: Boolean, migrated: Boolean): ForwardingHold = when {
        !migrated -> ForwardingHold.LOCK_NEEDED
        method == LockMethodChoice.OFF -> ForwardingHold.LOCK_OFF
        !deviceSecure -> ForwardingHold.SCREEN_LOCK_REMOVED
        else -> ForwardingHold.LOCK_NEEDED
    }

    /**
     * True when removing the app PIN would leave no app lock at all while [enabledOutbound] automations are on, so
     * the user must be told they will be turned off first.
     */
    fun removingPinDisablesOutbound(effectiveWithoutPin: EffectiveLock, enabledOutbound: Int): Boolean =
        effectiveWithoutPin == EffectiveLock.NONE && enabledOutbound > 0
}

/**
 * Automations that send messages off the phone ([sendsOffDevice]: auto-forwarding, auto-replies, webhooks, relays,
 * "open" intents) are allowed only while an app lock is set up ([securityReady]: app lock on with a usable method,
 * re-checking the phone's screen lock, which can be removed outside Dak). Otherwise someone with a minute on an
 * unlocked phone could quietly forward the owner's bank SMS and OTPs to themselves.
 *
 * Enforcement points:
 * - [AutomationRunner] asks [securityReady] before every outbound action; without a lock it sends nothing and calls
 *   [disableAll] (fail closed);
 * - the Forwarding and Automations screens refuse to turn such a rule on without a lock ("Set up app lock first"), and
 *   [RuleRepository] never stores one enabled without a lock;
 * - [start] (from `DakApplication`) follows [AppLockManager.state] and disables them when the lock goes away, and runs
 *   [enforce] on app start and from the daily housekeeping (a screen lock removed while Dak was not running);
 * - the App lock screen shows which rules will be turned off before the user switches the lock off (or removes the
 *   app PIN it relied on), then calls [disableAll] first.
 *
 * Depends on [AppLockManager], never the other way round.
 */
@Singleton
class OutboundAutomationGuard @Inject constructor(
    @ApplicationContext context: Context,
    // Lazy: the runner creates the guard with every message handler; the lock manager is built on first use, off the
    // main thread.
    private val lockManager: dagger.Lazy<AppLockManager>,
    private val rules: RuleRepository,
    private val holds: ForwardingHolds,
    private val status: ForwardingStatusNotifier,
    private val audit: AuditLogRepository,
    private val alerts: OutboundSecurityNotifier,
    private val reminders: OutboundRuleReminders,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val appLock: AppLockManager get() = lockManager.get()
    @Volatile private var started = false

    /** True when an app lock is set up and usable right now (device state re-read). */
    fun securityReady(): Boolean = appLock.isLockSetUp()

    /** Enabled rules that send off the phone, in list order. */
    suspend fun enabledOutboundRules(): List<Rule> = rules.enabledRules().filter { it.sendsOffDevice() }

    /** The hold to record if outbound automations had to be turned off now (see [OutboundGuardRules.holdFor]). */
    fun currentReason(): ForwardingHold {
        val setup = appLock.currentLock()
        return OutboundGuardRules.holdFor(setup.method, setup.deviceSecure, migrated())
    }

    /**
     * Turns off every enabled automation that sends off the phone, remembering [reason] for their rows, refreshing the
     * "Forwarding active" notification, audit-logging each and (when [notify]) posting one security alert. Returns the
     * rules it turned off.
     */
    suspend fun disableAll(reason: ForwardingHold, notify: Boolean = true): List<Rule> = mutex.withLock {
        val targets = enabledOutboundRules()
        if (targets.isEmpty()) {
            reminders.setMayHaveOutbound(false)
            return@withLock emptyList()
        }
        for (rule in targets) {
            holds.hold(rule.id, reason)
            rules.setEnabled(rule.id, false)
            runCatching { audit.log("rule:${rule.name}", ACTION_DISABLED, rule.id, "no app lock: ${reason.name}") }
        }
        runCatching { status.refresh() }
        if (notify) alerts.postDisabled(targets, reason)
        targets
    }

    /**
     * Disables outbound automations when no app lock is set up; true when the lock is fine. [cheap] (app start, lock
     * changes) skips the database when no outbound automation can be on ([OutboundRuleReminders.mayHaveOutbound]).
     */
    suspend fun enforce(cheap: Boolean = false): Boolean {
        if (securityReady()) return true
        if (cheap && !reminders.mayHaveOutbound()) return false
        disableAll(currentReason())
        return false
    }

    /**
     * Starts following the app lock (idempotent; `DakApplication.onCreate`). First the one-time upgrade check
     * ([migrateOnce]); then, whenever the effective lock is (or becomes) none, [enforce].
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { migrateOnce() }.onFailure { Log.w(TAG, "upgrade check failed", it) }
            appLock.state.map { it.effective }.distinctUntilChanged().collect { effective ->
                if (effective == EffectiveLock.NONE) runCatching { enforce(cheap = true) }.onFailure { Log.w(TAG, "enforce failed", it) }
            }
        }
    }

    /**
     * Once per install (the first start of the version that requires app lock): without a lock, enabled outbound
     * rules are turned off ("app lock needed") with one notification; with one, every enabled outbound rule gets a
     * "Was this you?" reminder, so a tampered setup made before this version is still surfaced.
     */
    suspend fun migrateOnce() {
        if (migrated()) return
        if (securityReady()) {
            for (rule in enabledOutboundRules()) {
                reminders.arm(rule.id, enabledAtMillis = rule.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            }
        } else {
            disableAll(ForwardingHold.LOCK_NEEDED)
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun migrated(): Boolean = prefs.getBoolean(KEY_MIGRATED, false)

    /**
     * Starts the guard from `DakApplication.onCreate` without building it (and the app lock manager, rule repository
     * and settings it reads) on the main thread: the guard is created and started on the application scope.
     */
    @Singleton
    class Starter @Inject constructor(
        private val guard: dagger.Lazy<OutboundAutomationGuard>,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        fun start() {
            scope.launch { runCatching { guard.get().start() }.onFailure { Log.w(TAG, "could not start", it) } }
        }
    }

    companion object {
        const val ACTION_DISABLED = "automation.disabled_no_lock"
        private const val PREFS = "dak_outbound_guard"
        private const val KEY_MIGRATED = "migrated_v1"
        private const val TAG = "DakOutboundGuard"
    }
}
