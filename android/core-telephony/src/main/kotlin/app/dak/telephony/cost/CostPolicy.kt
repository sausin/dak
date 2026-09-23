package app.dak.telephony.cost

/**
 * Pure decisions on top of [CostVerdict]s, shared by the composer (interactive) and automation (unattended) send
 * paths so both apply the same rules.
 */
object CostPolicy {

    /**
     * The verdicts an interactive send should confirm first, loudest first: those that [CostVerdict.needsConfirmation]
     * and are not in [approvedKeys] (see [approvalKey]). [CostKind.ROAMING] is only included when [warnRoaming].
     */
    fun toConfirm(
        verdicts: List<CostVerdict>,
        subId: Int,
        approvedKeys: Set<String>,
        warnRoaming: Boolean = true,
    ): List<CostVerdict> = verdicts
        .filter { it.needsConfirmation }
        .filter { warnRoaming || it.kind != CostKind.ROAMING }
        .filter { approvalKey(it.destination, subId) !in approvedKeys }
        .sortedWith(compareByDescending<CostVerdict> { it.severity }.thenBy { it.kind.ordinal })

    /**
     * Whether an unattended send (automation forward, rule-driven scheduled send) may go to [verdict]'s destination
     * without a prompt: premium-rate destinations are refused unless the user approved that number on that SIM.
     */
    fun allowUnattended(verdict: CostVerdict, subId: Int, approvedKeys: Set<String>): Boolean =
        verdict.kind != CostKind.PREMIUM_RATE || approvalKey(verdict.destination, subId) in approvedKeys

    /**
     * Stable key for a "don't ask again for this number" approval: per SIM, digits only for numbers (keeping a
     * leading `+`), upper-cased without spaces/dashes for alphanumeric ids.
     */
    fun approvalKey(destination: String, subId: Int): String {
        val trimmed = destination.trim()
        val id = if (trimmed.any { it.isLetter() }) {
            trimmed.filter { it.isLetterOrDigit() }.uppercase()
        } else {
            val digits = trimmed.filter { it.isDigit() }
            if (trimmed.startsWith("+")) "+$digits" else digits
        }
        return "$subId|$id"
    }
}
