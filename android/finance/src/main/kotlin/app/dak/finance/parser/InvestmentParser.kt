package app.dak.finance.parser

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.InvestmentAction
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.MoneyOccurrence
import app.dak.finance.money.MoneyParser
import java.math.BigDecimal

/**
 * The investment side of a message: an SMS about the user's mutual-fund folio or demat account (not the bank account
 * that paid for it). [transaction] is null when it is such a message but records no money movement or value (a
 * security alert, an NFO promotion, a future / failed instalment, a NAV-only note, a KYC update).
 */
internal data class InvestmentOutcome(val transaction: ExtractedTransaction?)

/**
 * Reads mutual-fund and demat messages: SIP / lumpsum purchases, redemptions, switches, dividends / IDCW (with units
 * and NAV when stated), contract notes / trade confirmations (quantity @ price), and holdings valuations (which
 * become the account's balance, like a bank's "Avl Bal").
 *
 * Generic by rule: every pattern is structure or vocabulary (folio, units, NAV, SIP, IDCW, allotted, redemption,
 * switch, ISIN, demat, BO ID, DP ID, client ID, contract note, bought / sold qty @ price, pledge, e-DIS, CAS), never a
 * fund house, registrar, broker or depository name; the institution is the sender header ([InstitutionTable]).
 * Every regex is linear or bounded (the sender controls the body).
 *
 * Which side a message is on: it is the investment's when it names a folio / demat id, or carries fund-side or
 * trade-side detail (units with NAV / allotment / redemption, IDCW, a trade line, a contract note, a valuation, a
 * security alert). A bank's own debit that only mentions a SIP or a folio next to the user's bank account ("Rs 5,000
 * debited from A/c XX1234 towards SIP, folio 1234") stays the bank's (and is marked an own transfer by
 * [TransactionParser]); a fund's allotment that mentions the bank account it was paid from stays the fund's, so the
 * bank account is never debited twice.
 */
internal object InvestmentParser {

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // ---- identifiers ----

    /** "Folio No. XXXX1234", "Folio 12345678/90", "folio: 1234567 / 12" (the check suffix after "/" is dropped). */
    private val folioRef = rx("""\bfolio\b\.?\s*(?:no\b\.?|number\b|num\b|#)?\s*[:#\-]?\s*([x*•\d][x*•\d.]{0,20}\d)(?:\s*/\s*\d{1,4}\b)?""")

    /** "BO ID 1234567800012345" (16 digits: DP id + client id), "BOID: XXXXXXXX00012345". */
    private val boRef = rx("""\bBO\s*[- ]?ID\b\.?\s*(?:no\b\.?)?\s*[:#\-]?\s*([x*•\d]{6,16}\d)""")

    /** "Client ID 12345678", "client id: XXXX5678", "Client Code AB1234". */
    private val clientRef = rx("""\bclient\s*(?:id|code)\b\.?\s*(?:no\b\.?)?\s*[:#\-]?\s*([a-z]{0,4}[x*•\d]{2,14}\d)\b""")

    /** "Demat a/c XXXX1234", "demat account no. 1234567800012345", "Demat Acct 12345678". */
    private val dematRef = rx(
        """\bdemat\s*(?:a\s?/\s?c|account|acct|ac)\b\.?\s*(?:no\b\.?|number\b)?\s*[:#\-]?\s*([x*•\d]{2,16}\d)""",
    )

    private val dematWord = rx("""\bdemat\b|\bBO\s*[- ]?ID\b|\bDP\s*[- ]?ID\b|\bdepository\b""")

    // ---- vocabulary ----

