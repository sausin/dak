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
}

/**
 * Remembers why [AutomationRunner] paused a rule (it also disables it), so the Forwarding screen can say so. Cleared
 * when the user turns the rule back on, saves it again or deletes it.
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
