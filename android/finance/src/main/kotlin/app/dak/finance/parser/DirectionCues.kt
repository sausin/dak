package app.dak.finance.parser

import app.dak.core.model.TransactionDirection

/**
 * A word in an SMS that says money moved, and which way.
 *
 * [strong] = a finite verb of completed movement ("debited", "credited", "spent", "received", "reversed"); weak =
 * a noun or phrase that only names a transaction ("txn of", "payment of", "purchase", "Dr.", "Cr.", "deposit of").
 * [refund] = a completed refund / reversal, which is always the user's credit whatever else the message says.
 * [transfer] = sent / paid / transferred / transfer, whose direction depends on where the money went ("to your").
 */
internal data class Cue(
    val range: IntRange,
    val direction: TransactionDirection,
    val strong: Boolean,
    val refund: Boolean = false,
    val transfer: Boolean = false,
)

/**
 * Finds the [Cue]s of a body and the phrases that make a message something other than a completed transaction:
 * OTPs, failures, requests, mandate/autopay set-ups, statements and reminders. Vocabulary only (no bank or brand
 * names); every regex is linear.
 */
internal object DirectionCues {

    private val DEBIT = TransactionDirection.DEBIT
    private val CREDIT = TransactionDirection.CREDIT

    private data class CueRule(val regex: Regex, val direction: TransactionDirection, val strong: Boolean, val refund: Boolean = false, val transfer: Boolean = false)

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val rules = listOf(
        CueRule(rx("""\b(?:debited|deducted|withdrawn|spent)\b"""), DEBIT, strong = true),
        CueRule(rx("""\bcharged\b"""), DEBIT, strong = true),
        CueRule(rx("""\bused\s+(?:for|at|to)\b"""), DEBIT, strong = true),
        CueRule(rx("""\b(?:was|has\s+been|is|been)\s+authori[sz]ed\b"""), DEBIT, strong = true),
        CueRule(rx("""\b(?:sent|paid|transferred|trfd?)\b"""), DEBIT, strong = true, transfer = true),
        CueRule(rx("""\b(?:refunded|reversed)\b|\bcredited\s+back\b"""), CREDIT, strong = true, refund = true),
        CueRule(rx("""\b(?:credited|deposited|received|disbursed|loaded)\b"""), CREDIT, strong = true),
        CueRule(
            rx("""\bmoney\s+added\b|\badded\s+(?:to|in|into)\s+(?:your\s+)?(?:[a-z]+\s+){0,2}?(?:wallet|balance|account|a/c|card)\b"""),
            CREDIT,
            strong = true,
        ),
        CueRule(rx("""\b(?:txn|transaction)\s+(?:of\b|(?=(?:rs|inr)\b|₹))|\bpurchase\b|\bpayment\s+of\b|\bwithdrawal\b|\ba\s+charge\s+of\b"""), DEBIT, strong = false),
        CueRule(rx("""\bauto[- ]?debit\b|(?<![-\w])debit\b(?!\s*(?:card|cum))|\bdr\b|\bfor\s+using\b|\b(?:imps|neft|rtgs)\s+of\b"""), DEBIT, strong = false),
        CueRule(rx("""\btransfer\b"""), DEBIT, strong = false, transfer = true),
        CueRule(rx("""\bdeposit\s+of\b|\bcash\s+deposit\b|\bcredit\s+of\b|\bcr\b"""), CREDIT, strong = false),
        CueRule(rx("""\brefund\b|\breversal\b"""), CREDIT, strong = false, refund = true),
    )

    /** Just before a cue, these make it negated or conditional ("not credited", "if debited", "is yet to be"). */
    private val negations = setOf("not", "never", "if", "unless", "yet", "once", "until", "when")

    /** Right before a cue (or before its "be"), these make it future or hypothetical ("will be debited", "can transfer"). */
    private val modals = setOf(
        "will", "shall", "would", "may", "might", "can", "could", "cannot", "can't", "to", "be", "scheduled", "upcoming",
        "pre",
    )

    /** Every cue in [body] that states a movement as done, in order of appearance. */
    fun find(body: String): List<Cue> {
        val out = ArrayList<Cue>()
        val taken = java.util.BitSet(body.length)
        for (rule in rules) {
            for (m in rule.regex.findAll(body)) {
                val r = m.range
                if (taken.nextSetBit(r.first).let { it != -1 && it <= r.last }) continue
                taken.set(r.first, r.last + 1)
                if (!isDone(body, r.first)) continue
                out += Cue(r, rule.direction, rule.strong, rule.refund, rule.transfer)
            }
        }
        return out.sortedBy { it.range.first }
    }