    private val unitsWord = rx("""\bunits?\b""")
    private val navWord = rx("""\bNAV\b""")
    private val allotWord = rx("""\ballot(?:ted|ment)\b""")
    private val idcwWord = rx("""\bIDCW\b|\bdividend\b|\bincome\s+distribution\b""")
    private val reinvestWord = rx("""\bre-?invest(?:ment|ed)?\b""")
    private val switchWord = rx("""\bswitch(?:ed|es|ing)?\b""")
    private val redeemWord = rx("""\bredeem(?:ed)?\b|\bredemption\b""")
    private val purchaseWord = rx(
        """\bpurchase[ds]?\b|\bSIP\b|\binvest(?:ed|ment)\b|\bsubscri(?:bed|ption)\b|\bprocessed\b|\bapplied\b|\blumpsum\b""",
    )
    private val mutualFundWord = rx("""\bmutual\s*funds?\b|\bscheme\b""")
    private val contractNote = rx("""\bcontract\s+note\b|\btrade\s+(?:confirmation|executed|details)\b|\btrades?\s+(?:done|executed)\b""")
    /** Words that make "bought 2 X at Rs 500" a securities trade rather than a shop order. */
    private val tradeContext = rx(
        """\bshares?\b|\bqty\b|\bquantity\b|\btrade[sd]?\b|\bstocks?\b|\bequity\b|\bsecurities\b|\bexchange\b|\bdemat\b|""" +
            """\bcontract\s+note\b|\bISIN\b|\bclient\s*(?:id|code)\b|\bBO\s*[- ]?ID\b""",
    )
    private val buyWord = rx("""\bbought\b|\bbuy\b|\bpurchased\b""")
    private val sellWord = rx("""\bsold\b|\bsell\b""")
    private val isinWord = rx("""\bISIN\b""")
    private val casWord = rx("""\bconsolidated\s+account\s+statement\b|\bCAS\b""")

    /** "Bought 10 ABC LTD @ 2,345.50", "SOLD 5 shares of XYZ at Rs 1,200", "Buy Qty 25 PQR @ 99.5". */
    private val tradeLine = rx(
        """\b(bought|sold|buy|sell)\s+(?:qty\.?\s*[:\-]?\s*)?(\d[\d,]{0,12})\s+(?:(?:shares?|qty|nos?\.?|units?)\s+(?:of\s+)?)?""" +
            """([a-z][a-z0-9&.\-' ]{0,40}?)\s*(?:@|\bat\b)\s*(?:(?:rs|inr|usd|eur|gbp|aed|sgd)\.?\s*|[$€£₹]\s*)?(\d[\d,]{0,12}(?:\.\d{1,4})?)""",
    )

    /** A valuation / holdings statement: the value is the account's balance. */
    private val valuationWord = rx(
        """\bvaluation\b|\b(?:current|market|portfolio|holdings?|investment|closing)\s+value\b|\bvalue\s+of\s+(?:your\s+)?(?:holdings?|investments?|portfolio|units)\b""" +
            """|\btotal\s+value\b|\bnet\s+asset\s+value\s+of\s+your\b""",
    )

    // ---- security alerts: never ledger entries (routed to Alerts by the classifier's "investment-alert" label) ----

    private val securityAlert = rx(
        """\b(?:re-?|un)?pledge[ds]?\b|\bmargin\s+pledge\b|\blien\b|\be-?DIS\b|""" +
            """\b(?:shares?|securities|qty|quantity|isin)\b[^.!?\n]{0,80}?\b(?:debited|transferred|moved|withdrawn|removed|off-?market)\b|""" +
            """\b(?:debited|transferred|withdrawn)\s+(?:from\s+)?(?:your\s+)?(?:demat|BO)\b""",
    )

    // ---- gates ----

    private val nfoPromo = rx(
        """\bNFO\b|\bnew\s+fund\s+offer\b|\binvest\s+now\b|\breturns?\s+(?:of\s+)?up\s?to\b|\bstart\s+(?:a|your)\s+SIP\b|""" +
            """\b(?:SIP|invest\w*)\s+(?:starting|from)\s+(?:just\s+|only\s+|at\s+)?(?:rs\.?|inr|₹)""",
    )

    /** Not done yet: an instalment that will be debited, a request received, a SIP registered or due. */
    private val notYet = rx(
        """\b(?:will|shall|would)\s+be\b|\bto\s+be\s+(?:allotted|processed|credited|debited|presented)\b|\b(?:is|are)\s+(?:due|scheduled)\b|""" +
            """\bdue\s+(?:on|date)\b|\bupcoming\b|\breminder\b|\b(?:SIP|mandate|instruction)\b[^.!?\n]{0,80}?\bregistered\b|\bregistration\b|""" +
            """\bregistered\s+successfully\b|\bsuccessfully\s+registered\b|""" +
            """\bunder\s+process\b|\bpending\b|\bin\s+process\b""",
    )

    /** A completed step ("processed", "allotted", "redeemed"): with it, "will be credited" is only about the payout. */
    private val doneWord = rx(
        """\b(?:processed|allotted|redeemed|switched|executed|confirmed|completed|invested|purchased|bought|sold)\b""",
    )
    private val modalBefore = setOf("be", "to", "will", "shall", "would", "not", "yet")

