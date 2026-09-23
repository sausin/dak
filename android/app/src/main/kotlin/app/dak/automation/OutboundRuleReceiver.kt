package app.dak.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dak.automations.rule.sendsOffDevice
import app.dak.index.repo.AuditLogRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Turn off" on the "Was this you?" security alert ([OutboundSecurityNotifier.postReminder]). Turning an automation
 * off is the safe direction, so it needs no unlock; the intent only carries a rule id, which must name an existing
 * rule that sends messages off the phone. Registered `exported="false"`: only our own PendingIntent reaches it.
 */
class OutboundRuleReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun rules(): RuleRepository
        fun forwardingStatus(): ForwardingStatusNotifier
        fun alerts(): OutboundSecurityNotifier
        fun audit(): AuditLogRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TURN_OFF) return
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID)?.takeIf { it.isNotBlank() } ?: return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val pending = goAsync()
        scope.launch {
            try {
                val rule = deps.rules().get(ruleId)
                if (rule == null) {
                    deps.alerts().cancelReminder(ruleId)
                } else if (rule.sendsOffDevice()) {
                    deps.rules().setEnabled(ruleId, false)
                    runCatching { deps.audit().log("user", ACTION_AUDIT, ruleId, "turned off from the security alert") }
                    runCatching { deps.forwardingStatus().refresh() }
                    deps.alerts().postTurnedOff(rule)
                }
            } catch (e: Exception) {
                // Leave the alert in place; the user can still turn the rule off in the app.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TURN_OFF = "app.dak.action.OUTBOUND_RULE_TURN_OFF"
        const val EXTRA_RULE_ID = "app.dak.extra.OUTBOUND_RULE_ID"
        private const val ACTION_AUDIT = "automation.turned_off_from_alert"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
