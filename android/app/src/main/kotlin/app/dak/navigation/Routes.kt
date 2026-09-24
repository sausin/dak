package app.dak.navigation

import android.net.Uri

/**
 * The pinned navigation contract. String routes only. Builder functions URL-encode every argument,
 * and omit optional arguments that are null so the NavHost default (null) applies.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val INBOX = "inbox"

    const val CONVERSATION = "conversation/{conversationId}?highlight={highlight}"
    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_HIGHLIGHT = "highlight"
    fun conversation(conversationId: String, highlight: String? = null): String =
        "conversation/${enc(conversationId)}" + query(ARG_HIGHLIGHT to highlight)

    const val COMPOSE = "compose?to={to}&body={body}&sub={sub}"
    const val ARG_TO = "to"
    const val ARG_BODY = "body"
    /** Preferred SIM subscription id (decimal); the composer uses it only if that SIM is still active. */
    const val ARG_SUB = "sub"
    fun compose(to: String? = null, body: String? = null, subId: Int? = null): String =
        "compose" + query(ARG_TO to to, ARG_BODY to body, ARG_SUB to subId?.takeIf { it >= 0 }?.toString())

    const val SEARCH = "search?q={q}"
    const val ARG_Q = "q"
    fun search(q: String? = null): String = "search" + query(ARG_Q to q)

    const val SETTINGS = "settings?focus={focus}"
    const val ARG_FOCUS = "focus"
    fun settings(focusKey: String? = null): String = "settings" + query(ARG_FOCUS to focusKey)

    const val SETTINGS_GROUP = "settings/group/{group}?focus={focus}"
    const val ARG_GROUP = "group"
    fun settingsGroup(group: String, focusKey: String? = null): String =
        "settings/group/${enc(group)}" + query(ARG_FOCUS to focusKey)

    const val BIN = "bin"
    const val PASSBOOK = "passbook"

    const val PASSBOOK_ACCOUNT = "passbook/account/{accountId}"
    const val ARG_ACCOUNT_ID = "accountId"
    fun passbookAccount(accountId: String): String = "passbook/account/${enc(accountId)}"

    const val AUTOMATIONS = "automations"

    /**
     * The Automations screen opened on one scheduled send (from its heads-up notification): [ARG_SCHEDULED_ID] is shown
     * first, and with [ARG_PICK_TIME] = "1" the date and time pickers open straight away ("Pick time").
     */
    const val SCHEDULED_SENDS = "scheduledsends?scheduledId={scheduledId}&pickTime={pickTime}"
    const val ARG_SCHEDULED_ID = "scheduledId"
    const val ARG_PICK_TIME = "pickTime"
    fun scheduledSends(scheduledId: Long? = null, pickTime: Boolean = false): String =
        "scheduledsends" + query(ARG_SCHEDULED_ID to scheduledId?.toString(), ARG_PICK_TIME to (if (pickTime) "1" else null))
    const val BACKUP = "backup"
    const val BLOCKED = "blocked"
    const val SELF_TEST = "selftest"

    /** Time-boxed auto-forwarding rules (e.g. bank alerts to your CA for tax season). */
    const val FORWARDING = "forwarding"
    /**
     * What automations sent (the run log): one rule's history ([ARG_RULE_ID]), or every rule's, including deleted
     * ones, when it is null.
     */
    const val AUTOMATION_HISTORY = "automationhistory?ruleId={ruleId}"
    const val ARG_RULE_ID = "ruleId"
    fun automationHistory(ruleId: String? = null): String = "automationhistory" + query(ARG_RULE_ID to ruleId)
    /** Birthday wishes picked up from contacts. */
    const val BIRTHDAYS = "birthdays"
    /** Fraud reporting: verified helplines, 1909 / cybercrime flows. Optional message to report. */
    const val FRAUD_HELP = "fraud?message={message}"
    const val ARG_MESSAGE = "message"
    fun fraudHelp(messageKey: String? = null): String = "fraud" + query(ARG_MESSAGE to messageKey)
    /** Sender folding: fold many channels (numbers/headers) into one conversation, or unfold. */
    const val SENDER_GROUPS = "sendergroups"
    /** Per-channel notification management. */
    const val NOTIFICATION_CHANNELS = "notificationchannels"
    /** App lock and privacy: unlock method, app PIN, auto-lock, Recents, sensitive screens. */
    const val APP_LOCK = "applock"
    /** Broadcast lists: one message to several people as separate SMS (replies come back 1:1). */
    const val BROADCASTS = "broadcasts"
    /** One broadcast list: its sent broadcasts (ticks, replies) and the composer. */
    const val BROADCAST = "broadcast/{id}"
    const val ARG_BROADCAST_ID = "id"
    fun broadcast(listId: String): String = "broadcast/${enc(listId)}"

    private fun enc(value: String): String = Uri.encode(value)

    private fun query(vararg pairs: Pair<String, String?>): String {
        val present = pairs.filter { it.second != null }
        if (present.isEmpty()) return ""
        return present.joinToString(separator = "&", prefix = "?") { (k, v) -> "$k=${enc(v!!)}" }
    }
}
