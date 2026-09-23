package app.dak.automation

import app.dak.automations.rule.Rule
import app.dak.automations.rule.RuleCodec
import app.dak.automations.rule.isExpired
import app.dak.index.repo.AutomationStore
import app.dak.index.repo.StoredRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A stored rule plus its decoded AST (null when the JSON could not be read at all). */
data class RuleEntry(val stored: StoredRule, val rule: Rule?)

/**
 * Typed access to automation rules: :core-index stores them as opaque JSON ([AutomationStore]); this decodes and
 * encodes them with :automations' [RuleCodec]. The store's id, name and enabled flag are authoritative.
 */
@Singleton
class RuleRepository @Inject constructor(
    private val store: AutomationStore,
    private val confirmations: OtpForwardConfirmations,
) {
    fun observe(): Flow<List<RuleEntry>> = store.observe().map { rows -> rows.map { RuleEntry(it, decode(it)) } }

    /** Enabled, decodable rules in list order, for evaluation. */
    suspend fun enabledRules(): List<Rule> = store.enabled().mapNotNull(::decode)

    suspend fun get(id: String): Rule? = store.get(id)?.let(::decode)

    /** Inserts or replaces [rule]; returns its id. */
    suspend fun save(rule: Rule): String =
        store.put(name = rule.name, json = RuleCodec.encode(rule), enabled = rule.enabled, id = rule.id)

    suspend fun setEnabled(id: String, enabled: Boolean) = store.setEnabled(id, enabled)

    suspend fun delete(id: String) {
        store.delete(id)
        confirmations.forget(id)
    }

    /**
     * Disables every enabled rule whose validity window ended before [nowMillis] (e.g. a forwarding rule "until
     * 31 Jul"), and returns them. Evaluated lazily: at message time and by the daily housekeeping, never by a timer.
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
