package app.dak.classify.scam

import app.dak.classify.DigitNormalizer
import app.dak.classify.LinkExtractor
import app.dak.classify.SenderId
import app.dak.classify.SenderKind
import app.dak.classify.TemplateBundle
import app.dak.classify.TrafficType
import app.dak.classify.text.GatedRegex
import java.math.BigDecimal

/**
 * Flags fake bank-credit alerts and the "sent by mistake, please return it" follow-ups that go with them (threat
 * model: `docs/security/fake-credit-scams.md`). Pure, on-device rules; no network, no model, no cloud.
 *
 * Scoring: every [ScamReason] found adds its weight; a saved contact halves the total (contacts can be
 * compromised, so it never drops to zero). [LIKELY_THRESHOLD] and above is [ScamLevel.LIKELY_SCAM],
 * [SUSPICIOUS_THRESHOLD] and above is [ScamLevel.SUSPICIOUS].
 *
 * Messages from a *verified* sender (a DLT header the template bundle lists for a bank or wallet, not on the
 * promotional route) are never flagged: in India those headers are registered to the brand and cannot be sent by
 * anyone else domestically, and genuine alerts routinely name other banks, show VPAs and mention refunds.
 *
 * Thread-safe (regexes are immutable), cheap, and bounded: only the first [MAX_SCAN_CHARS] characters are read.
 *
 * @param templates the template bundle, used for its sender table (header -> brand).
 */
public class FakeCreditDetector(private val templates: TemplateBundle) {

