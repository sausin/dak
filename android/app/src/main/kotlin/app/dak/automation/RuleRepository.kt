package app.dak.automation

import app.dak.automations.rule.Rule
import app.dak.automations.rule.RuleCodec
import app.dak.automations.rule.isExpired
import app.dak.automations.rule.sendsOffDevice
import app.dak.automations.safety.OutboundAutomations
import app.dak.index.repo.AutomationStore
import app.dak.index.repo.StoredRule
import app.dak.security.AppLockManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A stored rule plus its decoded AST (null when the JSON could not be read at all). */
data class RuleEntry(val stored: StoredRule, val rule: Rule?)

/**
 * Typed access to automation rules: :core-index stores them as opaque JSON ([AutomationStore]); this decodes and
 * encodes them with :automations' [RuleCodec]. The store's id, name and enabled flag are authoritative.
 *
 * Every write goes through here, so it is also where two anti-tampering rules for automations that send messages off
 * the phone ([sendsOffDevice]) hold whatever screen made the change:
 * - such a rule is never stored enabled while no app lock is set up ([AppLockManager.isLockSetUp]); the screens check
 *   first and explain ([OutboundAutomationGuard]), this is the backstop;
 * - turning one on arms the "Was this you?" reminder ([OutboundRuleReminders]); the user turning it off or deleting it
 *   cancels the reminder.
 */
@Singleton
class RuleRepository @Inject constructor(
    private val store: AutomationStore,
    private val confirmations: OtpForwardConfirmations,
    private val holds: ForwardingHolds,
    private val reminders: OutboundRuleReminders,
    // Lazy: built on the first write, not whenever something that reads rules is created (e.g. on the main thread).
    private val appLock: dagger.Lazy<AppLockManager>,
) {
    fun observe(): Flow<List<RuleEntry>> = store.observe().map { rows -> rows.map { RuleEntry(it, decode(it)) } }

    /** Enabled, decodable rules in list order, for evaluation. */
    suspend fun enabledRules(): List<Rule> = store.enabled().mapNotNull(::decode)

    suspend fun get(id: String): Rule? = store.get(id)?.let(::decode)

    /**
     * Inserts or replaces [rule]; returns its id. A rule that sends off the phone is stored disabled when no app lock
     * is set up (callers check [OutboundAutomationGuard.securityReady] first and tell the user).
     */
    suspend fun save(rule: Rule): String {
        val before = store.get(rule.id)?.let(::decode)
        val allowed = if (rule.enabled && rule.sendsOffDevice() && !appLock.get().isLockSetUp()) rule.copy(enabled = false) else rule
        val id = store.put(name = allowed.name, json = RuleCodec.encode(allowed), enabled = allowed.enabled, id = allowed.id)
        val saved = allowed.copy(id = id)
        when {
            OutboundAutomations.remindAfterSave(before, saved) -> reminders.arm(id, enabledAtMillis = System.currentTimeMillis())
            !saved.enabled && before?.enabled == true -> reminders.cancel(id)
        }
        return id
    }

    /**
     * Turns rule [id] on or off; returns false when it was refused (turning on a rule that sends off the phone while no
     * app lock is set up). [byUser] false marks the app pausing a rule by itself (contact removed, period ended), which
     * keeps a pending "Was this you?" reminder: whoever set the rule up, the owner should still hear about it.
     */
    suspend fun setEnabled(id: String, enabled: Boolean, byUser: Boolean = true): Boolean {
        if (!enabled) {
            store.setEnabled(id, false)
            if (byUser) reminders.cancel(id)
            return true
        }
        val stored = store.get(id)
        val rule = stored?.let(::decode)
        val outbound = rule?.sendsOffDevice() ?: true // an unreadable rule may send anything: treat it as outbound
        if (outbound && !appLock.get().isLockSetUp()) return false
        store.setEnabled(id, true)
        if (outbound && stored?.enabled != true) reminders.arm(id, enabledAtMillis = System.currentTimeMillis())
        return true
    }

    suspend fun delete(id: String) {
        store.delete(id)
        confirmations.forget(id)
        holds.clear(id)
        reminders.cancel(id)
    }

    /**
     * Disables every enabled rule whose validity window ended before [nowMillis] (e.g. a forwarding rule "until
     * 31 Jul"), and returns them. Evaluated lazily: at message time and by the daily housekeeping, never by a timer.
     * Ended rules stay saved (the user can use them again); their reminder is kept.
     */
    suspend fun disableExpired(nowMillis: Long = System.currentTimeMillis()): List<Rule> {
        val expired = enabledRules().filter { it.isExpired(nowMillis) }
        for (rule in expired) store.setEnabled(rule.id, false)
        return expired
    }

    private fun decode(stored: StoredRule): Rule? =
        runCatching { RuleCodec.decodeOne(stored.json) }.getOrNull()
            ?.copy(id = stored.id, name = stored.name, enabled = stored.enabled)
}
