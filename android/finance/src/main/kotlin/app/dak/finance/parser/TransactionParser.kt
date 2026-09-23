package app.dak.finance.parser

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
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
 * Extracts an [ExtractedTransaction] from an Indian bank/card/UPI/wallet SMS, or returns null
 * when the message is not a completed transaction (an OTP, a promotion, or a bill reminder).
 *
 * This is deliberately a deterministic, regex-based parser (no ML), matching the "signed JSON
 * bundle of ... bank/OTP regexes" approach described for the classification pipeline: cheap,
 * offline, auditable, and easy to extend as new bank formats show up.
 */
object TransactionParser {

    private val otpPattern = Regex("""\botp\b|one[- ]time password|verification code|security code""", RegexOption.IGNORE_CASE)

    private val promoPattern = Regex(
        """cashback up ?to|get flat|avail (?:the )?offer|%\s?off|use code|win\s|assured cashback|limited period|click here|exclusive offer|download (?:the )?app|hurry|t&c appl""",
        RegexOption.IGNORE_CASE,
    )

    private val billReminderPattern = Regex(
        """due on|minimum amount due|payment due|total amount due|bill (?:of|amount)|kindly pay|pay (?:your|the) (?:bill|minimum)""",
        RegexOption.IGNORE_CASE,
    )

    private val debitPattern = Regex(
        """debited|spent|withdrawn|sent (?:rs|inr|₹|\$|usd|aed|eur)|paid (?:to|rs|inr|₹)|purchase|txn of|used for|used at|auto[- ]?debit|deducted|charged""",
        RegexOption.IGNORE_CASE,
    )

    private val creditPattern = Regex(
        """credited|received|deposited|refund(?:ed)?""",
        RegexOption.IGNORE_CASE,
    )

    private val balanceContextPattern = Regex(
        """av[ai]{0,2}l\.?\s*bal|available balance|(?:clr|clear)\s*bal|balance is|bal is""",
        RegexOption.IGNORE_CASE,
    )

    private val upiPattern = Regex("""\bupi\b""", RegexOption.IGNORE_CASE)
    private val creditCardPattern = Regex("""credit card""", RegexOption.IGNORE_CASE)
    private val debitCardPattern = Regex("""debit card""", RegexOption.IGNORE_CASE)
    private val cardPattern = Regex("""\bcard\b""", RegexOption.IGNORE_CASE)
    private val accountPattern = Regex("""\ba\s?/\s?c\b|\baccount\b|\bacct\b""", RegexOption.IGNORE_CASE)
    private val walletPattern = Regex("""\bwallet\b""", RegexOption.IGNORE_CASE)

    /**
     * Account/card number patterns. Group 1 is the number as far as it is shown (mask + every visible digit, e.g.
     * `XX440065`); banks reveal 4-6 digits and change that over time, so all visible digits are kept, not just 4.
     */
    private val accountNumberPatterns = listOf(
        Regex("""card\s*(?:no\.?)?\s*([x*]{2,}\s*\d{4,})""", RegexOption.IGNORE_CASE),
        Regex("""card\s+ending\s+(?:with\s+|in\s+)?(\d{4,})""", RegexOption.IGNORE_CASE),
        Regex("""a\s?/\s?c\s*(?:no\.?)?\s*([x*]{2,}\s*\d{4,})""", RegexOption.IGNORE_CASE),
        Regex("""a\s?/\s?c\s+ending\s+(?:with\s+|in\s+)?(\d{4,})""", RegexOption.IGNORE_CASE),
        Regex("""account\s*(?:no\.?)?\s*([x*]{2,}\s*\d{4,})""", RegexOption.IGNORE_CASE),
        Regex("""ending\s+(?:with\s+|in\s+)?(\d{4,})""", RegexOption.IGNORE_CASE),
        Regex("""([x*]{4,}\d{4,})\b"""),
    )

    private val merchantPatterns = listOf(
        Regex("""to\s+vpa\s+([\w.\-]+@[\w.\-]+)""", RegexOption.IGNORE_CASE),
        Regex("""info\s*[:\-]\s*([^.\n]+)""", RegexOption.IGNORE_CASE),
        Regex("""\bat\s+([A-Z0-9][A-Z0-9&.\-' ]{1,40}?)(?=\s+on\b|\s+via\b|[.,]|$)""", RegexOption.IGNORE_CASE),
        Regex("""\bto\s+([A-Z0-9][A-Z0-9&.\-' ]{1,40}?)(?=\s+on\b|\s+via\b|[.,]|$)""", RegexOption.IGNORE_CASE),
    )

    private val referencePatterns = listOf(
        Regex("""upi\s*ref(?:erence)?\.?\s*(?:no\.?)?\s*[:\-]?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""rrn\s*[:\-]?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""ref(?:erence)?\s*(?:no\.?)?\s*[:\-]?\s*(\w+)""", RegexOption.IGNORE_CASE),
        Regex("""txn\s*id\s*[:\-]?\s*(\w+)""", RegexOption.IGNORE_CASE),
    )

