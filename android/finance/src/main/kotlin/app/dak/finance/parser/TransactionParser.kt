package app.dak.finance.parser

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.CurrencyTable
import app.dak.finance.money.DigitNormalizer
import app.dak.finance.money.MoneyOccurrence
import app.dak.finance.money.MoneyParser

/** A payment-due / statement reminder, deliberately kept separate from [ExtractedTransaction] since no money has moved. */
data class BillReminder(
    val amountMinor: Long,
    val currency: String,
    val dueHint: String?,
    val institution: String?,
)

/**
 * Extracts an [ExtractedTransaction] from a bank/card/UPI/wallet/loan SMS, or returns null when the message is not a
 * completed transaction (an OTP, a promotion, a request, a mandate set-up, a failed or future movement, a statement,
 * a balance alert or a bill reminder).
 *
 * This is deliberately a deterministic, rule-based parser (no ML), matching the "signed JSON bundle of ... bank/OTP
 * regexes" approach described for the classification pipeline: cheap, offline and auditable. Its rules are about the
 * structure and vocabulary of transaction SMS in general, never about one bank's template:
 *
 * 1. **Gates** ([DirectionCues]): OTPs (a safety footer such as "never share your OTP" does not count), promotions,
 *    payment/collect requests and statements are never transactions. Failed / declined / cancelled movements are
 *    not, unless the message also states a completed refund, reversal or credit (then it is that credit).
 * 2. **Direction**: from the [Cue]s that state a movement as done ("debited", "credited", "spent", "received"; not
 *    "will be debited", "if debited", "to be credited"). A completed refund / reversal wins; else the first finite
 *    verb; else the first transaction noun ("txn of", "payment of", "Dr.", "deposit of") — and a message with only
 *    nouns that also says it is scheduled, pending, set up or due is not a transaction. Transfer verbs (sent / paid /
 *    transferred) are the user's credit when the money went "to you" / "to your" account.
 * 3. **Whose numbers** ([InstrumentDetector], [NumberRef.isOwn]): each masked number gets a role from the words next
 *    to it — the other party's (beneficiary / payee / recipient / remitter), "your", and which side of the movement
 *    it is on. The other party's account never becomes the user's. A credit whose only named account is the other
 *    party's destination ("INR 1,00,000 credited to beneficiary A/c XX5632 for your NEFT") is a confirmation of the
 *    user's outgoing transfer: the user's debit when the SMS names the user's source account ("from your A/c
 *    XX1234"), otherwise not a transaction (the debit comes in its own SMS).
 * 4. **Amounts** ([AmountRoles]): balances, limits, dues, fees, cashback and bracketed equivalents are never the
 *    transaction amount; of the rest, the one nearest the deciding verb is. A bare number is only read as money right
 *    after "debited by / credited with" in an SMS whose other amounts, or Indian DLT sender, give its currency.
 */
object TransactionParser {

    private val promoPattern = Regex(
        """cashback up ?to|get flat|avail (?:the )?offer|%\s?off|use code|\bwin\s|assured cashback|limited period|click here|exclusive offer|download (?:the )?app|hurry|t&c appl|\bup\s?to\s+(?:rs\.?|inr|₹)|\bapply\s+now\b|\bpre-?approved\b|\beligible\s+for\b""",
        RegexOption.IGNORE_CASE,
    )

