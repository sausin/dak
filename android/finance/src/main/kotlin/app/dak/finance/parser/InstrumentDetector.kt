package app.dak.finance.parser

import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection

/**
 * What [InstrumentDetector] found: the instrument, the number of that instrument as far as the SMS shows it, and
 * (for a debit card or loan) the bank account the SMS says the money moved from.
 */
internal data class DetectedInstrument(
    val instrument: InstrumentType,
    val maskedNumber: String?,
    val linkedMaskedNumber: String? = null,
    /** The other party's account ("credited to beneficiary A/c XX5632"): never one of the user's instruments. */
    val counterpartyMaskedNumber: String? = null,
    /**
     * Set when the numbers themselves settle the direction: a payment *to* the user's own card ("Rs 5,000 paid to
     * Credit Card XX9876") is a credit to that card.
     */
    val directionOverride: TransactionDirection? = null,
)

/** Which side of the money movement a number is on, from the words next to it. */
internal enum class Side {
    /** The money left it: "debited from A/c", "from your A/c", "by a/c XX", "A/c XX1234 debited". */
    SOURCE,

    /** The money arrived in it: "credited to A/c", "to A/c", "in your A/c", "towards Card", "A/c XX5678 credited". */
    DEST,

    /** The instrument used: "spent on Card", "using Debit Card", "via card". */
    MEANS,
    NONE,
}

internal enum class RefKind { DEBIT_CARD, CREDIT_CARD, PREPAID_CARD, CARD, LOAN, ACCOUNT, BARE }

/**
 * A masked account/card number in a body, the kind of the words right before it, and its role:
 * [counterparty] = labelled as the other party's (beneficiary / payee / recipient / remitter / sender),
 * [yours] = labelled as the user's ("your", "ur"), [side] = which way the money moved through it.
 */
internal data class NumberRef(
    val kind: RefKind,
    val masked: String,
    val range: IntRange,
    val counterparty: Boolean = false,
    val yours: Boolean = false,
    val side: Side = Side.NONE,
) {
    val isCard: Boolean get() = kind in CARD_KINDS

    /**
     * Whether this number is the user's own in a message whose direction (for the user) is [direction].
     *
     * The heuristic, in order: a number labelled as the other party's is never the user's; one labelled "your" always
     * is; a card or loan number is the user's (banks do not name other people's cards or loans); otherwise an
     * account number on the far side of the movement belongs to the other party — the destination of a debit
     * ("debited from A/c XX1234 **to A/c XX5632**", "A/c XX5678 credited") or the source of a credit
     * ("credited to A/c XX4455 **by a/c XX9911**") — and any other account number is the user's.
     */
    fun isOwn(direction: TransactionDirection): Boolean = when {
        counterparty -> false
        yours -> true
        isCard || kind == RefKind.LOAN -> true
        direction == TransactionDirection.DEBIT -> side != Side.DEST
        else -> side != Side.SOURCE
    }

    companion object {
        val CARD_KINDS = setOf(RefKind.DEBIT_CARD, RefKind.CREDIT_CARD, RefKind.PREPAID_CARD, RefKind.CARD)
    }
}

/**
 * Finds the masked numbers in an SMS, decides whose each one is, and which payment instrument the message is about:
 * from the words right before each number ("Debit Card XX1234", "A/c XX5678", "Loan A/c XX9012") and then from
 * body-level wording. Deterministic and offline; every regex is linear (no nested quantifiers) because the sender
 * controls the body.
 *
 * Precedence among the user's own numbers: a typed card number (debit/credit/prepaid/forex/travel/gift/...) > a loan
 * number or loan/EMI wording with an account > an account number > body wording (prepaid, credit card, debit card,
 * loan, card brands, wallet, UPI, account, bare "card"). A bare "Card XX1234" is a debit card only when the SMS also
 * names the account it was debited from or states an available *balance*; it is a credit card when it states a
 * limit or names a card issuer, and otherwise stays a credit card, as before, so existing account ids do not move
 * (the user can change the type).
 */
internal object InstrumentDetector {