    /**
     * Evaluates one incoming message.
     *
     * @param address raw sender address (`VM-HDFCBK-S`, `+919812345678`, ...).
     * @param body message text.
     * @param hint transaction details the caller already parsed, if any.
     * @param knownAccounts the user's genuine accounts (may be empty when unknown, e.g. on the notification path).
     * @param isSavedContact the sender is in the user's contacts.
     * @param recentMessages incoming messages from the last 48 h (same sender, plus already-flagged credits).
     * @param dateMillis when this message arrived (anchors the follow-up window).
     * @param region ISO 3166 country of the receiving SIM (e.g. from a region profile); null when unknown. India's
     *   DLT rules (registered `XX-BRAND-S` headers, no bank alerts from mobile numbers) apply only for `"IN"`;
     *   elsewhere only generic signals are used (unknown sender + credit + return urgency, payment handles, PIN /
     *   collect bait, a known non-bank brand claiming a bank).
     */
    public fun evaluate(
        address: String,
        body: String,
        hint: TransactionHint? = null,
        knownAccounts: Set<AccountHint> = emptySet(),
        isSavedContact: Boolean = false,
        recentMessages: List<RecentMessage> = emptyList(),
        dateMillis: Long = System.currentTimeMillis(),
        region: String? = INDIA,
    ): ScamVerdict {
        val analysis = analyse(body)
        val text = analysis.text
        if (text.isBlank()) return ScamVerdict.None
        val india = isIndia(region)
        val sender = senderOf(address, india)
        if (sender.verified) return ScamVerdict.None

        val amounts = analysis.amounts + listOfNotNull(hint?.amountMinor)
        val transferMention = TRANSFER_MENTION.containsMatchIn(text)
        // "maine 15000 bhej diya": a bare number next to transfer wording is money too (follow-ups rarely write "Rs").
        val bareAmounts = if (transferMention) bareAmountsIn(text) else emptySet()
        val hasMoney = amounts.isNotEmpty() || bareAmounts.isNotEmpty() || MONEY_WORDS.containsMatchIn(text)
        val alert = alertKind(text, amounts.isNotEmpty(), hint)
        val returnRequest = hasMoney && RETURN_REQUEST.containsMatchIn(text)
        val pinToReceive = PIN_TO_RECEIVE.containsMatchIn(text)
        val collect = COLLECT.containsMatchIn(text) && RECEIVE_BAIT.containsMatchIn(text)
        val followUpCandidate = hasMoney && (returnRequest || transferMention)

        if (alert == null && !returnRequest && !pinToReceive && !collect && !followUpCandidate) return ScamVerdict.None

        val claimed = BankNames.familyIn(text)
        val reasons = LinkedHashSet<ScamReason>()
        var capAtSuspicious = false

        // --- Sender vs. claim
        if (alert != null && !india) {
            // Outside India banks legitimately use long codes, short codes and bare alphanumeric names.
            if (sender.inTable && sender.knownFamily == null && claimed != null) {
                reasons += ScamReason.BRAND_MISMATCH
            } else if (!isSavedContact) {
                reasons += ScamReason.UNKNOWN_SENDER_ALERT
            }
        } else if (alert != null) {
            when (sender.kind) {
                SenderKind.PHONE_NUMBER -> {
                    reasons += if (alert == Alert.CREDIT) ScamReason.CREDIT_ALERT_FROM_PHONE_NUMBER else ScamReason.DEBIT_ALERT_FROM_PHONE_NUMBER
                    if (claimed != null) reasons += ScamReason.PHONE_NUMBER_CLAIMS_BANK
                }
                SenderKind.DLT_HEADER -> when {
                    sender.traffic == TrafficType.PROMOTIONAL -> reasons += ScamReason.PROMOTIONAL_ROUTE
                    // A registered header of a known non-bank brand (Amazon refund "credited to your ICICI card") is
                    // genuine: DLT headers are bound to their brand. Only unknown headers naming someone else count.
                    sender.inTable -> Unit
                    claimed != null && BankNames.familyOfHeader(sender.mergeKey)?.id != claimed.id ->
                        reasons += ScamReason.UNVERIFIED_SENDER
                    else -> Unit
                }
                SenderKind.ALPHANUMERIC -> when {
                    sender.inTable && sender.knownFamily != null -> reasons += ScamReason.UNPREFIXED_BANK_HEADER
                    sender.inTable && claimed != null -> reasons += ScamReason.BRAND_MISMATCH
                    BankNames.familyOfHeader(sender.mergeKey) != null -> reasons += ScamReason.LOOKALIKE_SENDER
                    claimed != null -> reasons += ScamReason.UNVERIFIED_SENDER
                    else -> Unit
                }
                SenderKind.SHORT_CODE -> if (claimed != null) reasons += ScamReason.UNVERIFIED_SENDER
            }
        }

        // --- Account the user does not have
        if (alert != null && claimed != null && knownAccounts.isNotEmpty()) {
            val atBank = knownAccounts.filter { BankNames.familyIn(it.institution)?.id == claimed.id }
            if (atBank.isEmpty()) {
                reasons += ScamReason.NO_ACCOUNT_AT_BANK
            } else {
                // The other party's account in a transfer confirmation is not the user's, so it is never compared.
                val mask = maskIn(COUNTERPARTY_ACCOUNT.regex.replace(text, " ")) ?: hint?.last4
                if (mask != null && atBank.none { digitsMatch(it.maskedDigits, mask) }) reasons += ScamReason.UNKNOWN_ACCOUNT
            }
        }

        // --- Content
        if (returnRequest) reasons += ScamReason.RETURN_REQUEST
        // Indian mobile numbers are recognisable; elsewhere a number in an alert only counts next to a return request.
        if (alert != null && (india || returnRequest) && MOBILE.containsMatchIn(text)) reasons += ScamReason.MOBILE_NUMBER_IN_ALERT
        val hasLink = LinkExtractor.extract(text).isNotEmpty() || UPI_LINK.containsMatchIn(text)
        if (returnRequest && (hasLink || VPA.containsMatchIn(text))) {
            reasons += ScamReason.PAYMENT_HANDLE_WITH_RETURN
        } else if (hasLink && (alert != null || collect || pinToReceive)) {
            reasons += ScamReason.LINK_IN_ALERT
        }
        if (pinToReceive) reasons += ScamReason.PIN_TO_RECEIVE
        if (collect) reasons += ScamReason.COLLECT_REQUEST

        // --- Follow-up to a recent credit
        if (followUpCandidate && alert == null) {
            val window = recentMessages.filter { it.dateMillis in (dateMillis - FOLLOW_UP_WINDOW_MILLIS)..dateMillis && it.body != body }
            var unverifiedCredit = false
            var genuineCredit = false
            for (recent in window) {
                val recentText = normalize(recent.body)
                val recentAmounts = amountsIn(recentText)
                if (recent.flaggedCredit) {
                    unverifiedCredit = true
                    continue
                }
                if (alertKind(recentText, recentAmounts.isNotEmpty(), null) != Alert.CREDIT) continue
                val mentioned = amounts + bareAmounts
                val sameAmount = mentioned.isEmpty() || recentAmounts.any { it in mentioned }
                if (senderOf(recent.address, india).verified) {
                    if (sameAmount) genuineCredit = true
                } else if (sameAmount) {
                    unverifiedCredit = true
                }
            }
            if (unverifiedCredit) {
                reasons += ScamReason.FOLLOW_UP_AFTER_CREDIT
            } else if (genuineCredit && returnRequest) {
                reasons += ScamReason.RETURN_AFTER_GENUINE_CREDIT
                capAtSuspicious = true
            }
        }

        if (reasons.isEmpty()) return ScamVerdict.None
        var score = reasons.sumOf { it.weight }
        if (isSavedContact) score /= 2
        var level = when {
            score >= LIKELY_THRESHOLD -> ScamLevel.LIKELY_SCAM
            score >= SUSPICIOUS_THRESHOLD -> ScamLevel.SUSPICIOUS
            else -> ScamLevel.NONE
        }
        if (capAtSuspicious && level == ScamLevel.LIKELY_SCAM) level = ScamLevel.SUSPICIOUS
        if (level == ScamLevel.NONE) return ScamVerdict(ScamLevel.NONE, emptyList(), claimed?.displayName, score)
        return ScamVerdict(level, reasons.sortedByDescending { it.weight }, claimed?.displayName, score)
    }

