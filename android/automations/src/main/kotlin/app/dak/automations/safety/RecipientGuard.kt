package app.dak.automations.safety

/**
 * A snapshot of the number a forwarding/relay rule's recipient resolved to when the rule was created
 * (or last confirmed). If the contact's number has since changed, the rule must pause itself rather
 * than silently start sending to someone else — see "Relay rules" safety requirements.
 */
public data class RecipientSnapshot(
    val ruleId: String,
    val contactId: String?,
    val numberAtSnapshot: String,
)

/**
 * Pure decision: should a rule pause because its recipient's number changed underneath it?
 * [currentNumber] is `null` when the contact (or the number on it) can no longer be resolved at all,
 * which also pauses the rule.
 */
public fun shouldPause(snapshot: RecipientSnapshot, currentNumber: String?): Boolean {
    if (currentNumber == null) return true
    return normalize(currentNumber) != normalize(snapshot.numberAtSnapshot)
}

/**
 * Multi-number variant for a picked contact: pause unless the contact still exists ([currentNumbers] non-null) and
 * still lists [snapshot]'s number among its numbers. Used by auto-forwarding before every forward.
 */
public fun shouldPauseForContact(snapshot: RecipientSnapshot, currentNumbers: List<String>?): Boolean =
    currentNumbers == null || currentNumbers.all { shouldPause(snapshot, it) }

/** Loose normalization so formatting differences (spaces, dashes, a leading `+`/`00`) don't false-trigger a pause. */
private fun normalize(number: String): String {
    val digits = number.filter { it.isDigit() }
    return if (digits.length > 10) digits.takeLast(10) else digits
}