    /**
     * A masked number after a card / loan / account keyword: optional leading digits the bank shows ("4375XXXX1234",
     * "2XXXXX6789": dropped, only the trailing visible digits identify the number), a mask of X / * / dots / bullets
     * (one character is enough after a keyword: "A/c X5073") and at least 3 digits; "ending [with|in] 1234"; or,
     * right after the keyword, exactly four unmasked digits that are not part of a longer number, date or time
     * ("Card 1234 At SHOP", "A/c 1212 debited").
     */
    private const val NUMBER =
        """(?:\d{0,6}([x*.•]+\s*\d{3,})|ending\s+(?:with\s+|in\s+)?(\d{3,})|(\d{4})(?![\d,]|[-/.:]\d))"""

    /** What may sit between a keyword and the number: ". ", ": ", "#", "no.", "No: ", "number". */
    private const val SEP = """\.?\s*(?:[:#]\s*)?(?:(?:no|number|num)\b\.?\s*(?:[:#]\s*)?)?"""

    private val cardRef = Regex(
        """(?:\b(debit|credit|prepaid|forex|travel|multi[- ]?currency|gift|atm|wise|revolut)\s+)?\bcard$SEP$NUMBER""",
        RegexOption.IGNORE_CASE,
    )
    private val abbreviatedCardRef = Regex("""\b(DC|CC)$SEP$NUMBER""", RegexOption.IGNORE_CASE)
    private val loanRef = Regex(
        """\bloan(?:\s*(?:a\s?/\s?c|account|acct|ac)\b)?$SEP(?:$NUMBER|(\d{6,}))""",
        RegexOption.IGNORE_CASE,
    )
    private val accountRef = Regex("""(?:\ba\s?/\s?c|\b(?:account|acct|acc|ac)\b)$SEP$NUMBER""", RegexOption.IGNORE_CASE)
    private val bareRefs = listOf(
        Regex("""\bending\s+(?:with\s+|in\s+)?(\d{4,})""", RegexOption.IGNORE_CASE),
        Regex("""(?<![a-z0-9])([x*]{2,}\d{4,})\b""", RegexOption.IGNORE_CASE),
    )

    private val prepaidPhrase = Regex(
        """\b(?:prepaid|forex|travel|multi[- ]?currency|gift)\s+(?:\w+\s+)?card\b|\bwise\s+(?:card|account|balance)\b|\brevolut\b""",
        RegexOption.IGNORE_CASE,
    )
    private val creditCardPhrase = Regex("""\bcredit\s+card\b""", RegexOption.IGNORE_CASE)
    private val debitCardPhrase = Regex("""\bdebit\s+card\b|\batm(?:\s*cum\s*debit)?\s+card\b""", RegexOption.IGNORE_CASE)
    private val loanPhrase = Regex("""\bloan\s*(?:a\s?/\s?c|account|acct)\b""", RegexOption.IGNORE_CASE)
    private val loanWord = Regex("""\bloan\b""", RegexOption.IGNORE_CASE)
    private val emiWord = Regex("""\bemi\b|\binstal+ment\b|\brepayment\b""", RegexOption.IGNORE_CASE)

