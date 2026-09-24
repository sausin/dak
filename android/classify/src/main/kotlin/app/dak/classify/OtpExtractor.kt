package app.dak.classify

import app.dak.classify.text.AnalysisText
import app.dak.classify.text.GatedRegex
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
    private val webOtpRegex = GatedRegex("""@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\s+#(\S+)\s*$""")

    // 11-char SMS Retriever hash: base64-url-ish alphabet, at the very end of the message.
    private val retrieverHashRegex = Regex("""([A-Za-z0-9+/]{11})\s*$""")

    // A run of digits directly preceded by masking characters (XX1234) - never an OTP.
    private val maskedTailRegex = Regex("""(?i)\b[x*]{2,}\d{2,6}\b""")

    // Amount markers: Rs, INR, ₹, $ followed by digits - never an OTP.
    private val amountRegex = GatedRegex("""(?i)(₹|rs\.?|inr|usd|\$)\s*[\d,]+(\.\d+)?""")

    // Full phone numbers (10+ digits, possibly with +country code) - never an OTP on their own.
    private val phoneRegex = Regex("""(?<!\d)(\+?\d{1,3}[\s-]?)?\d{10}(?!\d)""")

    // Dates like 12/03/2024, 12-03-24, 2024-03-12.
    private val dateRegex = Regex("""\b\d{1,4}[/-]\d{1,2}[/-]\d{1,4}\b""")

    private val otpKeywordEn = "otp|one[- ]?time password|verification code|security code|passcode|auth(?:entication)? code|login code"
    private val otpKeywordHi = "ओटीपी|सत्यापन कोड|वन टाइम पासवर्ड"

    // Arabic (Gulf / MENA banks): "verification code", "activation code", "one-time password / code".
    private val otpKeywordAr = "رمز التحقق|رمز التفعيل|كلمة المرور لمرة واحدة|رمز لمرة واحدة|الرمز السري المؤقت"

    // German, French, Spanish, Italian and Dutch: "Ihr Bestätigungscode lautet 482913", "votre code de sécurité est 731904",
    // "su código de verificación es 482913", "il codice di verifica è 482913", "uw verificatiecode is 482913".
    private val otpKeywordEu =
        "best[äa]tigungscode|sicherheitscode|verifizierungscode|einmalpasswort|einmalcode|anmeldecode|" +
            "code de s[ée]curit[ée]|code de v[ée]rification|code de confirmation|mot de passe [àa] usage unique|" +
            "c[óo]digo de verificaci[óo]n|c[óo]digo de seguridad|c[óo]digo de confirmaci[óo]n|" +
            "codice di verifica|codice di sicurezza|codice di conferma|verificatiecode|beveiligingscode|bevestigingscode"

    // "OTP is 123456", "OTP: 123456", "your OTP for login is 123456", "OTP for ADCB login is 348201" (the code must
    // contain a digit, so a brand name in between is skipped rather than ending the search).
    private val codeAfterKeyword = GatedRegex(
        """(?i)(?:$otpKeywordEn|$otpKeywordHi|$otpKeywordAr|$otpKeywordEu)[^\n]{0,40}?\b((?=[A-Z]*\d)[A-Z0-9]{4,8})\b""",
    )

    // "123456 is your OTP", "123456 is the verification code", "G-123456 is your Google verification code"
    private val codeBeforeKeyword = GatedRegex(
        """\b([A-Z0-9]{4,8})\b[^0-9A-Za-z]{0,15}?is\s+(?:your|the)?\s*(?:[\p{L}\d&'.-]{1,24}\s+){0,3}?(?:$otpKeywordEn)""",
        RegexOption.IGNORE_CASE,
    )

    // "OTP for txn of Rs 2,499 at SHOP on card XX4411 is 773201", "OTP for your application on the portal is 552910":
    // a longer window, but only up to an explicit "is" right before the code.
    private val codeAfterKeywordIs = GatedRegex(
        """(?i)(?:$otpKeywordEn|$otpKeywordHi)[^\n]{0,90}?\bis\s*:?\s*((?=[A-Z]*\d)[A-Z0-9]{4,8})\b""",
    )

    // "Use 5521 as your one time password", "Enter 482913 as the verification code"
    private val codeAsKeyword = GatedRegex(
        """\b(?:use|enter)\s+([A-Z0-9]{4,8})\s+(?:as|is)\s+(?:your|the)?\s*(?:[\p{L}\d&'.-]{1,24}\s+){0,3}?(?:$otpKeywordEn)""",
        RegexOption.IGNORE_CASE,
    )

    // "771204 is your code to confirm a payment", "4821 is your Uber code": a bare "code" needs digits and "is your".
    private val digitsAreYourCode = GatedRegex("""(?i)\b(\d{4,8})\s+is\s+your\s+(?:\p{L}+\s+){0,2}?code\b""")

    // "Your WhatsApp code: 123-456", "code 123 456": a six-digit code written in two halves (returned joined).
    private val splitCode = GatedRegex("""(?i)\b(?:code|otp|pin|passcode)\b[^\n\d]{0,20}?(?<!\d[- ]?)(\d{3})[- ](\d{3})(?![- ]?\d)""")

    // "code: 1234", "pin: 1234", "code is 1234"
    private val genericCode = GatedRegex("""(?i)\b(?:code|pin)\s*(?:is|:)\s*([A-Z0-9]{4,8})\b""")

    /** The keyword-gated patterns, for the prefilter equivalence test. */
    internal val gatedPatterns: List<GatedRegex> get() =
        listOf(webOtpRegex, amountRegex, codeAfterKeyword, codeBeforeKeyword, codeAfterKeywordIs, codeAsKeyword, digitsAreYourCode, splitCode, genericCode)

    /** Attempts to extract OTP info from [body]. Returns null if no OTP-shaped code is found. */
    public fun extract(rawBody: String): OtpInfo? {
        // Normalise non-ASCII decimal digits (Devanagari, Bengali, Arabic-Indic, full-width, ...)
        // up front so every `\d`/digit check below matches regardless of script, and so any code
        // returned is always ASCII digits (copy/autofill needs ASCII, not e.g. Devanagari ०-९).
        // The analysis form (AnalysisText): RTL overrides applied, invisible characters dropped, zalgo capped.
        val body = DigitNormalizer.normalizeDigits(AnalysisText.of(rawBody))
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

        // "Use 5521 as your one time password. Valid for 1800 seconds.": a number after the keyword but in the next
        // sentence is only the code when nothing names one more directly.
        val afterKeyword = firstValidMatch(codeAfterKeyword, masked)
        val afterCode = afterKeyword?.groupValues?.get(1)
        if (afterKeyword != null && !crossesSentence(masked, afterKeyword)) return afterCode
        firstValidCode(codeBeforeKeyword, masked)?.let { return it }
        firstValidCode(codeAfterKeywordIs, masked)?.let { return it }
        firstValidCode(codeAsKeyword, masked)?.let { return it }
        firstValidCode(digitsAreYourCode, masked)?.let { return it }
        splitCode.find(masked)?.let { return it.groupValues[1] + it.groupValues[2] }
        firstValidCode(genericCode, masked)?.let { return it }
        return afterCode
    }

    /** A full stop, `!` or `?` followed by a blank between the keyword and the code of [match]. */
    private val sentenceEnd = Regex("""[.!?]\s""")

    private fun crossesSentence(text: String, match: MatchResult): Boolean {
        val codeStart = match.groups[1]?.range?.first ?: return false
        return sentenceEnd.containsMatchIn(text.subSequence(match.range.first, codeStart))
    }

    private fun firstValidCode(regex: GatedRegex, masked: String): String? = firstValidMatch(regex, masked)?.groupValues?.get(1)

    private fun firstValidMatch(regex: GatedRegex, masked: String): MatchResult? {
        for (match in regex.findAll(masked)) {
            if (normalizeCode(match.groupValues[1]) != null) return match
        }
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