    // ---- figures ----

    /** A plain number ("1,127.890", "45.6789"), never starting or ending inside another number. */
    private const val NUM = """(?<![\d,])(?<!\d\.)(\d(?:[\d,]{0,15}\d)?(?:\.\d{1,6})?)"""
    private const val CUR = """(?:(?:rs|inr)\.?\s*|₹\s*)?"""
    private const val DATE = """(?:\s*(?:as\s+on|as\s+of|on|dt\.?|dated)\s+\d{1,2}[-/ .]?(?:\d{1,2}|[a-z]{3,9})[-/ .]?\d{2,4})?"""

    /** "NAV Rs 45.6789", "NAV of 45.67", "at a NAV of Rs.45.6789", "NAV as on 12-Sep-2026: 45.6789", "@ Rs 45.67 per unit". */
    private val navValue = rx("""\bNAV\b$DATE(?:\s+(?:of|is|was|at|@))?\s*[:\-@]?\s*$CUR$NUM""")
    private val atPerUnit = rx("""@\s*$CUR$NUM\s*(?:per\s+unit|/\s*unit|p\.?u\.?)?|\bat\s+$CUR$NUM\s*(?:per\s+unit|/\s*unit)""")

    /** Units held after the transaction: "Balance units 1,234.567", "total units held: 890.12", "units balance 12.5". */
    private val unitsHeldValue = rx(
        """\b(?:total|balance|closing|available|holding|cumulative|clos\.?)\s+units?\b(?:\s+(?:held|balance))?(?:\s+(?:is|are|of))?\s*[:\-]?\s*$NUM|""" +
            """\bunits?\s+(?:held|balance|holding)\b(?:\s+(?:is|are|of))?\s*[:\-]?\s*$NUM|$NUM\s*units?\s+held\b""",
    )

    /** Units moved: "45.678 units", "Units allotted: 45.678", "No. of units 45.678". */
    private val unitsValue = rx(
        """$NUM\s*units?\b|\bunits?\b(?:\s+(?:allotted|redeemed|switched(?:\s+(?:in|out))?|purchased|credited|debited|of))?\s*[:\-]?\s*$NUM(?!\s*%)""",
    )

    private val isinValue = Regex("""\b([A-Z]{2}[A-Z0-9]{9}\d)\b""")

    /** Words before an amount that make it a fee, not the transaction amount. */
    private val feeWords = setOf("stamp", "duty", "charges", "charge", "brokerage", "stt", "gst", "fee", "fees", "load", "tds", "tax", "levies")
    private val netWords = setOf("net", "obligation", "consideration", "payable", "receivable", "total", "value", "amount", "amt")
    private val valueQualifiers = setOf("current", "market", "portfolio", "holding", "holdings", "investment", "investments", "total", "closing")
    private val stopWords = setOf("debited", "credited", "allotted", "redeemed", "paid", "invested", "purchased", "bought", "sold", "switched")

    /** Up to eight capitalised words ending in "Fund" / "Scheme" / "ETF" ("XYZ Flexi Cap Fund"); case-sensitive. */
    private val schemeName = Regex("""((?:[A-Z0-9][A-Za-z0-9&'.]{0,30} ){1,8})(Fund|FUND|Scheme|SCHEME|ETF|FoF|FOF)\b""")
    private val schemeSuffix = Regex("""^(?:\s?-\s?[A-Z][A-Za-z]{0,20}(?: [A-Z][A-Za-z]{0,20}){0,3}){1,3}""")
    private val whitespace = Regex("""\s+""")

