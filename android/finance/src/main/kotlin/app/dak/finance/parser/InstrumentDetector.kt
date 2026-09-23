package app.dak.finance.parser

import app.dak.core.model.InstrumentType

/**
 * What [InstrumentDetector] found: the instrument, the number of that instrument as far as the SMS shows it, and
 * (for a debit card or loan) the bank account the SMS says the money moved from.
 */
internal data class DetectedInstrument(
    val instrument: InstrumentType,
    val maskedNumber: String?,
    val linkedMaskedNumber: String? = null,
)

/**
 * Decides which payment instrument a transaction SMS is about, from the words right before each masked number
 * ("Debit Card XX1234", "A/c XX5678", "Loan A/c XX9012") and then from body-level wording. Deterministic and
 * offline; every regex is linear (no nested quantifiers) because the sender controls the body.
 *
 * Precedence: a typed card number (debit/credit/prepaid/forex/travel/gift/...) > a loan number or loan/EMI wording
 * with an account > an account number > body wording (prepaid, credit card, debit card, loan, card brands, wallet,
 * UPI, account, bare "card"). A bare "Card XX1234" is a debit card only when the SMS also names the account it was
 * debited from or states an available *balance*; it is a credit card when it states a limit or names a card issuer,
 * and otherwise stays a credit card, as before, so existing account ids do not move (the user can change the type).
 */
internal object InstrumentDetector {

    private enum class RefKind { DEBIT_CARD, CREDIT_CARD, PREPAID_CARD, CARD, LOAN, ACCOUNT, BARE }

    private data class NumberRef(val kind: RefKind, val masked: String, val range: IntRange)

    // Always follows a card / loan / account keyword, so a single mask character is enough ("A/c X5073", AU Bank).
    private const val NUMBER = """(?:([x*]+\s*\d{4,})|ending\s+(?:with\s+|in\s+)?(\d{4,}))"""
    private const val NO = """(?:(?:no\.?|number)\s*)?"""

    private val cardRef = Regex(
        """(?:\b(debit|credit|prepaid|forex|travel|multi[- ]?currency|gift|atm|wise|revolut)\s+)?card\s*$NO$NUMBER""",
        RegexOption.IGNORE_CASE,
    )
    private val abbreviatedCardRef = Regex("""\b(DC|CC)\s*$NO$NUMBER""", RegexOption.IGNORE_CASE)
    private val loanRef = Regex(
        """\bloan\s*(?:(?:a\s?/\s?c|account|acct)\.?\s*)?$NO(?:$NUMBER|(\d{6,}))""",
        RegexOption.IGNORE_CASE,
    )
    private val accountRef = Regex("""\b(?:a\s?/\s?c|account|acct)\.?\s*$NO$NUMBER""", RegexOption.IGNORE_CASE)
    private val bareRefs = listOf(
        Regex("""ending\s+(?:with\s+|in\s+)?(\d{4,})""", RegexOption.IGNORE_CASE),
        Regex("""([x*]{4,}\d{4,})\b""", RegexOption.IGNORE_CASE),
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

    private val limitContext = Regex("""\b(?:av[ai]{0,2}l\.?|available|credit)\s*(?:lmt|limit)\b""", RegexOption.IGNORE_CASE)
    private val balanceContext = Regex("""\bav[ai]{0,2}l\.?\s*bal|\bavailable\s+bal""", RegexOption.IGNORE_CASE)
    private val fromAccountContext = Regex(
        """\b(?:from|in|to)\s+(?:your\s+)?(?:(?:bank|savings|current)\s+)?(?:a\s?/\s?c|account|acct)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun detect(sender: String, body: String): DetectedInstrument {
        val refs = findRefs(body)
        val card = refs.firstOrNull { it.kind in CARD_KINDS }
        val loan = refs.firstOrNull { it.kind == RefKind.LOAN }
        val account = refs.firstOrNull { it.kind == RefKind.ACCOUNT }
        val bare = refs.firstOrNull { it.kind == RefKind.BARE }

        if (card != null) {
            val type = when (card.kind) {
                RefKind.DEBIT_CARD -> InstrumentType.DEBIT_CARD
                RefKind.CREDIT_CARD -> InstrumentType.CREDIT_CARD
                RefKind.PREPAID_CARD -> InstrumentType.PREPAID_CARD
                else -> resolveBareCard(sender, body, namesAccount = account != null)
            }
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

    /** Every masked number in [body] with the kind of the words right before it; typed refs win overlaps. */
    private fun findRefs(body: String): List<NumberRef> {
        val out = ArrayList<NumberRef>()
        fun add(kind: RefKind, match: MatchResult, masked: String?) {
            if (masked == null) return
            if (out.any { it.range.first <= match.range.last && match.range.first <= it.range.last }) return
            out += NumberRef(kind, masked, match.range)
        }
        for (m in cardRef.findAll(body)) add(cardKind(m.groupValues[1]), m, numberOf(m, 2))
        for (m in abbreviatedCardRef.findAll(body)) {
            val kind = if (m.groupValues[1].equals("DC", ignoreCase = true)) RefKind.DEBIT_CARD else RefKind.CREDIT_CARD
            add(kind, m, numberOf(m, 2))
        }
        for (m in loanRef.findAll(body)) {
            val unmasked = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { "XX" + it.takeLast(4) }
            add(RefKind.LOAN, m, numberOf(m, 1) ?: unmasked)
        }
        for (m in accountRef.findAll(body)) add(RefKind.ACCOUNT, m, numberOf(m, 1))
        for (pattern in bareRefs) for (m in pattern.findAll(body)) add(RefKind.BARE, m, normalise(m.groupValues[1]))
        return out.sortedBy { it.range.first }
    }

    /** The number of a `$NUMBER` match whose first group index is [firstGroup] (masked form, else "ending" digits). */
    private fun numberOf(match: MatchResult, firstGroup: Int): String? {
        val masked = match.groupValues[firstGroup]
        if (masked.isNotEmpty()) return normalise(masked)
        return match.groupValues[firstGroup + 1].takeIf { it.isNotEmpty() }
    }

    private fun normalise(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase().replace('*', 'X')

    private fun cardKind(adjective: String): RefKind = when (adjective.lowercase()) {
        "debit", "atm" -> RefKind.DEBIT_CARD
        "credit" -> RefKind.CREDIT_CARD
        "" -> RefKind.CARD
        else -> RefKind.PREPAID_CARD
    }

    private val CARD_KINDS = setOf(RefKind.DEBIT_CARD, RefKind.CREDIT_CARD, RefKind.PREPAID_CARD, RefKind.CARD)
}
