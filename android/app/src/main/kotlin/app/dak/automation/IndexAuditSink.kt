package app.dak.automation

import app.dak.automations.audit.AuditLogEntry
import app.dak.automations.audit.AuditSink
import app.dak.index.repo.AuditLogRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AuditSink] over the index's append-only audit log. Rows use actor `rule:<name>`, action
 * `automation.<ActionType>`, the message key as target, and recipient/channel/outcome in the detail, which is what
 * the conversation screen reads to show "Forwarded to …" under a bubble.
 */
@Singleton
class IndexAuditSink @Inject constructor(private val audit: AuditLogRepository) : AuditSink {
    override suspend fun record(entry: AuditLogEntry) {
        audit.log(
            actor = "rule:${entry.ruleName}",
            action = ACTION_PREFIX + entry.actionType,
            target = entry.messageKey,
            detail = listOfNotNull(
                entry.recipient?.let { "$KEY_RECIPIENT$it" },
                entry.channel?.let { "$KEY_CHANNEL$it" },
                "$KEY_OUTCOME${entry.outcome}",
            ).joinToString(SEPARATOR),
        )
    }

    companion object {
        const val ACTION_PREFIX = "automation."
        const val KEY_RECIPIENT = "to="
        const val KEY_CHANNEL = "channel="
        const val KEY_OUTCOME = "outcome="
        const val SEPARATOR = "; "

        /** Recipient of a successful forward recorded in [detail], or null. */
        fun forwardedRecipient(detail: String?): String? {
            val parts = detail?.split(SEPARATOR) ?: return null
            if (parts.none { it == "${KEY_OUTCOME}success" }) return null
            return parts.firstOrNull { it.startsWith(KEY_RECIPIENT) }?.removePrefix(KEY_RECIPIENT)
        }
    }
}