    /** Words that end the run before a scheme name ("UNITS ALLOTTED IN XYZ FUND" -> "XYZ FUND"). */
    private val notNameWords = setOf(
        "of", "in", "for", "under", "to", "the", "your", "from", "into", "via", "with", "and", "on", "at", "by", "units", "unit",
        "allotted", "redeemed", "switched", "rs", "rs.", "inr", "sip", "nav", "folio", "dear", "investor", "a/c", "no", "no.", "is", "has",
        "been", "towards", "purchase", "redemption", "switch", "dividend", "idcw", "amount", "amt",
    )
    /**
     * A superset of the words [parse] needs to recognise an investment message (see there). Kept in step with
     * [folioRef], [boRef], [clientRef], [dematRef], [unitsWord], [navWord], [idcwWord], [tradeLine], [contractNote],
     * [casWord] and [dematWord]; `LiteralGateTest` checks it on the parser corpus.
     */
    internal val investmentVocabulary = GatedPattern(
        """\b(?:folio|bo\s*[- ]?id|dp\s*[- ]?id|client\s*(?:id|code)|demat|units?|nav|idcw|dividend|income\s+distribution|""" +
            """bought|sold|buy|sell|contract\s+note|trades?|cas|consolidated\s+account\s+statement|depository|isin)\b""",
        listOf(
            "folio", "bo", "dp", "client", "demat", "unit", "nav", "idcw", "dividend", "income", "bought", "sold", "buy", "sell",
            "contract", "trade", "cas", "consolidated", "depository", "isin",
        ),
    )

    private val sharesWord = rx("""\bshares?\b""")
    private val receivableWord = rx("""\breceivable\b|\bcredited\s+to\s+(?:you|your)\b""")
    private val payableWord = rx("""\bpayable\b|\bdebited\b""")

    /** An investment account's number as the SMS names it, and its kind. */
    private data class InvestmentRef(val instrument: InstrumentType, val masked: String, val range: IntRange)

