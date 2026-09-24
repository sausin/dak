package app.dak.classify

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Harder OTP shapes: several numbers, expiry text, amounts, times, other digit scripts, alphanumeric codes. */
class OtpExtractorEdgeCasesTest {

    private fun code(body: String) = OtpExtractor.extract(body)?.code

    @Test
    fun `the code is the number the keyword points at, not an amount, card tail, date or phone number`() {
        assertEquals(
            "773201",
            code("OTP for txn of Rs 2,499.00 at SHOP on card XX4411 dated 12/09/2026 is 773201. Call 18002586161 if not you."),
        )
        assertEquals("482913", code("Rs.5000 transfer to A/c XX1234: OTP 482913. Valid 10 mins."))
        assertEquals("552910", code("OTP for your application on the portal is 552910"))
    }

    @Test
    fun `with two codes the one next to the keyword wins`() {
        assertEquals("111222", code("Your OTP is 111222. Ignore the earlier code 333444."))
        assertEquals("998877", code("Ref 12345678. Your verification code is 998877."))
        assertEquals("4455", code("Use 4455 as your one time password. Order 9988 confirmed."))
    }

    /**
     * Bug: the "keyword then code" pattern looked up to 40 characters past the keyword, across a full stop, so a
     * number in the next sentence ("Valid for 1800 seconds", "Order 9988 confirmed") was returned instead of the
     * code written before the keyword - and the notification's copy button copied the wrong number.
     */
    @Test
    fun `a number in the sentence after the keyword never replaces a code written before it`() {
        assertEquals("5521", code("Use 5521 as your one time password. Valid for 1800 seconds."))
        assertEquals("482913", code("482913 is your OTP. Valid till 2359 hrs."))
        assertEquals("482913", code("482913 is your OTP! Ref 7788 for queries"))
        // The keyword-then-code reading still works across abbreviations and within one sentence.
        assertEquals("773201", code("OTP for A/c no. XX1234 txn is 773201"))
        assertEquals("482913", code("Your OTP is 482913. Valid till 2359 hrs."))
    }

    @Test
    fun `expiry and validity text never becomes the code`() {
        assertEquals("482913", code("482913 is your OTP. Valid for 10 minutes till 10:45 PM."))
        assertEquals("482913", code("Your OTP is 482913 and it expires in 3 mins."))
        assertEquals("6021", code("OTP: 6021 (valid for 2026 seconds? no, for 180 seconds)"))
    }

    @Test
    fun `alphanumeric and prefixed codes`() {
        assertEquals("A1B2C3", code("Your login code: A1B2C3"))
        assertEquals("123456", code("G-123456 is your Google verification code."))
        // A code needs a digit: an all-letter word near the keyword is not one.
        assertNull(code("Your OTP is READY for pickup"))
    }

    @Test
    fun `codes written in any digit script come back as ASCII`() {
        val bengali = "আপনার OTP হল ৪৮২৯১৩"
        assertEquals("482913", code(bengali))
        assertEquals("482913", code("Your OTP is ４８２９１３")) // full-width
        assertEquals("482913", code("رمز التحقق الخاص بك هو ٤٨٢٩١٣")) // Arabic-Indic
        assertEquals("482913", code("Your OTP is ۴۸۲۹۱۳")) // Extended Arabic-Indic (Persian/Urdu)
        assertEquals("482913", code("உங்கள் OTP ௪௮௨௯௧௩")) // Tamil
    }

    @Test
    fun `too short or too long numbers are not codes`() {
        assertNull(code("Your OTP is 123"))
        assertNull(code("Your OTP is 123456789012"))
        assertEquals("1234", code("Your OTP is 1234"))
        assertEquals("12345678", code("Your OTP is 12345678"))
    }

    @Test
    fun `retriever hash and WebOTP tails`() {
        val both = OtpExtractor.extract("<#> 482913 is your OTP.\n@shop.example.in #482913")
        assertEquals("482913", both?.code)
        assertEquals("shop.example.in", both?.webOtpDomain)
        assertNull(both?.retrieverHash)
        // A hash with no code is not a usable OTP.
        assertNull(OtpExtractor.extract("Welcome back!\nFRAdOgxjeqz"))
        // A WebOTP line whose code has no digit is ignored; the body's code is used.
        assertEquals("482913", OtpExtractor.extract("Your OTP is 482913\n@shop.example.in #abcdef")?.code)
        // An 11-char token glued to the previous word is not a hash.
        assertNull(OtpExtractor.extract("Your OTP is 482913 codeFRAdOgxjeqz")?.retrieverHash)
    }

    @Test
    fun `no keyword, no code`() {
        assertNull(code("Meet me at 1830 near gate 4455"))
        assertNull(code("Your order 482913 has shipped"))
    }

    @Test
    fun `never throws and returns an ASCII code that occurs in the text on random input`() {
        val r = Random(424242)
        val parts = listOf(
            "OTP", "otp", "is", "your", "code", ":", "pin", "use", "as", "the", "verification code", "Rs", "₹", "XX",
            "@x.com", "#", "\n", " ", ".", "-", "/", "ओटीपी", "है", "رمز التحقق", "valid", "for", "mins",
        )
        repeat(5_000) {
            val sb = StringBuilder()
            repeat(r.nextInt(1, 20)) {
                if (r.nextInt(3) == 0) {
                    val digits = (1..r.nextInt(1, 12)).map { '0' + r.nextInt(10) }.joinToString("")
                    sb.append(if (r.nextInt(8) == 0) digits.map { '०' + (it - '0') }.joinToString("") else digits)
                } else {
                    sb.append(parts[r.nextInt(parts.size)])
                }
                sb.append(' ')
            }
            val body = sb.toString()
            val info = OtpExtractor.extract(body) ?: return@repeat
            val normalized = DigitNormalizer.normalizeDigits(body)
            assertTrue(info.code.all { it.code < 128 }, "non-ASCII code ${info.code} from: $body")
            // A six-digit code may be written in two halves ("123-456", "123 456"), returned joined.
            val halves = if (info.code.length == 6) listOf("-", " ").map { info.code.substring(0, 3) + it + info.code.substring(3) } else emptyList()
            assertTrue(info.code in normalized || halves.any { it in normalized }, "code ${info.code} not in: $body")
            assertTrue(info.code.any { it.isDigit() }, "code without a digit ${info.code} from: $body")
        }
    }
}
