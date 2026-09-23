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
        """debited|spent|withdrawn|sent (?:rs|inr|₹|\$|usd|aed|eur)|paid (?:to|rs|inr|₹)|purchase|txn of|used for|used at|auto[- ]?debit|deducted|charged|a charge of|you paid|(?:was|has been) authori[sz]ed""",
        RegexOption.IGNORE_CASE,
    )

    private val creditPattern = Regex(
        """credited|received|deposited|refund(?:ed)?""",
        RegexOption.IGNORE_CASE,
    )

    private val balanceContextPattern = Regex(
        """av[ai]{0,2}l\.?\s*bal|available balance|(?:clr|clear)\s*bal|balance is|bal is|remaining balance|\bbalance\s*[:\-]|\bbal(?:ance)?\.?\s*[:\-]?\s*(?:inr|rs\.?|₹)""",
        RegexOption.IGNORE_CASE,
    )

    /** A loan's amount still owed ("Outstanding principal: Rs 4,20,000"), used as the balance of a loan SMS. */
    private val loanOutstandingPattern = Regex(
        """outstanding(?:\s+(?:principal|amount|balance|loan))?(?:\s+(?:is|of))?\s*[:\-]?""",
        RegexOption.IGNORE_CASE,
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

        val occurrences = MoneyParser.findAll(body, symbolMap)
        if (occurrences.isEmpty()) return null

        val detected = InstrumentDetector.detect(sender, body)
        val instrument = detected.instrument
        val balanceOccurrence = pickBalanceOccurrence(body, occurrences, balanceContextPattern)
            ?: if (instrument == InstrumentType.LOAN && detected.linkedMaskedNumber == null) {
                pickBalanceOccurrence(body, occurrences, loanOutstandingPattern)
            } else {
                null
            }
        val txnOccurrence = occurrences.firstOrNull { it != balanceOccurrence } ?: occurrences.first()

        val maskedNumber = detected.maskedNumber
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
        if (!billReminderPattern.containsMatchIn(body)) return null
        val amount = MoneyParser.findAll(body, symbolMap).firstOrNull() ?: return null
        val dueHint = Regex("""due on[^.,\n]*""", RegexOption.IGNORE_CASE).find(body)?.value
        return BillReminder(
            amountMinor = amount.money.amountMinor,
            currency = amount.money.currencyUpper,
            dueHint = dueHint,
            institution = InstitutionTable.institutionFor(sender),
        )
    }

    private fun pickBalanceOccurrence(body: String, occurrences: List<MoneyOccurrence>, context: Regex): MoneyOccurrence? {
        if (occurrences.size < 2) return null
        val balanceKeywordMatch = context.find(body) ?: return null
        // The balance amount is the occurrence closest to (and after, typically) the balance keyword.
        return occurrences.minByOrNull { occurrence ->
            kotlin.math.abs(occurrence.range.first - balanceKeywordMatch.range.first)
        }
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
