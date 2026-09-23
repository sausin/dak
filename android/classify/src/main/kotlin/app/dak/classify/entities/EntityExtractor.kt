package app.dak.classify.entities

import app.dak.classify.DigitNormalizer
import app.dak.classify.LinkExtractor
import app.dak.classify.OtpExtractor
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.Leniency
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import java.util.Locale

/**
 * Finds typed, actionable spans in an SMS body (OTP, URL, email, account mask, amount, UPI id, PNR, tracking and
 * reference numbers, phone numbers) instead of treating every digit run as a phone link.
 *
 * Pure, offline and bounded: only the first [MAX_CHARS] chars are scanned, every pattern is ReDoS-safe
 * ([EntityPatterns]), and phone numbers come from libphonenumber's `findNumbers` at `Leniency.VALID` for the SIM's
 * region, so OTPs, amounts, dates and short codes are not phone numbers. Overlaps resolve by [EntityType] order.
 */
public object EntityExtractor {

    /** Only this many leading chars are scanned. */
    public const val MAX_CHARS: Int = 4_000

    /** Upper bound on libphonenumber's internal attempts per body. */
    private const val PHONE_MAX_TRIES = 50L

    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** Well-known UPI handles (PSP suffixes): a candidate with one of these is a UPI id without further context. */
    private val UPI_HANDLES: Set<String> = setOf(
        "ybl", "ibl", "axl", "apl", "yapl", "rapl", "upi", "paytm", "ptyes", "ptaxis", "pthdfc", "ptsbi",
        "okhdfcbank", "okicici", "oksbi", "okaxis", "axisbank", "axisb", "icici", "hdfcbank", "sbi", "kotak", "kmbl",
        "idfcbank", "idfcfirst", "federal", "fbl", "indus", "pnb", "boi", "barodampay", "unionbank", "uboi", "cnrb",
        "ikwik", "freecharge", "jupiteraxis", "waicici", "waaxis", "wahdfcbank", "wasbi", "rbl", "yesbank", "yesbankltd",
        "aubank", "dbs", "citi", "hsbc", "sc", "airtel", "jio", "slice", "fam", "abfspay", "niyoicici", "timecosmos",
        "pingpay", "amazonpay", "ratn", "idbi", "centralbank", "indianbank", "iob", "mahb", "kbl", "equitas", "dlb",
        "tjsb", "cub", "kvb", "sib", "hsbcbank", "postbank", "airtelpaymentsbank", "nsdl", "superyes", "goaxb",
    )

    /**
     * Extracts entity spans from [body], sorted by position, never overlapping.
     *
     * @param regionIso ISO 3166 country of the SIM the message arrived on (e.g. "IN"); numbers without a `+` country
     *   code are read as that country's. Null: only `+`-prefixed numbers are phone numbers.
     * @param hints spans the caller already found (e.g. `MoneyParser` amounts as [EntityType.AMOUNT]). When no
     *   AMOUNT hint is given, a minimal built-in rupee matcher finds amounts so they are never linked as phones.
     * @param otpCode the OTP the index already extracted, if any; otherwise [OtpExtractor] runs here.
     */
    public fun extract(
        body: String,
        regionIso: String?,
        hints: List<EntityHint> = emptyList(),
        otpCode: String? = null,
    ): List<EntitySpan> {
        if (body.isEmpty()) return emptyList()
        val text = if (body.length > MAX_CHARS) body.substring(0, MAX_CHARS) else body
        // Non-ASCII digits (Devanagari, Arabic-Indic...) fold 1:1 to ASCII, so ranges stay valid in the original.
        val digits = DigitNormalizer.normalizeDigits(text)
        val candidates = ArrayList<EntitySpan>()

        otp(text, digits, otpCode)?.let { candidates += it }
        urls(text, candidates)
        simple(EntityPatterns.email, text, digits, EntityType.EMAIL, group = 0, candidates) { it.lowercase(Locale.ROOT) }
        masks(text, digits, candidates)
        amounts(text, digits, hints, candidates)
        upis(text, digits, candidates)
        simple(EntityPatterns.pnr, text, digits, EntityType.PNR, group = 1, candidates) { it }
        trackings(text, digits, candidates)
        references(text, digits, candidates)
        for (hint in hints) {
            if (hint.type == EntityType.AMOUNT) continue
            span(text, hint.type, hint.start, hint.end, hint.value)?.let { candidates += it }
        }
        phones(text, digits, regionIso, candidates)

        return resolve(candidates)
    }

    private fun otp(text: String, digits: String, known: String?): EntitySpan? {
        val code = known ?: OtpExtractor.extract(text)?.code ?: return null
        var from = 0
        while (from < digits.length) {
            val at = digits.indexOf(code, from)
            if (at < 0) return null
            val end = at + code.length
            val before = if (at > 0) digits[at - 1] else ' '
            val after = if (end < digits.length) digits[end] else ' '
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return span(text, EntityType.OTP, at, end, code)
            from = at + 1
        }
        return null
    }

    private fun urls(text: String, out: MutableList<EntitySpan>) {
        var from = 0
        for (link in LinkExtractor.extract(text)) {
            val at = text.indexOf(link.raw, from)
            if (at < 0) continue
            from = at + link.raw.length
            span(text, EntityType.URL, at, from, link.raw)?.let { out += it }
        }
    }