    /**
     * Cheap pre-check: false when [evaluate] would certainly return [ScamVerdict.None] (a verified sender, or no money
     * / PIN / collect wording at all), so callers can skip loading contacts, accounts and history.
     */
    public fun isCandidate(address: String, body: String, region: String? = INDIA): Boolean {
        val analysis = analyse(body)
        val text = analysis.text
        if (text.isBlank() || senderOf(address, isIndia(region)).verified) return false
        return analysis.amounts.isNotEmpty() || MONEY_WORDS.containsMatchIn(text) || PIN_TO_RECEIVE.containsMatchIn(text) ||
            COLLECT.containsMatchIn(text) || TRANSFER_MENTION.containsMatchIn(text)
    }

    /**
     * True when [evaluate] would use `recentMessages` for this message (an unverified sender talking about sending /
     * returning money without being an alert itself), so callers can skip loading history for everything else.
     */
    public fun needsRecentMessages(address: String, body: String, region: String? = INDIA): Boolean {
        val analysis = analyse(body)
        val text = analysis.text
        if (text.isBlank() || senderOf(address, isIndia(region)).verified) return false
        val amounts = analysis.amounts
        val transferMention = TRANSFER_MENTION.containsMatchIn(text)
        val hasMoney = amounts.isNotEmpty() || MONEY_WORDS.containsMatchIn(text) || (transferMention && bareAmountsIn(text).isNotEmpty())
        if (!hasMoney) return false
        if (alertKind(text, amounts.isNotEmpty(), null) != null) return false
        return transferMention || RETURN_REQUEST.containsMatchIn(text)
    }

    // ------------------------------------------------------------------------------------------------ sender

    private class Sender(
        val kind: SenderKind,
        val mergeKey: String,
        val traffic: TrafficType?,
        /** The merge key is in the bundle's sender table. */
        val inTable: Boolean,
        /** Brand of the table entry, if any. */
        val knownBrand: String?,
        /** Bank/wallet family of that brand, if the brand is a financial institution. */
        val knownFamily: BankNames.Family?,
        /** A registered DLT header of a known bank/wallet, not on the promotional route. */
        val verified: Boolean,
    )

    private fun isIndia(region: String?): Boolean = region.equals(INDIA, ignoreCase = true)

