package app.dak.automation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why the app paused a forwarding rule by itself. */
enum class ForwardingHold {
    /** The recipient was deleted from contacts, or its contact no longer has the number. */
    CONTACT_MISSING,
    /** Contacts access was revoked, so the recipient could not be checked. */
    CONTACTS_ACCESS,
    /** The user switched the app lock off ([OutboundAutomationGuard]). */
    LOCK_OFF,
    /** The phone's screen lock was removed, leaving no app lock ([OutboundAutomationGuard]). */
    SCREEN_LOCK_REMOVED,
    /** No app lock was set up (rules from before app lock was required, or a lock that is not usable). */
    LOCK_NEEDED,
    ;

    /** True for the holds [OutboundAutomationGuard] sets: the rule can come back once an app lock is set up. */
    val needsAppLock: Boolean get() = this == LOCK_OFF || this == SCREEN_LOCK_REMOVED || this == LOCK_NEEDED
}

/**
 * Remembers why the app turned a rule off by itself ([AutomationRunner]: recipient no longer a contact;
 * [OutboundAutomationGuard]: no app lock), so the Forwarding and Automations screens can say so. Cleared when the user
 * turns the rule back on, saves it again or deletes it.
 */
@Singleton
class ForwardingHolds @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun reason(ruleId: String): ForwardingHold? =
        prefs.getString(ruleId, null)?.let { name -> ForwardingHold.entries.firstOrNull { it.name == name } }

    fun hold(ruleId: String, reason: ForwardingHold) {
        prefs.edit().putString(ruleId, reason.name).apply()
    }

    fun clear(ruleId: String) {
        prefs.edit().remove(ruleId).apply()
    }

    private companion object {
        const val PREFS = "dak_forwarding_holds"
    }
}