    /**
     * The investment-side reading of [body] (already digit-normalised), or null when it is not an investment
     * account's message (then the bank-side parser reads it).
     */
    fun parse(
        sender: String,
        body: String,
        symbolMap: Map<String, String>,
        folded: String = GatedPattern.fold(body),
        gated: Boolean = true,
    ): InvestmentOutcome? {
        // Every branch below that makes this an investment message needs one of these words (a folio / demat / BO /
        // client reference, units / NAV / IDCW, a trade verb or contract note, a CAS, a depository or ISIN): without
        // any, skip the ~20 vocabulary passes (most SMS are not about investments).
        if (gated && !investmentVocabulary.containsMatchIn(body, folded)) return null
        val refs = findRefs(body)
        val trades = if (tradeContext.containsMatchIn(body)) tradeLine.findAll(body).toList() else emptyList()
        // A contract note with both buys and sells: its net amount is recorded, not one line's quantity and price.
        val mixed = trades.map { it.groupValues[1].lowercase().let { v -> v == "bought" || v == "buy" } }.distinct().size > 1
        val trade = trades.firstOrNull()?.takeIf { !mixed }
        val hasUnits = unitsWord.containsMatchIn(body)
        val fundDetail = (hasUnits && (navWord.containsMatchIn(body) || allotWord.containsMatchIn(body) || redeemWord.containsMatchIn(body) ||
            switchWord.containsMatchIn(body) || idcwWord.containsMatchIn(body))) ||
            (navWord.containsMatchIn(body) && (allotWord.containsMatchIn(body) || redeemWord.containsMatchIn(body))) ||
            (idcwWord.containsMatchIn(body) && (hasUnits || mutualFundWord.containsMatchIn(body) || refs.any { it.instrument == InstrumentType.MUTUAL_FUND }) &&
                !sharesWord.containsMatchIn(body))
        val tradeDetail = trades.isNotEmpty() || contractNote.containsMatchIn(body)
        val alert = securityAlert.containsMatchIn(body)
        val valuation = valuationWord.containsMatchIn(body) || casWord.containsMatchIn(body)
        val dematContext = dematWord.containsMatchIn(body) || isinWord.containsMatchIn(body)

        val instrument = when {
            refs.any { it.instrument == InstrumentType.MUTUAL_FUND } -> InstrumentType.MUTUAL_FUND
            refs.any { it.instrument == InstrumentType.DEMAT } -> InstrumentType.DEMAT
            fundDetail -> InstrumentType.MUTUAL_FUND
            tradeDetail -> InstrumentType.DEMAT
            // A consolidated account statement: a valuation of the user's folios.
            casWord.containsMatchIn(body) -> InstrumentType.MUTUAL_FUND
            dematContext && (alert || valuation) -> InstrumentType.DEMAT
            else -> return null
        }

        // A bank's own debit / credit that only mentions a SIP, folio or demat account next to the user's bank account
        // or card: the bank side (TransactionParser marks it an own transfer). A fund / broker naming the account it
        // paid out to or was paid from says "bank a/c XX4321": that is only a reference.
        val bankRefs = InstrumentDetector.findRefs(body).filter { ref ->
            ref.kind != RefKind.BARE && refs.none { it.range.first <= ref.range.last && ref.range.first <= it.range.last } &&
                "bank" !in SmsWords.before(body, ref.range.first, 2)
        }
        // With no folio / demat id at all, a bank account named next to demat words is the bank's movement too (a loan
        // against pledged shares disbursed to "your A/c XX1234").
        if (bankRefs.isNotEmpty() && !fundDetail && !tradeDetail && (refs.isEmpty() || (!alert && !valuation))) return null

        // Security alerts (shares debited from demat, pledge, e-DIS) are not money movements: no ledger entry.
        if (alert && !tradeDetail) return InvestmentOutcome(null)
        if (nfoPromo.containsMatchIn(body) || TransactionParser.isPromotion(body)) return InvestmentOutcome(null)
        if (DirectionCues.failure.containsMatchIn(body)) return InvestmentOutcome(null)
        if (notYet.containsMatchIn(body) && !isDone(body)) return InvestmentOutcome(null)

        val action = actionOf(instrument, body, valuation) ?: return InvestmentOutcome(null)
        val ref = refs.firstOrNull { it.instrument == instrument } ?: refs.firstOrNull()

        val priceRanges = ArrayList<IntRange>()
        var unitPrice: String? = null
        navValue.find(body)?.let { m -> m.groups[1]?.let { g -> unitPrice = plain(g.value); priceRanges += m.range } }
        for (m in atPerUnit.findAll(body)) {
            priceRanges += m.range
            if (unitPrice == null) unitPrice = (m.groups[1] ?: m.groups[2])?.value?.let(::plain)
        }
        var units: String? = null
        var merchant: String? = null
        // A demat account's per-unit figure is only ever a trade line's price.
        if (instrument == InstrumentType.DEMAT) unitPrice = null
        if (trade != null) {
            units = plain(trade.groupValues[2])
            unitPrice = plain(trade.groupValues[4])
            merchant = trade.groupValues[3].trim().trimEnd('.', ',').takeIf { it.isNotEmpty() }
            priceRanges += trade.groups[4]!!.range
        }
        val heldMatch = unitsHeldValue.find(body)
        val unitsHeld = heldMatch?.let { m -> (m.groups[1] ?: m.groups[2] ?: m.groups[3])?.value?.let(::plain) }
        if (units == null) {
            units = unitsValue.findAll(body)
                .filter { m -> heldMatch == null || m.range.last < heldMatch.range.first || m.range.first > heldMatch.range.last }
                .firstNotNullOfOrNull { m -> (m.groups[1] ?: m.groups[2])?.value?.let(::plain) }
        }

        val occurrences = MoneyParser.findAll(body, symbolMap)
            .filter { o -> priceRanges.none { it.first <= o.range.last && o.range.first <= it.last } }
            .filter { o -> AmountRoles.classify(body, listOf(o)).first().role.let { it != AmountRole.LIMIT && it != AmountRole.BALANCE } }
        val (values, others) = occurrences.partition { isValueAmount(body, it) }
        val candidates = others.filter { !isFee(body, it) && !isPriceWord(body, it) }

        val cue = actionCue(action, body)
        val amount: MoneyOccurrence? = when (action) {
            InvestmentAction.VALUATION -> null
            InvestmentAction.BUY, InvestmentAction.SELL ->
                candidates.firstOrNull { o -> SmsWords.before(body, o.range.first, 3).any { it in netWords } } ?: nearest(candidates, cue)
            else -> nearest(candidates, cue)
        }
        val value = values.firstOrNull()
            ?: if (action == InvestmentAction.VALUATION) candidates.firstOrNull() else null

        val currency = amount?.money?.currencyUpper ?: value?.money?.currencyUpper
            ?: occurrences.firstOrNull()?.money?.currencyUpper
            ?: if (InstitutionTable.isDltSender(sender)) "INR" else null
        val amountMinor: Long = when {
            action == InvestmentAction.VALUATION -> if (value == null) return InvestmentOutcome(null) else 0L
            amount != null -> amount.money.amountMinor
            // "Bought 10 ABC @ 2,345.50" with no total: quantity x price.
            units != null && unitPrice != null && currency != null && trade != null -> product(units!!, unitPrice!!, currency) ?: return InvestmentOutcome(null)
            else -> return InvestmentOutcome(null)
        }
        if (currency == null || (amountMinor <= 0 && action != InvestmentAction.VALUATION)) return InvestmentOutcome(null)

        val direction = when (action) {
            InvestmentAction.REDEMPTION, InvestmentAction.SELL -> TransactionDirection.DEBIT
            else -> TransactionDirection.CREDIT
        }
        val masked = ref?.masked
        return InvestmentOutcome(
            ExtractedTransaction(
                direction = direction,
                amountMinor = amountMinor,
                currency = currency,
                instrument = instrument,
                last4 = masked?.filter { it.isDigit() }?.takeLast(4),
                merchant = merchant ?: schemeName(body),
                reference = TransactionParser.referenceOf(body),
                balanceMinor = value?.money?.amountMinor,
                balanceCurrency = value?.money?.currencyUpper,
                institution = InstitutionTable.institutionFor(sender),
                maskedNumber = masked,
                investmentAction = action,
                units = units,
                unitPrice = unitPrice,
                unitsHeld = unitsHeld,
                isin = isinOf(body),
                ownTransfer = action != InvestmentAction.DIVIDEND,
            ),
        )
    }