    /**
     * In India a sender is verified only as a registered DLT header of a known bank/wallet off the promotional route.
     * Elsewhere any sender whose name is in the bank table counts (alphanumeric names are normal there).
     */
    private fun senderOf(address: String, india: Boolean): Sender {
        val kind = SenderId.classify(address)
        val key = SenderId.mergeKey(address)
        val header = if (kind == SenderKind.DLT_HEADER) SenderId.parseDltHeader(address) else null
        val entry = if (kind == SenderKind.DLT_HEADER || kind == SenderKind.ALPHANUMERIC) templates.sender(key) else null
        val family = entry?.brand?.let { BankNames.familyIn(it) }
        val verified = if (india) {
            kind == SenderKind.DLT_HEADER && family != null && header?.route != TrafficType.PROMOTIONAL
        } else {
            family != null && header?.route != TrafficType.PROMOTIONAL
        }
        return Sender(kind, key, header?.route, entry != null, entry?.brand, family, verified)
    }

    // ------------------------------------------------------------------------------------------------ text

    private enum class Alert { CREDIT, DEBIT }

    private fun alertKind(text: String, hasAmount: Boolean, hint: TransactionHint?): Alert? {
        val hinted = hint?.direction
        if (!hasAmount && hinted == null) return null
        if (!ACCOUNT_REF.containsMatchIn(text) && hinted == null) return null
        return when {
            // "Credited to beneficiary ..." confirms the user's own outgoing transfer: money left, it did not arrive.
            hinted == HintDirection.CREDIT || (CREDIT_WORDS.containsMatchIn(text) && !BENEFICIARY_CREDIT.containsMatchIn(text)) -> Alert.CREDIT
            hinted == HintDirection.DEBIT || DEBIT_WORDS.containsMatchIn(text) || BENEFICIARY_CREDIT.containsMatchIn(text) -> Alert.DEBIT
            else -> null
        }
    }

    /**
     * The normalized text and its amounts, shared by [isCandidate], [needsRecentMessages] and [evaluate]: callers ask
     * all three about the same body in a row, so the last body analysed on this thread is remembered (by identity;
     * pure functions of the body, so reuse is exact).
     */
    private class Analysis(val body: String, val text: String) {
        val amounts: Set<Long> by lazy(LazyThreadSafetyMode.NONE) { amountsIn(text) }
    }

    private val lastAnalysis = ThreadLocal<Analysis?>()

    private fun analyse(body: String): Analysis {
        lastAnalysis.get()?.let { if (it.body === body) return it }
        return Analysis(body, normalize(body)).also { lastAnalysis.set(it) }
    }

    private fun normalize(body: String): String =
        DigitNormalizer.normalizeDigits(if (body.length > MAX_SCAN_CHARS) body.substring(0, MAX_SCAN_CHARS) else body)

