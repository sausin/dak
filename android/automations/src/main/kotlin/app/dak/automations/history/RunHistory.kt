package app.dak.automations.history

import app.dak.automations.rule.ActionSpec
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What happened to one outbound action of a rule for one message. */
public enum class RunOutcome {
    /** Handed to the SMS stack / webhook / relay (or queued for the next free sending slot). */
    SENT,
    FAILED,
    /** Deliberately not sent; [RunRecord.reason] says why ([SkipReason]). */
    SKIPPED,
    ;

    public companion object {
        public fun fromName(name: String?): RunOutcome = entries.firstOrNull { it.name == name } ?: FAILED
    }
}

/** Why an outbound action was skipped (stored as-is in the run log; the UI maps each to a sentence). */
public object SkipReason {
    /** OTP-capable or long forwarding that was not confirmed with a fingerprint / screen lock. */
    public const val NOT_CONFIRMED: String = "not_confirmed"
    /** No app lock was set up, so nothing may leave the phone automatically. */
    public const val NO_APP_LOCK: String = "no_app_lock"
    /** The recipient is no longer a phone contact. */
    public const val CONTACT_REMOVED: String = "contact_removed"
    /** Contacts access was revoked, so the recipient could not be checked. */
    public const val NO_CONTACTS_ACCESS: String = "no_contacts_access"
    /** The source message looked like a fake-credit scam. */
    public const val POSSIBLE_SCAM: String = "possible_scam"
    /** The action needs a premium feature that is not active. */
    public const val PREMIUM_LOCKED: String = "premium_locked"
}

/**
 * One row of an automation's run log, as the history screen sees it. [ruleName] is a snapshot, so runs of a deleted
 * rule stay readable; [destination] is normalised (a phone number, a webhook host) while [destinationLabel] is what
 * the user knows it as (the contact name).
 */
public data class RunRecord(
    val id: Long,
    val ruleId: String,
    val ruleName: String,
    val atMillis: Long,
    val messageKey: String?,
    val conversationId: String?,
    val sourceLabel: String?,
    val actionKind: String,
    val destinationLabel: String?,
    val destination: String?,
    val outcome: RunOutcome,
    val reason: String?,
    val textPreview: String?,
) {
    /** What to call the destination: the contact name when known, else the normalised destination. */
    val destinationText: String get() = destinationLabel?.takeIf { it.isNotBlank() } ?: destination.orEmpty()
}

/** Totals for a history header: "43 messages forwarded to Sharma CA between 1 Jul and 31 Jul; 2 failed; 5 skipped". */
public data class RunSummary(
    val sent: Int,
    val failed: Int,
    val skipped: Int,
    /** Distinct destinations of sent runs, most used first. */
    val destinations: List<String>,
    val firstAtMillis: Long?,
    val lastAtMillis: Long?,
) {
    val total: Int get() = sent + failed + skipped
}

/** One calendar day of runs, newest first. */
public data class RunDay(val date: LocalDate, val runs: List<RunRecord>)

/** Pure helpers for the automation run log (history screen, "Was this you?" counts). */
public object RunHistory {

    /** Longest stored preview of the text that was (or would have been) sent. */
    public const val PREVIEW_MAX_CHARS: Int = 160

    public fun summarize(records: List<RunRecord>): RunSummary {
        val sent = records.filter { it.outcome == RunOutcome.SENT }
        return RunSummary(
            sent = sent.size,
            failed = records.count { it.outcome == RunOutcome.FAILED },
            skipped = records.count { it.outcome == RunOutcome.SKIPPED },
            destinations = sent.groupingBy { it.destinationText }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
                .filter { it.isNotBlank() },
            firstAtMillis = records.minOfOrNull { it.atMillis },
            lastAtMillis = records.maxOfOrNull { it.atMillis },
        )
    }

    /** Groups [records] by local day in [zone]: days newest first, runs within a day newest first. */
    public fun groupByDay(records: List<RunRecord>, zone: ZoneId): List<RunDay> =
        records.sortedWith(compareByDescending<RunRecord> { it.atMillis }.thenByDescending { it.id })
            .groupBy { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() }
            .map { (date, runs) -> RunDay(date, runs) }
            .sortedByDescending { it.date }

    /**
     * A one-line preview of [text]: whitespace collapsed, at most [max] characters with an ellipsis. When [otpCode]
     * is given it is masked, so the history does not become a list of working codes.
     */
    public fun preview(text: String, otpCode: String? = null, max: Int = PREVIEW_MAX_CHARS): String {
        var line = text.replace(Regex("\\s+"), " ").trim()
        if (!otpCode.isNullOrBlank() && otpCode.length >= MIN_OTP_TO_MASK) line = line.replace(otpCode, "•".repeat(otpCode.length))
        return if (line.length <= max) line else line.take((max - 1).coerceAtLeast(0)).trimEnd() + "…"
    }

    /**
     * Where [action] sends to, normalised for the log: the phone number for SMS, the host for a webhook, the pairing
     * for the web client, `channel:recipient` for relays, "sender" for an auto-reply and the scheme for an "open"
     * intent. Null for actions that do not send anything anywhere.
     */
    public fun destinationOf(action: ActionSpec, replyTo: String? = null): String? = when (action) {
        is ActionSpec.ForwardSms -> action.to.trim()
        is ActionSpec.ScheduleReply -> replyTo?.trim()
        is ActionSpec.Webhook -> runCatching { URI(action.url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: action.url
        is ActionSpec.RelayToWebClient -> action.pairingId?.let { "web:$it" } ?: "web"
        is ActionSpec.RelayRule -> "${action.channel.name.lowercase()}:${action.recipient.trim()}"
        is ActionSpec.LaunchIntent -> runCatching { URI(action.uri).scheme }.getOrNull() ?: "intent"
        is ActionSpec.Unknown -> action.type
        else -> null
    }

    /** Stable kind name for the log ("ForwardSms", "Webhook", …; the unknown type's own name otherwise). */
    public fun kindOf(action: ActionSpec): String = when (action) {
        is ActionSpec.Unknown -> "Unknown:${action.type}"
        else -> action::class.simpleName ?: "Unknown"
    }

    /** Codes shorter than this are not masked (they would blank out ordinary digits in the text). */
    private const val MIN_OTP_TO_MASK = 4
}