    /** Whether [body] states a completed step (not "to be processed" / "will be allotted"). */
    private fun isDone(body: String): Boolean = doneWord.findAll(body).any { m ->
        SmsWords.before(body, m.range.first, 1).firstOrNull() !in modalBefore
    }

    private fun actionOf(instrument: InstrumentType, body: String, valuation: Boolean): InvestmentAction? {
        val dividend = idcwWord.containsMatchIn(body) && !(reinvestWord.containsMatchIn(body) && unitsWord.containsMatchIn(body))
        return if (instrument == InstrumentType.MUTUAL_FUND) {
            when {
                dividend -> InvestmentAction.DIVIDEND
                switchWord.containsMatchIn(body) -> InvestmentAction.SWITCH
                redeemWord.containsMatchIn(body) -> InvestmentAction.REDEMPTION
                allotWord.containsMatchIn(body) || purchaseWord.containsMatchIn(body) || reinvestWord.containsMatchIn(body) -> InvestmentAction.PURCHASE
                valuation -> InvestmentAction.VALUATION
                else -> null
            }
        } else {
            val buy = buyWord.find(body)
            val sell = sellWord.find(body)
            when {
                dividend -> InvestmentAction.DIVIDEND
                buy != null && sell != null -> {
                    // A contract note with both sides: the net obligation decides ("payable" by the user = net buy).
                    val receivable = receivableWord.containsMatchIn(body)
                    val payable = payableWord.containsMatchIn(body)
                    when {
                        receivable && !payable -> InvestmentAction.SELL
                        payable && !receivable -> InvestmentAction.BUY
                        buy.range.first <= sell.range.first -> InvestmentAction.BUY
                        else -> InvestmentAction.SELL
                    }
                }
                sell != null -> InvestmentAction.SELL
                buy != null || allotWord.containsMatchIn(body) -> InvestmentAction.BUY
                valuation -> InvestmentAction.VALUATION
                else -> null
            }
        }
    }

    /** Where the action is stated, so the nearest amount is its amount. */
    private fun actionCue(action: InvestmentAction, body: String): IntRange? {
        val regex = when (action) {
            InvestmentAction.DIVIDEND -> idcwWord
            InvestmentAction.SWITCH -> switchWord
            InvestmentAction.REDEMPTION -> redeemWord
            InvestmentAction.PURCHASE -> allotWord.takeIf { it.containsMatchIn(body) } ?: purchaseWord
            InvestmentAction.BUY -> buyWord
            InvestmentAction.SELL -> sellWord
            InvestmentAction.VALUATION -> valuationWord
        }
        return regex.find(body)?.range
    }

    private fun nearest(candidates: List<MoneyOccurrence>, cue: IntRange?): MoneyOccurrence? {
        if (cue == null) return candidates.firstOrNull()
        return candidates.minByOrNull { o ->
            when {
                o.range.last < cue.first -> cue.first - o.range.last
                cue.last < o.range.first -> o.range.first - cue.last
                else -> 0
            }
        }
    }