    private val billReminderPattern = Regex(
        """due on|minimum amount due|payment due|total amount due|bill (?:of|amount)|kindly pay|pay (?:your|the) (?:bill|minimum)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The bank confirming that the user's own transfer reached the other party, with no account number to carry a
     * role ("Rs 2,500 has been credited to the payee Ramesh").
     */
    private val creditToOtherParty = Regex(
        """\bcredited\s+(?:in)?to\s+(?:the\s+|your\s+)?(?:beneficiary|benef|bene|payee|recipient|receiver)""",
        RegexOption.IGNORE_CASE,
    )

    /** A number with no currency right after "debited by / credited with" ("A/C X9876 debited by 250.0 on"). */
    private val bareAmountAfterVerb = Regex(
        """\b(?:debited|credited|withdrawn|deposited|paid|received|sent|transferred)\s+(?:by|with|for|of)\s+(\d{1,3}(?:,\d{2,3}){1,4}(?:\.\d{1,2})?|\d{1,10}(?:\.\d{1,2})?)(?![\d,])""" +
            // ...and then ends the phrase: "debited by 250.0 on date", not "credited with 500 reward points".
            """(?=\s+(?:on|at|to|from|towards|for|via|dated|ref|in|and|by|trf)\b|[ \t]*(?:[.,;:()]|$))""",
        RegexOption.IGNORE_CASE,
    )

    private val merchantVpaDebit = Regex("""\bto\s+(?:vpa\s+)?([\w.\-]+@[\w.\-]+)""", RegexOption.IGNORE_CASE)
    private val merchantVpaCredit = Regex("""\b(?:from|by)\s+(?:vpa\s+)?([\w.\-]+@[\w.\-]+)""", RegexOption.IGNORE_CASE)
    private val merchantInfo = Regex("""\binfo\s*[:\-]\s*([^.\n]+)""", RegexOption.IGNORE_CASE)
    private const val NAME_END =
        """(?=\s+(?:on|via|using|with|from|for|is|was|has|by|ref|and|in|txn|avl|dated|through|thru)\b|[.,;:(]|$)"""
    private val merchantAt = Regex("""\bat\s+([A-Z][A-Z0-9&.\-' ]{1,40}?)$NAME_END""", RegexOption.IGNORE_CASE)
    private val merchantTo = Regex("""\bto\s+([A-Z0-9][A-Z0-9&.\-' ]{1,40}?)$NAME_END""", RegexOption.IGNORE_CASE)

    /** A "to X" capture that is an account, card or pronoun, not a payee name. */
    private val notAName = Regex(
        """^(?:your|you|ur|the|a\s?/\s?c|ac|acct|account|card|credit|debit|beneficiary|payee|vpa|an?|be|block|loan|bank|wallet|upi|mobile|report)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val referencePatterns = listOf(
        Regex("""\bupi\s*ref(?:erence)?\.?\s*(?:no\.?)?\s*[:\-]?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:rrn|utr)\b\s*(?:no\.?)?\s*[:\-]?\s*(\w+)""", RegexOption.IGNORE_CASE),
        Regex("""\bref(?:erence)?(?:\s*no)?\b\.?\s*[:\-]?\s*(\w+)""", RegexOption.IGNORE_CASE),
        Regex("""\btxn\s*id\s*[:\-]?\s*(\w+)""", RegexOption.IGNORE_CASE),
    )

    /**
     * Parses [rawBody] from [sender] into an [ExtractedTransaction], or null if it is not a completed transaction.
     * [symbolMap] resolves symbols such as a bare `$`; pass `CurrencyTable.symbolMapFor(homeCurrency)` so `$` reads
     * as CAD for a Canadian SIM, SGD for a Singaporean one, and so on (the default reads it as USD).
     */
    fun parse(
        sender: String,
        rawBody: String,
        symbolMap: Map<String, String> = CurrencyTable.defaultSymbolToCurrency,
    ): ExtractedTransaction? {
        // Normalise non-ASCII decimal digits (Devanagari, Bengali, Arabic-Indic, full-width, ...)
        // once up front so every `\d` regex below (last4, reference, amounts) matches regardless
        // of the digit script the SMS was written in.
        val body = DigitNormalizer.normalizeDigits(rawBody)
        if (DirectionCues.isOtp(body)) return null
        if (promoPattern.containsMatchIn(body)) return null
        if (DirectionCues.request.containsMatchIn(body)) return null
        if (DirectionCues.statement.containsMatchIn(body)) return null

        val cues = DirectionCues.find(body)
        val strong = cues.filter { it.strong }
        val refund = strong.firstOrNull { it.refund }
        val failed = DirectionCues.failure.containsMatchIn(body)
        val mainCue: Cue = when {
            refund != null -> refund
            failed -> strong.firstOrNull { it.direction == TransactionDirection.CREDIT } ?: return null
            strong.isNotEmpty() -> strong.first()
            cues.isEmpty() -> return null
            DirectionCues.due.containsMatchIn(body) || billReminderPattern.containsMatchIn(body) -> return null
            DirectionCues.pendingOrSetup.containsMatchIn(body) -> return null
            else -> cues.first()
        }
        var direction = if (mainCue.transfer) DirectionCues.transferDirection(body, mainCue) else mainCue.direction

        val refs = InstrumentDetector.findRefs(body)
        if (direction == TransactionDirection.CREDIT && refund == null) {
            // "INR 2,000 credited to A/c XX5632 of RAMESH from your A/c XX1234": the user's named account is the
            // source, so for the user this is money out.
            val yoursSource = refs.any { it.yours && !it.counterparty && it.side == Side.SOURCE }
            val yoursElsewhere = refs.any { it.yours && !it.counterparty && it.side != Side.SOURCE }
            if (yoursSource && !yoursElsewhere) {
                direction = TransactionDirection.DEBIT
            } else if (refs.none { it.isOwn(direction) } && goesToOtherParty(body, refs)) {
                return null
            }
        }

        val detected = InstrumentDetector.detect(sender, body, refs, direction)
        direction = detected.directionOverride ?: direction
        val instrument = detected.instrument

        val amounts = AmountRoles.classify(body, MoneyParser.findAll(body, symbolMap))
        val txnAmount = pickAmount(amounts, mainCue, direction)
            ?: bareAmount(sender, body, amounts)
            ?: return null
        val balance = amounts.firstOrNull { it.role == AmountRole.BALANCE && it.occurrence !== txnAmount }?.occurrence
            ?: if (instrument == InstrumentType.LOAN && detected.linkedMaskedNumber == null) {
                amounts.firstOrNull { it.role == AmountRole.DUE && "outstanding" in it.beforeWords }?.occurrence
            } else {
                null
            }

        val maskedNumber = detected.maskedNumber
        val last4 = maskedNumber?.filter { it.isDigit() }?.takeLast(4)
        return ExtractedTransaction(
            direction = direction,
            amountMinor = txnAmount.money.amountMinor,
            currency = txnAmount.money.currencyUpper,
            instrument = instrument,
            last4 = last4,
            merchant = detectMerchant(body, direction),
            reference = detectReference(body),
            balanceMinor = balance?.money?.amountMinor,
            balanceCurrency = balance?.money?.currencyUpper,
            institution = InstitutionTable.institutionFor(sender),
            maskedNumber = maskedNumber,
            linkedMaskedNumber = detected.linkedMaskedNumber,
        )
    }

    /** Parses [body] as a bill/statement-due reminder, or null if it doesn't look like one. */
    fun parseBillReminder(
        sender: String,
        rawBody: String,
        symbolMap: Map<String, String> = CurrencyTable.defaultSymbolToCurrency,
    ): BillReminder? {
        val body = DigitNormalizer.normalizeDigits(rawBody)
        if (!billReminderPattern.containsMatchIn(body) && !DirectionCues.due.containsMatchIn(body)) return null
        val amount = MoneyParser.findAll(body, symbolMap).firstOrNull() ?: return null
        val dueHint = Regex("""due\s+(?:on|by)[^.,\n]*""", RegexOption.IGNORE_CASE).find(body)?.value
        return BillReminder(
            amountMinor = amount.money.amountMinor,
            currency = amount.money.currencyUpper,
            dueHint = dueHint,
            institution = InstitutionTable.institutionFor(sender),
        )
    }

    /** Whether the credit in [body] went to the other party (a beneficiary / payee destination). */
    private fun goesToOtherParty(body: String, refs: List<NumberRef>): Boolean =
        creditToOtherParty.containsMatchIn(body) || refs.any { it.counterparty && it.side != Side.SOURCE }

    /**
     * The transaction amount: of the amounts that are not a balance, limit, due, fee or equivalent (nor, for a debit,
     * a cashback), the one nearest the cue that decided the direction.
     */
    private fun pickAmount(amounts: List<RoledAmount>, cue: Cue, direction: TransactionDirection): MoneyOccurrence? {
        val candidates = amounts.filter {
            it.role == AmountRole.NONE || (it.role == AmountRole.CASHBACK && direction == TransactionDirection.CREDIT)
        }
        return candidates.minByOrNull { distance(it.occurrence.range, cue.range) }?.occurrence
    }

    private fun distance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }

    /**
     * "A/C X9876 debited by 250.0": a number with no currency, right after the movement verb. Its currency is the one
     * the SMS uses elsewhere (e.g. for the balance), else INR for an Indian DLT sender; otherwise it is not read.
     */
    private fun bareAmount(sender: String, body: String, amounts: List<RoledAmount>): MoneyOccurrence? {
        val match = bareAmountAfterVerb.find(body) ?: return null
        val currency = amounts.firstOrNull()?.occurrence?.money?.currencyUpper
            ?: if (InstitutionTable.isDltSender(sender)) "INR" else return null
        val group = match.groups[1] ?: return null
        val money = try {
            MoneyParser.parseAmount(group.value, currency)
        } catch (e: NumberFormatException) {
            return null
        } catch (e: ArithmeticException) {
            return null
        }
        if (money.amountMinor <= 0) return null
        return MoneyOccurrence(money, group.range, group.value)
    }

    private fun detectMerchant(body: String, direction: TransactionDirection): String? {
        val vpa = if (direction == TransactionDirection.DEBIT) merchantVpaDebit else merchantVpaCredit
        vpa.find(body)?.let { return clean(it.groupValues[1]) }
        merchantInfo.find(body)?.let { return clean(it.groupValues[1]) }
        merchantAt.find(body)?.let { return clean(it.groupValues[1]) }
        if (direction == TransactionDirection.DEBIT) {
            for (m in merchantTo.findAll(body)) {
                val name = m.groupValues[1]
                if (!notAName.containsMatchIn(name)) return clean(name)
            }
        }
        return null
    }

    private fun clean(raw: String): String = raw.trim().trimEnd('.', ',')

    private fun detectReference(body: String): String? {
        for (pattern in referencePatterns) {
            for (m in pattern.findAll(body)) {
                val value = m.groupValues[1]
                if (value.equals("no", ignoreCase = true) || value.equals("number", ignoreCase = true)) continue
                return value
            }
        }
        return null
    }
}
