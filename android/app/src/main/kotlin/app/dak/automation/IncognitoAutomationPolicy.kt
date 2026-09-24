package app.dak.automation

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.isForwardingOrRelay

/**
 * Incognito chats keep their messages off everything but the thread: an automation may not copy one elsewhere
 * (SMS forward, webhook, web relay). On-device actions and auto-replies (which carry the rule's own text) still run.
 */
object IncognitoAutomationPolicy {
    fun blocks(action: ActionSpec, incognito: Boolean): Boolean = incognito && action.isForwardingOrRelay()
}