    /** Words that do not change whether a cue is done ("will be auto-debited", "has been successfully credited"). */
    private val transparent = setOf("auto", "successfully", "already", "also", "duly", "hereby", "fully", "partly")

    private fun isDone(body: String, start: Int): Boolean {
        val before = SmsWords.before(body, start, 6).filter { it !in transparent }.take(3)
        if (before.any { it in negations }) return false
        // "will be credited", "to be debited", "can transfer"; but "has been credited".
        val first = before.firstOrNull() ?: return true
        if (first in modals) return false
        return !(first == "been" && before.getOrNull(1) in modals)
    }

    /**
     * The direction a transfer verb at [cue] gives the user: money sent *to you* / *to your* account is a credit
     * ("Rs 5,000 transferred to your A/c", "Kiran has sent you Rs 300"), unless it is going to one of the user's cards
     * (paying a card is money the user sends) or the clause names a "from your" source first.
     */
    fun transferDirection(body: String, cue: Cue): TransactionDirection {
        val words = SmsWords.after(body, cue.range.last + 1, 10)
        for ((i, w) in words.withIndex()) {
            when (w) {
                "from" -> return DEBIT
                "you" -> if (i == 0 || words[i - 1] in toWords) return CREDIT
                "your", "ur" -> if (i > 0 && words[i - 1] in toWords) {
                    val target = words.subList(i + 1, minOf(words.size, i + 5))
                    return if (target.any { it == "card" }) DEBIT else CREDIT
                }
            }
        }
        return DEBIT
    }

    private val toWords = setOf("to", "into", "in")

    // --- gates: messages that are not a completed transaction ---

    private val otpWord = rx("""\botp\b|one[- ]time\s+password|verification\s+code|security\s+code|\bpasscode\b""")

    /** An OTP code: "Use 482913 to authorise", "482913 is your ...". */
    private val otpCode = rx("""\buse\s+\d{4,8}\s+(?:to|as|for)\b|\b\d{4,8}\s+is\s+(?:your|the)\b""")

    /** Safety footers that mention OTPs in genuine transaction alerts ("Never share your OTP", "Bank never asks for OTP"). */
    private val otpWarning = rx("""share|disclos|reveal|\bask""")

    /** Whether [body] is an OTP / verification message (a mention inside a safety warning does not count). */
    fun isOtp(body: String): Boolean {
        if (otpCode.containsMatchIn(body)) return true
        for (m in otpWord.findAll(body)) {
            val window = body.substring(maxOf(0, m.range.first - 40), m.range.first)
            if (!otpWarning.containsMatchIn(window)) return true
        }
        return false
    }

    /** The movement failed, was declined or cancelled. Only a completed refund / reversal / credit makes it a transaction. */
    val failure = rx(
        """\b(?:failed|failure|declined|unsuccessful|rejected|cancell?ed|bounced|dishonou?red|timed\s*out)\b|""" +
            """\bnot\s+(?:been\s+)?(?:successful|processed|completed)\b|\b(?:could|can)\s*(?:not|n't)\s+be\b|\bcannot\s+be\b|""" +
            """\breturned\s+unpaid\b|\binsufficient\s+(?:balance|funds|credit\s+limit|limit)\b""",
    )

    /** Money asked for, not moved: UPI collect requests, payment requests. */
    val request = rx(
        """\b(?:has|have|is|are)\s+request(?:ed|ing)\b|\b(?:payment|money|collect|pay)\s+request\b|""" +
            """\brequest(?:ed|s)?\s+(?:rs\.?|inr|₹|money|payment|for\s+(?:rs\.?|inr|₹))""",
    )

    /** A statement or mini-statement: a summary, not a movement. */
    val statement = rx("""\be-?statement\b|\bmini[- ]?statement\b|\bstatement\b[^.\n]{0,60}?\b(?:generated|ready|is\s+available)\b""")

    /** A reminder that money is due (only counts when no completed movement is stated). */
    val due = rx(
        """due\s+on|minimum\s+(?:amount\s+|amt\s+)?due|payment\s+due|total\s+(?:amount|amt)\s+due|\bamt\s+due|\bmin\s+due|""" +
            """\bbill\s+(?:of|amount)|kindly\s+pay|pay\s+(?:your|the)\s+(?:bill|minimum)|\b(?:is|are)\s+due\b|""" +
            """\bdue\s+(?:by|for|date|tomorrow|today|in)\b""",
    )

    /** Something set up or pending rather than done (only counts when no completed movement is stated). */
    val pendingOrSetup = rx(
        """\b(?:will|shall|scheduled|upcoming|pre-?debit|initiated|pending|processing|in\s+process|under\s+process|""" +
            """registered|registration|created|set\s?up|activated)\b""",
    )
}
