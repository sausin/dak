package app.dak.finance.parser

import app.dak.finance.money.MoneyOccurrence

/** What an amount in a transaction SMS is, from the words right next to it. */
internal enum class AmountRole {
    /** Nothing says otherwise: a candidate for the transaction amount. */
    NONE,

    /** "Avl Bal Rs 500", "Balance: Rs 500", "Rs 500 is your available balance". */
    BALANCE,

    /** "Avl Lmt Rs 45,000", "credit limit", "OD limit". */
    LIMIT,

    /** "Total amt due", "Outstanding Rs 4,20,000", "Min due". */
    DUE,

    /** "cashback of Rs 25", "reward points worth Rs 50". */
    CASHBACK,

    /** "charges of Rs 5.90", "markup of Rs 57.50", "GST Rs 18". */
    FEE,

    /** The same amount in another currency: "Rs 10,850 (AED 120.50)", "INR equivalent approx Rs 3,520". */
    EQUIV,
}

/** An amount found in a body together with its [role]. */
internal data class RoledAmount(val occurrence: MoneyOccurrence, val role: AmountRole, val beforeWords: List<String>)

/**
 * Gives every amount in a message a role, so the transaction amount is never a balance, a limit, a due amount, a fee,
 * a cashback or a converted equivalent. Word-based ([SmsWords]), so it works for any order the bank writes things in
 * ("Avl Bal Rs 1,000. Rs 500 debited" as well as "Rs 500 debited. Avl Bal Rs 1,000").
 */
internal object AmountRoles {

    private val balanceWord = Regex("""(?:av[a-z]*)?bal(?:ance|ances)?|avlbal""")
    private val limitWords = setOf("limit", "lmt", "limits")
    private val dueWords = setOf("due", "dues", "outstanding", "o/s")
    private val cashbackWords = setOf("cashback", "cash-back", "reward", "rewards", "points", "worth")
    private val feeWords = setOf("fee", "fees", "charges", "gst", "markup", "surcharge", "tax", "commission")
    private val equivWords = setOf("equivalent", "equiv", "approx", "approximately", "converted")

    /** Words that start the transaction's own phrase: scanning back from an amount stops at them. */
    private val stopWords = setOf(
        "debited", "credited", "spent", "paid", "sent", "received", "withdrawn", "deposited", "transferred", "debit",
        "txn", "transaction", "payment", "purchase", "refund", "reversal", "deducted", "charged", "withdrawal", "deposit",
        "reversed", "refunded", "added", "loaded", "used",
    )
    private val fillers = setOf("is", "your", "the", "as", "now", "of", "a", "an")

    fun classify(body: String, occurrences: List<MoneyOccurrence>): List<RoledAmount> =
        occurrences.mapIndexed { i, occ ->
            val prev = occurrences.getOrNull(i - 1)
            val floor = prev?.range?.last?.plus(1) ?: 0
            val before = SmsWords.before(body, occ.range.first, 6, floor)
            RoledAmount(occ, roleOf(body, occ, prev, before, occurrences.getOrNull(i + 1)), before)
        }

    private fun roleOf(body: String, occ: MoneyOccurrence, prev: MoneyOccurrence?, before: List<String>, next: MoneyOccurrence?): AmountRole {
        // "Rs.10,850.00 (AED 120.50)": an amount in brackets right after another is its equivalent.
        if (prev != null && body.substring(prev.range.last + 1, occ.range.first).trim() == "(") return AmountRole.EQUIV
        for (w in before) {
            wordRole(w)?.let { return it }
            if (w in stopWords) break
        }
        // "Rs 45,000 available limit", "Rs 50 cashback credited".
        val ceil = next?.range?.first ?: body.length
        val after = SmsWords.after(body, occ.range.last + 1, 4, ceil).filter { it !in fillers }
        val first = after.firstOrNull() ?: return AmountRole.NONE
        if (first in stopWords) return AmountRole.NONE
        wordRole(first)?.let { return if (it == AmountRole.EQUIV || it == AmountRole.FEE) AmountRole.NONE else it }
        if (first == "avl" || first == "available" || first == "avail" || first == "avbl" || first == "total") {
            return after.getOrNull(1)?.let { w -> wordRole(w)?.takeIf { it == AmountRole.BALANCE || it == AmountRole.LIMIT } } ?: AmountRole.NONE
        }
        return AmountRole.NONE
    }

    private fun wordRole(w: String): AmountRole? = when {
        balanceWord.matches(w) -> AmountRole.BALANCE
        w in limitWords -> AmountRole.LIMIT
        w in dueWords -> AmountRole.DUE
        w in cashbackWords -> AmountRole.CASHBACK
        w in feeWords -> AmountRole.FEE
        w in equivWords -> AmountRole.EQUIV
        else -> null
    }
}
