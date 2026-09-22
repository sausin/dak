package app.dak.classify

import app.dak.core.model.OtpInfo

/**
 * Extracts one-time-password codes (and related metadata) from an SMS body.
 *
 * Handles plain digit codes (4-8 digits), labelled alphanumeric codes ("A1B2C3"), English and
 * Hindi phrasing, a trailing 11-character SMS Retriever app hash, and a trailing WebOTP line
 * (`@domain.com #123456`). Deliberately avoids matching amounts, dates, phone numbers and masked
 * account/card tails (`XX1234`, `xxxx1234`).
 */
public object OtpExtractor {

    // Trailing WebOTP line, e.g. "@example.com #123456" possibly on its own line at the end.
    private val webOtpRegex = Regex("""@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\s+#(\S+)\s*$""")

    // 11-char SMS Retriever hash: base64-url-ish alphabet, at the very end of the message.
    private val retrieverHashRegex = Regex("""([A-Za-z0-9+/]{11})\s*$""")

    // A run of digits directly preceded by masking characters (XX1234) - never an OTP.
    private val maskedTailRegex = Regex("""(?i)\b[x*]{2,}\d{2,6}\b""")

    // Amount markers: Rs, INR, ₹, $ followed by digits - never an OTP.
    private val amountRegex = Regex("""(?i)(₹|rs\.?|inr|usd|\$)\s*[\d,]+(\.\d+)?""")

    // Full phone numbers (10+ digits, possibly with +country code) - never an OTP on their own.
    private val phoneRegex = Regex("""(?<!\d)(\+?\d{1,3}[\s-]?)?\d{10}(?!\d)""")

    // Dates like 12/03/2024, 12-03-24, 2024-03-12.
    private val dateRegex = Regex("""\b\d{1,4}[/-]\d{1,2}[/-]\d{1,4}\b""")

    private val otpKeywordEn = "otp|one[- ]?time password|verification code|security code|passcode|auth(entication)? code|login code"
    private val otpKeywordHi = "ओटीपी|सत्यापन कोड|वन टाइम पासवर्ड"

    // "OTP is 123456", "OTP: 123456", "your OTP for login is 123456"
    private val codeAfterKeyword = Regex(
        """(?i)(?:$otpKeywordEn|$otpKeywordHi)[^0-9A-Za-z]{0,40}?\b([A-Z0-9]{4,8})\b""",
    )

    // "123456 is your OTP", "123456 is the verification code"
    private val codeBeforeKeyword = Regex(
        """\b([A-Z0-9]{4,8})\b[^0-9A-Za-z]{0,15}?is\s+(?:your|the)?\s*(?:$otpKeywordEn)""",
        RegexOption.IGNORE_CASE,
    )

    // "code: 1234", "pin: 1234", "code is 1234"
    private val genericCode = Regex("""(?i)\b(?:code|pin)\s*(?:is|:)\s*([A-Z0-9]{4,8})\b""")

    /** Attempts to extract OTP info from [body]. Returns null if no OTP-shaped code is found. */
    public fun extract(body: String): OtpInfo? {
        val webOtp = webOtpRegex.find(body)
        val webOtpDomain = webOtp?.groupValues?.get(1)
        val webOtpCode = webOtp?.groupValues?.get(2)?.takeIf { it.any { c -> c.isDigit() } }

        // Strip the WebOTP tail before hunting for a retriever hash / body code, so it never
        // double-matches as either.
        val withoutWebOtp = if (webOtp != null) body.substring(0, webOtp.range.first) else body

        val retrieverHash = findRetrieverHash(withoutWebOtp)
        val bodyForCode = if (retrieverHash != null) {
            withoutWebOtp.trimEnd().dropLast(11)
        } else {
            withoutWebOtp
        }

        val code = webOtpCode ?: findCode(bodyForCode)
        if (code == null && retrieverHash == null && webOtpDomain == null) return null
        if (code == null) return null // hash/domain alone without a code is not a usable OtpInfo

        return OtpInfo(code = code, retrieverHash = retrieverHash, webOtpDomain = webOtpDomain)
    }

    private fun findRetrieverHash(body: String): String? {
        val trimmed = body.trimEnd()
        val match = retrieverHashRegex.find(trimmed) ?: return null
        val candidate = match.groupValues[1]
        // Must be preceded by whitespace or start-of-string (a standalone token), and contain at
        // least one letter (a pure 11-digit number is far more likely to be a phone/account number).
        val start = match.range.first
        val precededByBoundary = start == 0 || trimmed[start - 1].isWhitespace()
        if (!precededByBoundary) return null
        if (candidate.all { it.isDigit() }) return null
        return candidate
    }

    private fun findCode(body: String): String? {
        // Mask out amounts, dates, masked-tail account references and bare phone numbers so they
        // can never be picked up as a "code before/after keyword" or generic code.
        val masked = buildString(body.length) {
            var i = 0
            val spans = sortedSpans(body)
            for (span in spans) {
                if (i < span.first) append(body, i, span.first)
                append("\u0000".repeat(span.last - span.first + 1))
                i = span.last + 1
            }
            if (i < body.length) append(body, i, body.length)
        }

        codeAfterKeyword.find(masked)?.let { return normalizeCode(it.groupValues[1]) }
        codeBeforeKeyword.find(masked)?.let { return normalizeCode(it.groupValues[1]) }
        genericCode.find(masked)?.let { return normalizeCode(it.groupValues[1]) }
        return null
    }

    private fun normalizeCode(raw: String): String? {
        // Reject pure-alpha "codes" that are just English words caught by a loose keyword window.
        if (raw.all { it.isLetter() }) return null
        return raw
    }

    private fun sortedSpans(body: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        maskedTailRegex.findAll(body).forEach { spans += it.range }
        amountRegex.findAll(body).forEach { spans += it.range }
        dateRegex.findAll(body).forEach { spans += it.range }
        phoneRegex.findAll(body).forEach { spans += it.range }
        return spans.sortedBy { it.first }
    }
}