    private fun masks(text: String, digits: String, out: MutableList<EntitySpan>) {
        for (m in EntityPatterns.maskedAccount.findAll(digits)) {
            span(text, EntityType.MASKED_ACCOUNT, m.range.first, m.range.last + 1, m.groupValues[1])?.let { out += it }
        }
        for (m in EntityPatterns.accountEnding.findAll(digits)) {
            val g = m.groups[1] ?: continue
            span(text, EntityType.MASKED_ACCOUNT, g.range.first, g.range.last + 1, g.value)?.let { out += it }
        }
    }

    private fun amounts(text: String, digits: String, hints: List<EntityHint>, out: MutableList<EntitySpan>) {
        val given = hints.filter { it.type == EntityType.AMOUNT }
        if (given.isNotEmpty()) {
            for (h in given) span(text, EntityType.AMOUNT, h.start, h.end, h.value)?.let { out += it }
            return
        }
        for (m in EntityPatterns.amount.findAll(digits)) {
            val value = m.groupValues[1].replace(",", "")
            span(text, EntityType.AMOUNT, m.range.first, m.range.last + 1, value)?.let { out += it }
        }
    }

    private fun upis(text: String, digits: String, out: MutableList<EntitySpan>) {
        for (m in EntityPatterns.upi.findAll(digits)) {
            val handle = m.groupValues[2].lowercase(Locale.ROOT)
            val before = digits.substring(maxOf(0, m.range.first - 24), m.range.first)
            if (handle !in UPI_HANDLES && !EntityPatterns.upiContext.containsMatchIn(before)) continue
            span(text, EntityType.UPI_ID, m.range.first, m.range.last + 1, m.value.lowercase(Locale.ROOT))?.let { out += it }
        }
    }

    private fun trackings(text: String, digits: String, out: MutableList<EntitySpan>) {
        var courier: String? = null
        var courierLooked = false
        for (m in EntityPatterns.tracking.findAll(digits)) {
            val g = m.groups[1] ?: continue
            if (g.value.none { it.isDigit() }) continue
            if (!courierLooked) {
                courier = Couriers.find(digits)
                courierLooked = true
            }
            span(text, EntityType.TRACKING, g.range.first, g.range.last + 1, g.value.uppercase(Locale.ROOT), courier)?.let { out += it }
        }
    }

    private fun references(text: String, digits: String, out: MutableList<EntitySpan>) {
        for (m in EntityPatterns.reference.findAll(digits)) {
            val g = m.groups[1] ?: continue
            val id = g.value.trimEnd('-')
            if (id.length < 6 || id.none { it.isDigit() }) continue
            span(text, EntityType.REFERENCE, g.range.first, g.range.first + id.length, id)?.let { out += it }
        }
    }

    private fun phones(text: String, digits: String, regionIso: String?, out: MutableList<EntitySpan>) {
        val region = regionIso?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 } ?: "ZZ"
        val matches = try {
            phoneUtil.findNumbers(digits, region, Leniency.VALID, PHONE_MAX_TRIES)
        } catch (e: RuntimeException) {
            return
        }
        for (match in matches) {
            val start = match.start()
            val end = match.end()
            val before = digits.substring(maxOf(0, start - 24), start)
            // "A/c 123456789012", "Customer ID 9876543210", "Ref no 9123456789": some other number, not a phone.
            if (EntityPatterns.nonPhoneContext.containsMatchIn(before)) continue
            // A mask ("XX9876543210") or a currency right before the digits: not a phone either.
            val prev = before.trimEnd().lastOrNull()
            if (prev != null && (prev == 'X' || prev == 'x' || prev == '*' || prev == '₹')) continue
            val e164 = try {
                phoneUtil.format(match.number(), PhoneNumberFormat.E164)
            } catch (e: RuntimeException) {
                continue
            }
            span(text, EntityType.PHONE, start, end, e164)?.let { out += it }
        }
    }

    private inline fun simple(
        regex: Regex,
        text: String,
        digits: String,
        type: EntityType,
        group: Int,
        out: MutableList<EntitySpan>,
        value: (String) -> String,
    ) {
        for (m in regex.findAll(digits)) {
            val g = m.groups[group] ?: continue
            span(text, type, g.range.first, g.range.last + 1, value(g.value))?.let { out += it }
        }
    }

    private fun span(text: String, type: EntityType, start: Int, end: Int, value: String, courier: String? = null): EntitySpan? {
        if (start < 0 || end > text.length || start >= end) return null
        return EntitySpan(type, start, end, text.substring(start, end), value, courier)
    }

    /** Keeps the highest-priority span wherever candidates overlap; returns spans sorted by start. */
    private fun resolve(candidates: List<EntitySpan>): List<EntitySpan> {
        val ordered = candidates.sortedWith(compareBy<EntitySpan>({ it.type.ordinal }, { it.start }, { -(it.end - it.start) }))
        val kept = ArrayList<EntitySpan>(ordered.size)
        for (c in ordered) {
            if (kept.none { it.start < c.end && c.start < it.end }) kept += c
        }
        return kept.sortedBy { it.start }
    }
}