    public companion object {
        /** Only this many leading characters of a body are examined. */
        public const val MAX_SCAN_CHARS: Int = 4_000
        public const val LIKELY_THRESHOLD: Int = 60
        public const val SUSPICIOUS_THRESHOLD: Int = 30
        public const val FOLLOW_UP_WINDOW_MILLIS: Long = 48L * 60 * 60 * 1000

        /** Region code whose DLT sender rules the detector knows (the default region). */
        public const val INDIA: String = "IN"

        private val O = setOf(RegexOption.IGNORE_CASE)

        private val AMOUNT_BEFORE = GatedRegex("""(?:\brs\.?|\binr|₹|\brupees?|रु\.?|रू\.?|\$|€|£|₦|₱|\b(?:usd|eur|gbp|aed|sar|qar|kwd|omr|bhd|sgd|myr|aud|cad|ngn|kes|zar|php|idr|pkr|bdt|lkr|npr))\s?(\d[\d,]{0,14}(?:\.\d{1,2})?)""", O)
        private val AMOUNT_AFTER = GatedRegex("""(?<![\d.])(\d[\d,]{0,14}(?:\.\d{1,2})?)\s?(?:/-|rs\b|rupees?\b|रुपये|रु\.?|rupaye\b|rupay\b)""", O)

        private val MONEY_WORDS = GatedRegex(
            """\b(?:money|amount|payment|paise|paisa|paisay|rupees?|rupaye|upi|gpay|phone\s?pe|paytm|transfer\w*)\b|₹|रुपये|पैसे|पैसा""",
            O,
        )

        private val ACCOUNT_REF = GatedRegex(
            """a/c|\ba\.c\b|\bacc?t\b|\baccount|\bac\s?no|\bbank\b|\bupi\b|\bimps\b|\bneft\b|\brtgs\b|\bwallet\b|\bvpa\b|(?<![x*])[x*]{2,}+\d{2,6}|खाते|खाता|बैंक""",
            O,
        )

        private val CREDIT_WORDS = GatedRegex(
            """credited|\bcredit(?:ed)?\s+(?:of|with|by|for|to)\b|\bcr\b|\bcr\.|\breceived\b|\bdeposited\b|has\s+been\s+added|added\s+to\s+your|""" +
                """जमा|प्राप्त|क्रेडिट|\bjama\s+ho|\bcredit\s+ho|\baa\s+gaye\b|\baaye\s+hain\b|\bsalary\b""",
            O,
        )

        private val DEBIT_WORDS = GatedRegex(
            """debited|\bdebit(?:ed)?\s+(?:of|with|by|for|from)\b|\bdr\b|\bdr\.|\bwithdrawn\b|\bdeducted\b|\bspent\b|डेबिट|कट\s+गए|\bkat\s+gaye\b""",
            O,
        )

        /** "Sent by mistake / please return" in English, Hinglish and Hindi (only counted with money context). */
        private val RETURN_REQUEST = GatedRegex(
            """\bby\s+mistake\b|\bmistakenly\b|\bwrongly\s+(?:credited|sent|transferred|deposited)\b|\bwrong\s+(?:number|account|a/c|upi)\b|""" +
                """\baccidentally\b|\b(?:please|pls|plz|kindly)\s+(?:return|refund|send\s+(?:it\s+|the\s+\w+\s+)?back|pay\s+(?:it\s+)?back)\b|""" +
                """\brefund\s+(?:it\s+)?back\b|\bsend\s+(?:it\s+|the\s+money\s+|the\s+amount\s+|my\s+money\s+)?back\b|""" +
                """\breturn\s+(?:the\s+|my\s+)?(?:money|amount|payment|paise|paisa)\b|\bgal[a]?ti\s+se\b|\bgalthi\s+se\b|""" +
                """\b[wv]apa?s\s+(?:kar|kr|bhej|de|dedo|kardo|karo|karde|bhejo|bhejdo|kijiye|kariye)|\blauta\s+(?:do|dijiye|de|dena)\b|""" +
                """गलती\s+से|ग़लती\s+से|वापस|लौटा""",
            O,
        )

        /** Indian mobile number (not a toll-free 1800 line, not glued to an account mask). */
        private val MOBILE = GatedRegex("""(?<![\w\d])(?:\+?91[\s-]?|0)?[6-9]\d{4}[\s-]?\d{5}(?!\d)""")

        /** UPI ID (handle without a dot after the @, unlike an e-mail address). */
        private val VPA = GatedRegex("""\b[a-z0-9._-]{2,64}@[a-z]{2,32}(?![\w.])""", O)
        private val UPI_LINK = GatedRegex("""upi://""", O)

        /** "Enter UPI PIN to receive / accept": a UPI PIN is never needed to receive money. */
        private val PIN_TO_RECEIVE = GatedRegex(
            """\b(?:enter|use|type|provide|share|put)\s+(?:your\s+)?(?:upi\s+)?m?pin\b[^.!?\n]{0,60}\b(?:receive|accept|claim|get|credit|collect)|""" +
                """\b(?:receive|accept|claim|get|credit)\w*\b[^.!?\n]{0,60}\b(?:enter|use|type|provide)\s+(?:your\s+)?(?:upi\s+)?m?pin\b|""" +
                """\b(?:lene|paane|pane|receive\s+karne)\s+ke\s+liye[^.!?\n]{0,40}\bpin\b|पाने\s+के\s+लिए[^।.!?\n]{0,40}पिन""",
            O,
        )

        /** Collect / approve phrasing ("approve the request", "tap to accept"). */
        private val COLLECT = GatedRegex(
            """\b(?:collect|payment|money)\s+request\b|\brequested\s+(?:money|rs|inr|₹|payment)|""" +
                """\b(?:approve|accept)\s+(?:the\s+)?(?:payment\s+|collect\s+|money\s+)?request\b|""" +
                """\b(?:click|tap|press)\b[^.!?\n]{0,40}\b(?:accept|receive|claim|get)\b""",
            O,
        )

        /** Words that make a collect request look like incoming money. */
        private val RECEIVE_BAIT = GatedRegex("""receiv|credit|cashback|refund|\bwon\b|reward|prize|\bjeet|जीत|प्राप्त""", O)

        /** A personal message talking about a transfer ("I sent 5000", "bheja", "transfer kiya"). */
        private val TRANSFER_MENTION = GatedRegex(
            """\b(?:sent|transferred|transfer\s+(?:kiya|kar\s+diya|ho\s+gaya)|bheja|bhej\s+diya|dal\s+diya|daal\s+diya|credited)\b|भेजा|भेज\s+दिया""",
            O,
        )

        private val MASK = GatedRegex(
            // A single mask character only after an account keyword ("A/c X5073", AU Bank).
            """(?:\b(?:a\s?/\s?c|acc?t|account)\.?\s*(?:no\.?\s*)?[x*]|(?<![x*])[x*]{2,}+|\bending\s+(?:with\s+)?|\bno\.?\s*)(\d{3,6})(?!\d)""",
            O,
        )

        internal fun amountsIn(text: String): Set<Long> {
            val out = HashSet<Long>()
            for (regex in listOf(AMOUNT_BEFORE, AMOUNT_AFTER)) {
                for (m in regex.findAll(text)) {
                    val raw = m.groupValues[1].replace(",", "").trimEnd('.')
                    val minor = raw.toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.DOWN) ?: continue
                    if (minor > BigDecimal.ZERO && minor < MAX_MINOR) out += minor.toLong()
                    if (out.size >= 8) return out
                }
            }
            return out
        }

