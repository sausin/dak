package app.dak.classify.scam

import app.dak.classify.DigitNormalizer
import app.dak.classify.LinkExtractor
import app.dak.classify.SenderId
import app.dak.classify.SenderKind
import app.dak.classify.TemplateBundle
import app.dak.classify.TrafficType
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
     */
    public fun evaluate(
        address: String,
        body: String,
        hint: TransactionHint? = null,
        knownAccounts: Set<AccountHint> = emptySet(),
        isSavedContact: Boolean = false,
        recentMessages: List<RecentMessage> = emptyList(),
        dateMillis: Long = System.currentTimeMillis(),
    ): ScamVerdict {
        val text = normalize(body)
        if (text.isBlank()) return ScamVerdict.None
        val sender = senderOf(address)
        if (sender.verified) return ScamVerdict.None

        val amounts = amountsIn(text) + listOfNotNull(hint?.amountMinor)
        val hasMoney = amounts.isNotEmpty() || MONEY_WORDS.containsMatchIn(text)
        val alert = alertKind(text, amounts.isNotEmpty(), hint)
        val returnRequest = hasMoney && RETURN_REQUEST.containsMatchIn(text)
        val pinToReceive = PIN_TO_RECEIVE.containsMatchIn(text)
        val collect = COLLECT.containsMatchIn(text) && RECEIVE_BAIT.containsMatchIn(text)
        val followUpCandidate = hasMoney && (returnRequest || TRANSFER_MENTION.containsMatchIn(text))

        if (alert == null && !returnRequest && !pinToReceive && !collect && !followUpCandidate) return ScamVerdict.None

        val claimed = BankNames.familyIn(text)
        val reasons = LinkedHashSet<ScamReason>()
        var capAtSuspicious = false

        // --- Sender vs. claim
        if (alert != null) {
            when (sender.kind) {
                SenderKind.PHONE_NUMBER -> {
                    reasons += if (alert == Alert.CREDIT) ScamReason.CREDIT_ALERT_FROM_PHONE_NUMBER else ScamReason.DEBIT_ALERT_FROM_PHONE_NUMBER
                    if (claimed != null) reasons += ScamReason.PHONE_NUMBER_CLAIMS_BANK
                }
                SenderKind.DLT_HEADER -> when {
                    sender.traffic == TrafficType.PROMOTIONAL -> reasons += ScamReason.PROMOTIONAL_ROUTE
                    sender.knownBrand != null && claimed != null -> reasons += ScamReason.BRAND_MISMATCH
                    sender.knownBrand == null && claimed != null && BankNames.familyOfHeader(sender.mergeKey)?.id != claimed.id ->
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
                val mask = maskIn(text) ?: hint?.last4
                if (mask != null && atBank.none { digitsMatch(it.maskedDigits, mask) }) reasons += ScamReason.UNKNOWN_ACCOUNT
            }
        }

        // --- Content
        if (returnRequest) reasons += ScamReason.RETURN_REQUEST
        if (alert == Alert.CREDIT && MOBILE.containsMatchIn(text)) reasons += ScamReason.MOBILE_NUMBER_IN_ALERT
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
                val sameAmount = amounts.isEmpty() || recentAmounts.any { it in amounts }
                if (senderOf(recent.address).verified) {
                    if (sameAmount && (returnRequest || amounts.isNotEmpty())) genuineCredit = true
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

    private fun senderOf(address: String): Sender {
        val kind = SenderId.classify(address)
        val key = SenderId.mergeKey(address)
        val header = if (kind == SenderKind.DLT_HEADER) SenderId.parseDltHeader(address) else null
        val entry = if (kind == SenderKind.DLT_HEADER || kind == SenderKind.ALPHANUMERIC) templates.sender(key) else null
        val family = entry?.brand?.let { BankNames.familyIn(it) }
        val verified = kind == SenderKind.DLT_HEADER && family != null && header?.trafficType != TrafficType.PROMOTIONAL
        return Sender(kind, key, header?.trafficType, entry != null, entry?.brand, family, verified)
    }

    // ------------------------------------------------------------------------------------------------ text

    private enum class Alert { CREDIT, DEBIT }

    private fun alertKind(text: String, hasAmount: Boolean, hint: TransactionHint?): Alert? {
        val hinted = hint?.direction
        if (!hasAmount && hinted == null) return null
        if (!ACCOUNT_REF.containsMatchIn(text) && hinted == null) return null
        return when {
            hinted == HintDirection.CREDIT || CREDIT_WORDS.containsMatchIn(text) -> Alert.CREDIT
            hinted == HintDirection.DEBIT || DEBIT_WORDS.containsMatchIn(text) -> Alert.DEBIT
            else -> null
        }
    }

    private fun normalize(body: String): String =
        DigitNormalizer.normalizeDigits(if (body.length > MAX_SCAN_CHARS) body.substring(0, MAX_SCAN_CHARS) else body)

    public companion object {
        /** Only this many leading characters of a body are examined. */
        public const val MAX_SCAN_CHARS: Int = 4_000
        public const val LIKELY_THRESHOLD: Int = 60
        public const val SUSPICIOUS_THRESHOLD: Int = 30
        public const val FOLLOW_UP_WINDOW_MILLIS: Long = 48L * 60 * 60 * 1000

        private val O = setOf(RegexOption.IGNORE_CASE)

        private val AMOUNT_BEFORE = Regex("""(?:\brs\.?|\binr|₹|\brupees?)\s?(\d[\d,]{0,14}(?:\.\d{1,2})?)""", O)
        private val AMOUNT_AFTER = Regex("""(?<![\d.])(\d[\d,]{0,14}(?:\.\d{1,2})?)\s?(?:/-|rs\b|rupees?\b|रुपये|रु\.?|rupaye\b|rupay\b)""", O)

        private val MONEY_WORDS = Regex(
            """\b(?:money|amount|payment|paise|paisa|paisay|rupees?|rupaye|upi|gpay|phone\s?pe|paytm|transfer\w*)\b|₹|रुपये|पैसे|पैसा""",
            O,
        )

        private val ACCOUNT_REF = Regex(
            """a/c|\ba\.c\b|\bacc?t\b|\baccount|\bac\s?no|\bbank\b|\bupi\b|\bimps\b|\bneft\b|\brtgs\b|\bwallet\b|\bvpa\b|[x*]{2,}\d{2,6}|खाते|खाता|बैंक""",
            O,
        )

        private val CREDIT_WORDS = Regex(
            """credited|\bcredit(?:ed)?\s+(?:of|with|by|for|to)\b|\bcr\b|\bcr\.|\breceived\b|\bdeposited\b|has\s+been\s+added|added\s+to\s+your|""" +
                """जमा|प्राप्त|क्रेडिट|\bjama\s+ho|\bcredit\s+ho|\baa\s+gaye\b|\baaye\s+hain\b|\bsalary\b""",
            O,
        )

        private val DEBIT_WORDS = Regex(
            """debited|\bdebit(?:ed)?\s+(?:of|with|by|for|from)\b|\bdr\b|\bdr\.|\bwithdrawn\b|\bdeducted\b|\bspent\b|डेबिट|कट\s+गए|\bkat\s+gaye\b""",
            O,
        )

        /** "Sent by mistake / please return" in English, Hinglish and Hindi (only counted with money context). */
        private val RETURN_REQUEST = Regex(
            """\bby\s+mistake\b|\bmistakenly\b|\bwrongly\s+(?:credited|sent|transferred|deposited)\b|\bwrong\s+(?:number|account|a/c|upi)\b|""" +
                """\baccidentally\b|\b(?:please|pls|plz|kindly)\s+(?:return|refund|send\s+(?:it\s+|the\s+\w+\s+)?back|pay\s+(?:it\s+)?back)\b|""" +
                """\brefund\s+(?:it\s+)?back\b|\bsend\s+(?:it\s+|the\s+money\s+|the\s+amount\s+|my\s+money\s+)?back\b|""" +
                """\breturn\s+(?:the\s+|my\s+)?(?:money|amount|payment|paise|paisa)\b|\bgal[a]?ti\s+se\b|\bgalthi\s+se\b|""" +
                """\b[wv]apa?s\s+(?:kar|kr|bhej|de|dedo|kardo|karo|karde|bhejo|bhejdo|kijiye|kariye)|\blauta\s+(?:do|dijiye|de|dena)\b|""" +
                """गलती\s+से|ग़लती\s+से|वापस|लौटा""",
            O,
        )

        /** Indian mobile number (not a toll-free 1800 line, not glued to an account mask). */
        private val MOBILE = Regex("""(?<![\w\d])(?:\+?91[\s-]?|0)?[6-9]\d{4}[\s-]?\d{5}(?!\d)""")

        /** UPI ID (handle without a dot after the @, unlike an e-mail address). */
        private val VPA = Regex("""\b[a-z0-9._-]{2,64}@[a-z]{2,32}(?![\w.])""", O)
        private val UPI_LINK = Regex("""upi://""", O)

        /** "Enter UPI PIN to receive / accept": a UPI PIN is never needed to receive money. */
        private val PIN_TO_RECEIVE = Regex(
            """\b(?:enter|use|type|provide|share|put)\s+(?:your\s+)?(?:upi\s+)?m?pin\b[^.!?\n]{0,60}\b(?:receive|accept|claim|get|credit|collect)|""" +
                """\b(?:receive|accept|claim|get|credit)\w*\b[^.!?\n]{0,60}\b(?:enter|use|type|provide)\s+(?:your\s+)?(?:upi\s+)?m?pin\b|""" +
                """\b(?:lene|paane|pane|receive\s+karne)\s+ke\s+liye[^.!?\n]{0,40}\bpin\b|पाने\s+के\s+लिए[^।.!?\n]{0,40}पिन""",
            O,
        )

        /** Collect / approve phrasing ("approve the request", "tap to accept"). */
        private val COLLECT = Regex(
            """\b(?:collect|payment|money)\s+request\b|\brequested\s+(?:money|rs|inr|₹|payment)|""" +
                """\b(?:approve|accept)\s+(?:the\s+)?(?:payment\s+|collect\s+|money\s+)?request\b|""" +
                """\b(?:click|tap|press)\b[^.!?\n]{0,40}\b(?:accept|receive|claim|get)\b""",
            O,
        )

        /** Words that make a collect request look like incoming money. */
        private val RECEIVE_BAIT = Regex("""receiv|credit|cashback|refund|\bwon\b|reward|prize|\bjeet|जीत|प्राप्त""", O)

        /** A personal message talking about a transfer ("I sent 5000", "bheja", "transfer kiya"). */
        private val TRANSFER_MENTION = Regex(
            """\b(?:sent|transferred|transfer\s+(?:kiya|kar\s+diya|ho\s+gaya)|bheja|bhej\s+diya|dal\s+diya|daal\s+diya|credited)\b|भेजा|भेज\s+दिया""",
            O,
        )

        private val MASK = Regex("""(?:[x*]{2,}|\bending\s+(?:with\s+)?|\bno\.?\s*)(\d{3,6})(?!\d)""", O)

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

        internal fun maskIn(text: String): String? = MASK.find(text)?.groupValues?.get(1)

        /** Masks match when the shorter visible tail is a suffix of the longer one (XX1234 vs XXXX001234). */
        internal fun digitsMatch(a: String, b: String): Boolean {
            val x = a.filter { it.isDigit() }
            val y = b.filter { it.isDigit() }
            if (x.isEmpty() || y.isEmpty()) return true
            return if (x.length >= y.length) x.endsWith(y) else y.endsWith(x)
        }
    }
}
