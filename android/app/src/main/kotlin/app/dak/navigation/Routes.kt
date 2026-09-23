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

    const val COMPOSE = "compose?to={to}&body={body}"
    const val ARG_TO = "to"
    const val ARG_BODY = "body"
    fun compose(to: String? = null, body: String? = null): String =
        "compose" + query(ARG_TO to to, ARG_BODY to body)

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
    const val BACKUP = "backup"
    const val BLOCKED = "blocked"
    const val SELF_TEST = "selftest"

    /** Time-boxed auto-forwarding rules (e.g. bank alerts to your CA for tax season). */
    const val FORWARDING = "forwarding"
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

    private fun enc(value: String): String = Uri.encode(value)

    private fun query(vararg pairs: Pair<String, String?>): String {
        val present = pairs.filter { it.second != null }
        if (present.isEmpty()) return ""
        return present.joinToString(separator = "&", prefix = "?") { (k, v) -> "$k=${enc(v!!)}" }
    }
}