        private val MAX_MINOR = BigDecimal("100000000000000")

        /** Every body pattern, for the prefilter equivalence test (`GatedRegexEquivalenceTest`). */
        internal val allPatterns: List<GatedRegex>
            get() = listOf(
                AMOUNT_BEFORE, AMOUNT_AFTER, MONEY_WORDS, ACCOUNT_REF, CREDIT_WORDS, DEBIT_WORDS, RETURN_REQUEST, MOBILE, VPA,
                UPI_LINK, PIN_TO_RECEIVE, COLLECT, RECEIVE_BAIT, TRANSFER_MENTION, MASK, BARE_AMOUNT, COUNTERPARTY_ACCOUNT,
                BENEFICIARY_CREDIT,
            )

        /** Plain numbers of 3-7 digits (or Indian-grouped "15,000"), as rupees in minor units. */
        private val BARE_AMOUNT = GatedRegex("""(?<![\d.,])(\d{1,3}(?:,\d{2,3}){1,3}|\d{3,7})(?![\d,])""")

        internal fun bareAmountsIn(text: String): Set<Long> =
            BARE_AMOUNT.findAll(text).take(8).mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull()?.times(100) }.toSet()

        internal fun maskIn(text: String): String? = MASK.find(text)?.groupValues?.get(1)

        /** The other party's account ("beneficiary A/c XX5632", "payee account XX1234"). */
        private val COUNTERPARTY_ACCOUNT = GatedRegex(
            """\b(?:beneficiary|benef|bene|payee|recipient|receiver)(?:'s)?\.?\s*(?:bank\s+)?(?:a\s?/\s?c|acc?t|account)\.?\s*(?:no\.?\s*)?[x*]*\d{3,}""",
            O,
        )

        /** "Credited to beneficiary ...": the bank confirming the user's own outgoing transfer. */
        private val BENEFICIARY_CREDIT = GatedRegex(
            """\bcredited\s+(?:in)?to\s+(?:the\s+|your\s+)?(?:beneficiary|benef|bene|payee|recipient|receiver)""",
            O,
        )

        /** Masks match when the shorter visible tail is a suffix of the longer one (XX1234 vs XXXX001234). */
        internal fun digitsMatch(a: String, b: String): Boolean {
            val x = a.filter { it.isDigit() }
            val y = b.filter { it.isDigit() }
            if (x.isEmpty() || y.isEmpty()) return true
            return if (x.length >= y.length) x.endsWith(y) else y.endsWith(x)
        }
    }
}
