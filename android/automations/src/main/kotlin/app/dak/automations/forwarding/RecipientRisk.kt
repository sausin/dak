package app.dak.automations.forwarding

/** Why a forwarding recipient looks like it could be a scammer's number rather than someone the user knows. */
public enum class RecipientRisk {
    /** The contact was added or edited within [RecipientRiskHeuristic.RECENT_CONTACT_MILLIS]. */
    RECENTLY_CHANGED_CONTACT,

    /** No SMS has ever been exchanged with the number on this phone. */
    NO_MESSAGE_HISTORY,

    /** A foreign number while the SIM is Indian. */
    INTERNATIONAL_NUMBER,

    /** Not a normal mobile number: wrong length, a landline / toll-free / short code, or letters. */
    UNUSUAL_NUMBER,
}

/**
 * What the app could find out about a picked recipient. Nulls mean "could not tell" and never raise a flag on
 * their own.
 *
 * @param contactLastUpdatedMillis `ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP` of the contact.
 * @param hasMessageHistory whether any SMS (either direction) with the number exists on the phone.
 * @param homeCountryIso the sending SIM's home country (ISO 3166-1 alpha-2, any case); null is treated as India.
 */
public data class RecipientFacts(
    val number: String,
    val contactLastUpdatedMillis: Long? = null,
    val hasMessageHistory: Boolean? = null,
    val homeCountryIso: String? = null,
)

/**
 * Best-effort "is this recipient risky?" signal for auto-forwarding, shown as a strong warning (plus the biometric
 * step) when a flagged contact is picked. Scam scripts typically have the victim save the scammer's number as a new
 * contact during the call and then set up forwarding to it, so the signals are:
 *
 * - **recently added or edited contact**: Android keeps no contact creation time; the closest is
 *   `CONTACT_LAST_UPDATED_TIMESTAMP`, which changes on *any* edit (and on some account syncs). A contact touched in
 *   the last [RECENT_CONTACT_MILLIS] is flagged; a long-known contact edited yesterday is a false positive we
 *   accept, since the result is only a warning and one extra confirmation;
 * - **no message history** with the number (someone you have never texted);
 * - **number sanity** for Indian SIMs: a normal mobile is 10 digits starting 6–9 (after removing `+91`, `0091`, `91`
 *   or a trunk `0`); foreign numbers, landlines, toll-free/short codes and odd lengths are flagged. Landlines whose
 *   area code starts 6–9 (e.g. Bengaluru's 80) look exactly like mobiles and pass. For other home countries only
 *   clearly malformed numbers (letters, fewer than 7 or more than 15 digits) are flagged.
 *
 * Pure: the caller gathers [RecipientFacts] (contacts, SMS provider, SIM) and passes the clock.
 */
public object RecipientRiskHeuristic {

    /** Contacts added or edited more recently than this are flagged: 7 days. */
    public const val RECENT_CONTACT_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /** Allowed clock skew before a "last updated" time in the future is itself treated as recent. */
    private const val FUTURE_SKEW_MILLIS: Long = 24L * 60 * 60 * 1000

    public fun assess(facts: RecipientFacts, nowMillis: Long): Set<RecipientRisk> {
        val risks = LinkedHashSet<RecipientRisk>()
        facts.contactLastUpdatedMillis?.takeIf { it > 0L }?.let { updated ->
            if (nowMillis - updated < RECENT_CONTACT_MILLIS || updated > nowMillis + FUTURE_SKEW_MILLIS) {
                risks += RecipientRisk.RECENTLY_CHANGED_CONTACT
            }
        }
        if (facts.hasMessageHistory == false) risks += RecipientRisk.NO_MESSAGE_HISTORY
        numberRisk(facts.number, facts.homeCountryIso)?.let { risks += it }
        return risks
    }

    /** The number-shape flag for [number], or null when it looks like a normal mobile number. */
    public fun numberRisk(number: String, homeCountryIso: String?): RecipientRisk? {
        val trimmed = number.trim()
        if (trimmed.any { it.isLetter() }) return RecipientRisk.UNUSUAL_NUMBER
        val digits = trimmed.filter { it in '0'..'9' }
        val india = homeCountryIso == null || homeCountryIso.equals("in", ignoreCase = true)
        if (!india) return if (digits.length in MIN_DIGITS..MAX_DIGITS) null else RecipientRisk.UNUSUAL_NUMBER
        val national = when {
            trimmed.startsWith("+") -> if (digits.startsWith("91")) digits.drop(2) else return RecipientRisk.INTERNATIONAL_NUMBER
            digits.startsWith("00") -> if (digits.startsWith("0091")) digits.drop(4) else return RecipientRisk.INTERNATIONAL_NUMBER
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
        return if (national.length == 10 && national[0] in '6'..'9') null else RecipientRisk.UNUSUAL_NUMBER
    }

    private const val MIN_DIGITS = 7
    private const val MAX_DIGITS = 15
}
