package app.dak.automations.audit

/**
 * One row of the automation audit log: every relayed/forwarded message is logged with rule, recipient
 * and channel (see "Relay rules" safety requirements), and shown as "forwarded to Y" in the source thread.
 */
public data class AuditLogEntry(
    val ruleId: String,
    val ruleName: String,
    /** The [app.dak.automations.rule.ActionSpec] subtype's simple name, e.g. "ForwardSms". */
    val actionType: String,
    val recipient: String?,
    val channel: String?,
    val messageKey: String,
    val atMillis: Long,
    val outcome: String,
)

/** Persists [AuditLogEntry] rows. `:app` backs this with a Room table; tests use an in-memory fake. */
public interface AuditSink {
    public suspend fun record(entry: AuditLogEntry)
}