    /** "Current value Rs 1,20,000", "Valuation of your folio ... as on 20/09/2026: Rs 2,34,567". */
    private fun isValueAmount(body: String, o: MoneyOccurrence): Boolean {
        val before = SmsWords.before(body, o.range.first, 10)
        for ((i, w) in before.withIndex()) {
            if (w in stopWords) return false
            if (w == "valuation") return true
            if (w == "value" && before.getOrNull(i + 1) in valueQualifiers) return true
            if (w in listOf("holdings", "holding", "investments", "portfolio") && before.getOrNull(i + 1) == "of" && before.getOrNull(i + 2) == "value") return true
            if (w in listOf("holdings", "holding", "investments", "portfolio") && before.getOrNull(i + 1) == "your" && before.getOrNull(i + 2) == "of" && before.getOrNull(i + 3) == "value") return true
        }
        return false
    }

    private fun isFee(body: String, o: MoneyOccurrence): Boolean {
        val before = SmsWords.before(body, o.range.first, 3)
        return before.any { it in feeWords }
    }

    /** "NAV Rs 45.67", "@ Rs 45.67", "price Rs 99.50": a per-unit figure, not the transaction amount. */
    private fun isPriceWord(body: String, o: MoneyOccurrence): Boolean {
        val before = SmsWords.before(body, o.range.first, 2)
        if (before.firstOrNull() == "@" || before.any { it == "nav" || it == "price" || it == "rate" }) return true
        val after = SmsWords.after(body, o.range.last + 1, 2)
        return after.firstOrNull() == "per" || after.firstOrNull() == "p.u"
    }

    /** Every folio / demat number in [body], typed. */
    private fun findRefs(body: String): List<InvestmentRef> {
        val out = ArrayList<InvestmentRef>()
        for (m in folioRef.findAll(body)) masked(m.groupValues[1])?.let { out += InvestmentRef(InstrumentType.MUTUAL_FUND, it, m.range) }
        for (pattern in listOf(boRef, clientRef, dematRef)) {
            for (m in pattern.findAll(body)) masked(m.groupValues[1])?.let { out += InvestmentRef(InstrumentType.DEMAT, it, m.range) }
        }
        return out
    }

    /**
     * An investment number as an id: always `XXXX` + the last four digits, however much the SMS shows ("12345678",
     * "XXXX5678", "1234567800015678" all become `XXXX5678`), so one folio / demat account keeps one id across message
     * styles and the full number is never stored. Null with fewer than three digits.
     */
    private fun masked(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 3) return null
        return "XXXX" + digits.takeLast(4)
    }

    private fun isinOf(body: String): String? {
        val keyword = isinWord.find(body)
        if (keyword != null) {
            isinValue.find(body, keyword.range.last + 1)?.let { if (it.range.first - keyword.range.last < 8) return it.groupValues[1] }
        }
        // Without the keyword, only a code that looks like one (a letter-only country prefix and a digit in the body).
        return isinValue.findAll(body).map { it.groupValues[1] }.firstOrNull { code -> code.drop(2).count { it.isDigit() } >= 5 && code.take(2).all { it.isLetter() } }
    }

    /**
     * The scheme's name: the capitalised words ending in "Fund" / "Scheme" / "ETF", plus a "- Direct Growth" style
     * suffix ("XYZ Flexi Cap Fund - Direct Plan - Growth"). Best effort; null when there is none.
     */
    private fun schemeName(body: String): String? {
        for (m in schemeName.findAll(body)) {
            val words = m.groupValues[1].trim().split(' ')
            val cut = words.indexOfLast { w -> w.lowercase() in notNameWords || w.count { it.isDigit() } >= 3 }
            val kept = words.drop(cut + 1)
            if (kept.isEmpty()) continue
            var name = kept.joinToString(" ") + " " + m.groupValues[2]
            schemeSuffix.find(body.substring(m.range.last + 1))?.let { name += it.value }
            if (kept.size == 1 && kept[0].equals("mutual", ignoreCase = true)) continue
            return name.replace(whitespace, " ").trim()
        }
        return null
    }

    private fun plain(raw: String): String? = runCatching { BigDecimal(raw.replace(",", "")).stripTrailingZeros().toPlainString() }.getOrNull()

    private fun product(units: String, price: String, currency: String): Long? = runCatching {
        MoneyParser.parseAmount(BigDecimal(units).multiply(BigDecimal(price)).toPlainString(), currency).amountMinor
    }.getOrNull()
}