    /** Card products / issuers that only issue credit cards. */
    private val creditCardBrand = Regex(
        """\b(?:sapphire|amex|american express|apple card|discover card|capital one|quicksilver|sbi\s*card|onecard)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val creditCardSenders = listOf("SBICRD", "SBICARD", "AMEX", "ONECRD")

    private val walletExplicit = Regex(
        """\bwallet\b|\bamazon\s*pay\s*balance\b|\bairtel\s*money\b|\bola\s*money\b|\bjio\s*money\b""",
        RegexOption.IGNORE_CASE,
    )
    private val walletBrand = Regex("""\bmobikwik\b|\bfreecharge\b""", RegexOption.IGNORE_CASE)
    private val upiWord = Regex("""\bupi\b|\bvpa\b""", RegexOption.IGNORE_CASE)

    /** Common UPI handles; anchored at `@`, so linear. */
    private val vpaHandle = Regex(
        """@(?:ok(?:axis|hdfcbank|icici|sbi)|ybl|ibl|axl|paytm|upi|apl|icici|sbi|hdfcbank|axisbank|kotak|yesbank|pty?es|ptaxis|pthdfc|ptsbi|fbl|waaxis|wahdfcbank|jupiteraxis)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val accountWord = Regex("""\ba\s?/\s?c\b|\baccount\b|\bacct\b""", RegexOption.IGNORE_CASE)
    private val cardWord = Regex("""\bcard\b""", RegexOption.IGNORE_CASE)

    private val limitContext = Regex("""\b(?:av[ai]{0,2}l\.?|available|credit|cr\.?)\s*(?:lmt|limit)\b""", RegexOption.IGNORE_CASE)
    private val balanceContext = Regex("""\bav[ai]{0,2}l\.?\s*bal|\bavailable\s+bal""", RegexOption.IGNORE_CASE)
    private val fromAccountContext = Regex(
        """\b(?:from|in|to)\s+(?:your\s+)?(?:(?:bank|savings|current)\s+)?(?:a\s?/\s?c|account|acct)\b""",
        RegexOption.IGNORE_CASE,
    )

    // --- words that give a number its role (see NumberRef) ---
    private val counterpartyWords = setOf(
        "beneficiary", "beneficiary's", "benef", "bene", "payee", "payee's", "recipient", "recipient's", "receiver",
        "receiver's", "remitter", "remitter's", "sender", "sender's",
    )
    /** The user's own: "your A/c", "own A/c", "Debit Card XX9191 linked to A/c XX2323". */
    private val yoursWords = setOf("your", "ur", "yr", "own", "linked")
    private val sourcePreps = setOf("from", "by")
    private val destPreps = setOf("to", "into", "towards", "toward", "in")
    private val meansWords = setOf("using", "via", "with", "on", "through", "thru")
    internal val debitWords = setOf("debited", "deducted", "withdrawn", "dr", "debit", "spent", "charged")
    internal val creditWords = setOf("credited", "deposited", "cr", "credit", "received", "added", "loaded", "refunded", "reversed")
    private val auxiliaries = setOf("is", "has", "have", "been", "was", "were", "got", "are", "successfully", "hereby", "also")

    /**
     * Every masked number in [body] with the kind of the words right before it and its role; typed refs win overlaps.
     * Sorted by position.
     */
    fun findRefs(body: String): List<NumberRef> {
        val out = ArrayList<NumberRef>()
        val taken = java.util.BitSet(body.length)
        fun add(kind: RefKind, match: MatchResult, masked: String?) {
            if (masked == null) return
            val r = match.range
            if (taken.nextSetBit(r.first).let { it != -1 && it <= r.last }) return
            taken.set(r.first, r.last + 1)
            out += NumberRef(kind, masked, r)
        }
        for (m in cardRef.findAll(body)) add(cardKind(m.groupValues[1]), m, numberOf(m, 2))
        for (m in abbreviatedCardRef.findAll(body)) {
            val kind = if (m.groupValues[1].equals("DC", ignoreCase = true)) RefKind.DEBIT_CARD else RefKind.CREDIT_CARD
            add(kind, m, numberOf(m, 2))
        }
        for (m in loanRef.findAll(body)) {
            val unmasked = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { "XX" + it.takeLast(4) }
            add(RefKind.LOAN, m, numberOf(m, 1) ?: unmasked)
        }
        for (m in accountRef.findAll(body)) add(RefKind.ACCOUNT, m, numberOf(m, 1))
        for (pattern in bareRefs) for (m in pattern.findAll(body)) add(RefKind.BARE, m, normalise(m.groupValues[1]))
        val sorted = out.sortedBy { it.range.first }
        return sorted.mapIndexed { i, ref ->
            val floor = if (i == 0) 0 else sorted[i - 1].range.last + 1
            val ceil = if (i == sorted.lastIndex) body.length else sorted[i + 1].range.first
            withRole(body, ref, floor, ceil)
        }
    }

    /** Reads [ref]'s role from the words before it (back to [floor]) and right after it (up to [ceil]). */
    private fun withRole(body: String, ref: NumberRef, floor: Int, ceil: Int): NumberRef {
        var counterparty = false
        var yours = false
        var side = Side.NONE
        val before = SmsWords.before(body, ref.range.first, 7, floor)
        for ((i, w) in before.withIndex()) {
            when (w) {
                in counterpartyWords -> counterparty = true
                in yoursWords -> yours = true
                "and" -> break
                in sourcePreps -> side = Side.SOURCE
                // "debited to your account": the verb, not the preposition, says which side the number is on.
                in destPreps -> side = if (before.getOrNull(i + 1) in debitWords) Side.SOURCE else Side.DEST
                in meansWords -> side = Side.MEANS
                in debitWords -> side = Side.SOURCE
                in creditWords -> side = Side.DEST
            }
            if (side != Side.NONE) {
                // "linked to A/c XX2323": an ownership word right before the preposition still counts.
                if (before.getOrNull(i + 1) in yoursWords) yours = true
                break
            }
        }
        val after = SmsWords.after(body, ref.range.last + 1, 5, ceil)
        if (after.firstOrNull() in counterpartyWords) counterparty = true
        if (side == Side.NONE) {
            // "A/c XX1234 debited", "a/c XX1122 is credited": the verb right after the number.
            when (after.firstOrNull { it !in auxiliaries }) {
                in debitWords -> side = Side.SOURCE
                in creditWords -> side = Side.DEST
            }
        }
        return ref.copy(counterparty = counterparty, yours = yours, side = side)
    }

    /**
     * The instrument of a message whose direction (for the user) is [direction], from [refs] (see [findRefs]):
     * only the user's own numbers ([NumberRef.isOwn]) are considered.
     */
    fun detect(sender: String, body: String, refs: List<NumberRef>, direction: TransactionDirection): DetectedInstrument {
        val counterparty = refs.firstOrNull { !it.isOwn(direction) }?.masked
        val own = refs.filter { it.isOwn(direction) }
        val card = own.firstOrNull { it.isCard }

        // A payment *to* one of the user's cards ("Rs 5,000 paid to Credit Card XX9876", "paid towards your Credit
        // Card XX5566 from A/c XX1234"): the card is where the money went, not the instrument that paid. With the
        // paying account named, the message is that account's debit; alone, it is the card's credit.
        if (card != null && card.side == Side.DEST && card.kind != RefKind.DEBIT_CARD && direction == TransactionDirection.DEBIT) {
            val payer = own.firstOrNull { !it.isCard && it.kind != RefKind.LOAN }
            if (payer != null) {
                return DetectedInstrument(InstrumentType.BANK_ACCOUNT, payer.masked, counterpartyMaskedNumber = counterparty)
            }
            val type = cardType(sender, body, card, namesAccount = false)
            return DetectedInstrument(type, card.masked, counterpartyMaskedNumber = counterparty, directionOverride = TransactionDirection.CREDIT)
        }
        return detectOwn(sender, body, own, direction).copy(counterpartyMaskedNumber = counterparty)
    }

    /**
     * [detect] over the user's own references. With two of the user's accounts named ("from A/c XX1212 to own A/c
     * XX9999"), a debit is the source account's and a credit the destination's.
     */
    private fun detectOwn(sender: String, body: String, own: List<NumberRef>, direction: TransactionDirection): DetectedInstrument {
        val farSide = if (direction == TransactionDirection.DEBIT) Side.DEST else Side.SOURCE
        val refs = own.sortedBy { if (it.side == farSide) 1 else 0 }
        val card = refs.firstOrNull { it.isCard }
        val loan = refs.firstOrNull { it.kind == RefKind.LOAN }
        val account = refs.firstOrNull { it.kind == RefKind.ACCOUNT }
        val bare = refs.firstOrNull { it.kind == RefKind.BARE }

        if (card != null) {
            val type = cardType(sender, body, card, namesAccount = account != null)
            val linked = if (type == InstrumentType.DEBIT_CARD) account?.masked else null
            return DetectedInstrument(type, card.masked, linked)
        }
        if (loan != null) return DetectedInstrument(InstrumentType.LOAN, loan.masked, account?.masked)
        if (account != null) {
            return if (isLoanContext(body)) {
                DetectedInstrument(InstrumentType.LOAN, null, account.masked)
            } else {
                DetectedInstrument(InstrumentType.BANK_ACCOUNT, account.masked)
            }
        }
        return DetectedInstrument(byWording(sender, body), bare?.masked)
    }

    private fun cardType(sender: String, body: String, card: NumberRef, namesAccount: Boolean): InstrumentType = when (card.kind) {
        RefKind.DEBIT_CARD -> InstrumentType.DEBIT_CARD
        RefKind.CREDIT_CARD -> InstrumentType.CREDIT_CARD
        RefKind.PREPAID_CARD -> InstrumentType.PREPAID_CARD
        else -> resolveBareCard(sender, body, namesAccount)
    }

    /** Body-level wording, for an SMS with no typed number. */
    private fun byWording(sender: String, body: String): InstrumentType = when {
        prepaidPhrase.containsMatchIn(body) -> InstrumentType.PREPAID_CARD
        creditCardPhrase.containsMatchIn(body) -> InstrumentType.CREDIT_CARD
        debitCardPhrase.containsMatchIn(body) -> InstrumentType.DEBIT_CARD
        isLoanContext(body) -> InstrumentType.LOAN
        isCreditCardIssuer(sender, body) -> InstrumentType.CREDIT_CARD
        walletExplicit.containsMatchIn(body) -> InstrumentType.WALLET
        upiWord.containsMatchIn(body) || vpaHandle.containsMatchIn(body) -> InstrumentType.UPI
        walletBrand.containsMatchIn(body) -> InstrumentType.WALLET
        accountWord.containsMatchIn(body) -> InstrumentType.BANK_ACCOUNT
        cardWord.containsMatchIn(body) -> resolveBareCard(sender, body, namesAccount = false)
        else -> InstrumentType.UNKNOWN
    }

    /** A card the SMS does not type ("Card XX1234"). */
    private fun resolveBareCard(sender: String, body: String, namesAccount: Boolean): InstrumentType = when {
        prepaidPhrase.containsMatchIn(body) -> InstrumentType.PREPAID_CARD
        creditCardPhrase.containsMatchIn(body) -> InstrumentType.CREDIT_CARD
        debitCardPhrase.containsMatchIn(body) -> InstrumentType.DEBIT_CARD
        limitContext.containsMatchIn(body) -> InstrumentType.CREDIT_CARD
        isCreditCardIssuer(sender, body) -> InstrumentType.CREDIT_CARD
        namesAccount || fromAccountContext.containsMatchIn(body) -> InstrumentType.DEBIT_CARD
        balanceContext.containsMatchIn(body) -> InstrumentType.DEBIT_CARD
        else -> InstrumentType.CREDIT_CARD
    }

    private fun isLoanContext(body: String): Boolean =
        loanPhrase.containsMatchIn(body) || (loanWord.containsMatchIn(body) && emiWord.containsMatchIn(body))

    private fun isCreditCardIssuer(sender: String, body: String): Boolean {
        val header = sender.uppercase()
        return creditCardSenders.any { header.contains(it) } || creditCardBrand.containsMatchIn(body)
    }

    /** The number of a `$NUMBER` match whose first group index is [firstGroup] (masked form, else the plain digits). */
    private fun numberOf(match: MatchResult, firstGroup: Int): String? {
        val masked = match.groupValues[firstGroup]
        if (masked.isNotEmpty()) return normalise(masked)
        return match.groupValues[firstGroup + 1].takeIf { it.isNotEmpty() }
            ?: match.groupValues[firstGroup + 2].takeIf { it.isNotEmpty() }
    }

    /** Mask characters (X, *, dots, bullets) become `X`; blanks go. */
    private fun normalise(raw: String): String = buildString(raw.length) {
        for (c in raw) {
            when {
                c.isWhitespace() -> Unit
                c == '*' || c == '.' || c == '•' -> append('X')
                else -> append(c.uppercaseChar())
            }
        }
    }

    private fun cardKind(adjective: String): RefKind = when (adjective.lowercase()) {
        "debit", "atm" -> RefKind.DEBIT_CARD
        "credit" -> RefKind.CREDIT_CARD
        "" -> RefKind.CARD
        else -> RefKind.PREPAID_CARD
    }
}