    /** Parses [body] from [sender] into an [ExtractedTransaction], or null if it is not a completed transaction. */
    fun parse(sender: String, rawBody: String): ExtractedTransaction? {
        // Normalise non-ASCII decimal digits (Devanagari, Bengali, Arabic-Indic, full-width, ...)
        // once up front so every `\d` regex below (last4, reference, amounts) matches regardless
        // of the digit script the SMS was written in.
        val body = DigitNormalizer.normalizeDigits(rawBody)
        if (otpPattern.containsMatchIn(body)) return null
        if (promoPattern.containsMatchIn(body)) return null
        if (billReminderPattern.containsMatchIn(body)) return null

        val debitMatch = debitPattern.find(body)
        val creditMatch = creditPattern.find(body)
        val direction = when {
            debitMatch != null && creditMatch != null -> {
                if (debitMatch.range.first <= creditMatch.range.first) TransactionDirection.DEBIT else TransactionDirection.CREDIT
            }
            debitMatch != null -> TransactionDirection.DEBIT
            creditMatch != null -> TransactionDirection.CREDIT
            else -> return null
        }

        val occurrences = MoneyParser.findAll(body)
        if (occurrences.isEmpty()) return null

        val balanceOccurrence = pickBalanceOccurrence(body, occurrences)
        val txnOccurrence = occurrences.firstOrNull { it != balanceOccurrence } ?: occurrences.first()

        val instrument = detectInstrument(body)
        val maskedNumber = detectMaskedNumber(body)
        val last4 = maskedNumber?.filter { it.isDigit() }?.takeLast(4)
        val merchant = detectMerchant(body)
        val reference = detectReference(body)
        val institution = InstitutionTable.institutionFor(sender)

        return ExtractedTransaction(
            direction = direction,
            amountMinor = txnOccurrence.money.amountMinor,
            currency = txnOccurrence.money.currencyUpper,
            instrument = instrument,
            last4 = last4,
            merchant = merchant,
            reference = reference,
            balanceMinor = balanceOccurrence?.money?.amountMinor,
            balanceCurrency = balanceOccurrence?.money?.currencyUpper,
            institution = institution,
            maskedNumber = maskedNumber,
        )
    }

    /** Parses [body] as a bill/statement-due reminder, or null if it doesn't look like one. */
    fun parseBillReminder(sender: String, rawBody: String): BillReminder? {
        val body = DigitNormalizer.normalizeDigits(rawBody)
        if (!billReminderPattern.containsMatchIn(body)) return null
        val amount = MoneyParser.findAll(body).firstOrNull() ?: return null
        val dueHint = Regex("""due on[^.,\n]*""", RegexOption.IGNORE_CASE).find(body)?.value
        return BillReminder(
            amountMinor = amount.money.amountMinor,
            currency = amount.money.currencyUpper,
            dueHint = dueHint,
            institution = InstitutionTable.institutionFor(sender),
        )
    }

    private fun pickBalanceOccurrence(body: String, occurrences: List<MoneyOccurrence>): MoneyOccurrence? {
        if (occurrences.size < 2) return null
        val balanceKeywordMatch = balanceContextPattern.find(body) ?: return null
        // The balance amount is the occurrence closest to (and after, typically) the balance keyword.
        return occurrences.minByOrNull { occurrence ->
            kotlin.math.abs(occurrence.range.first - balanceKeywordMatch.range.first)
        }
    }

    private fun detectInstrument(body: String): InstrumentType = when {
        upiPattern.containsMatchIn(body) -> InstrumentType.UPI
        creditCardPattern.containsMatchIn(body) -> InstrumentType.CREDIT_CARD
        debitCardPattern.containsMatchIn(body) -> InstrumentType.BANK_ACCOUNT
        cardPattern.containsMatchIn(body) -> InstrumentType.CREDIT_CARD
        accountPattern.containsMatchIn(body) -> InstrumentType.BANK_ACCOUNT
        walletPattern.containsMatchIn(body) -> InstrumentType.WALLET
        else -> InstrumentType.UNKNOWN
    }

    /** The first account/card number in [body], normalised (`*` -> `X`, upper-case, no spaces), e.g. `XX440065`. */
    private fun detectMaskedNumber(body: String): String? {
        for (pattern in accountNumberPatterns) {
            pattern.find(body)?.let { match ->
                return match.groupValues[1].filterNot { it.isWhitespace() }.uppercase().replace('*', 'X')
            }
        }
        return null
    }

    private fun detectMerchant(body: String): String? {
        for (pattern in merchantPatterns) {
            pattern.find(body)?.let { return it.groupValues[1].trim().trimEnd('.', ',') }
        }
        return null
    }

    private fun detectReference(body: String): String? {
        for (pattern in referencePatterns) {
            pattern.find(body)?.let { return it.groupValues[1] }
        }
        return null
    }
}
