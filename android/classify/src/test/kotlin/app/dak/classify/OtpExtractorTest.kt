package app.dak.classify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtpExtractorTest {

    @Test
    fun `otp before keyword`() {
        val info = OtpExtractor.extract("123456 is your OTP for login. Valid for 10 minutes.")
        assertEquals("123456", info?.code)
    }

    @Test
    fun `otp after keyword`() {
        val info = OtpExtractor.extract("Your OTP is 482910 for txn of Rs 2500 at Amazon.")
        assertEquals("482910", info?.code)
    }

    @Test
    fun `generic code label`() {
        assertEquals("1234", OtpExtractor.extract("Your code: 1234 to continue")?.code)
        assertEquals("A1B2", OtpExtractor.extract("Enter code: A1B2 to verify")?.code)
    }

    @Test
    fun `verification code phrase`() {
        assertEquals("903214", OtpExtractor.extract("Your verification code is 903214. Never share it.")?.code)
    }

    @Test
    fun `hindi phrasing`() {
        assertEquals("665544", OtpExtractor.extract("आपका ओटीपी 665544 है कृपया साझा न करें")?.code)
        assertEquals("112233", OtpExtractor.extract("आपका सत्यापन कोड 112233 है")?.code)
    }

    @Test
    fun `ignores amounts dates and masked account tails`() {
        assertNull(OtpExtractor.extract("Rs 4500.00 debited from A/c XX1234 on 12-03-24. Avl bal Rs 12,340.00"))
        assertNull(OtpExtractor.extract("Your flight departs on 12/03/2024 at gate 45"))
    }

    @Test
    fun `ignores phone numbers with no otp keyword`() {
        assertNull(OtpExtractor.extract("Call us at 9876543210 for support"))
    }

    @Test
    fun `extracts trailing sms retriever hash`() {
        val info = OtpExtractor.extract("123456 is your OTP. Do not share.\nFRAdOgxjeqz")
        assertEquals("123456", info?.code)
        assertEquals("FRAdOgxjeqz", info?.retrieverHash)
    }

    @Test
    fun `does not treat a pure digit trailing token as a retriever hash`() {
        val info = OtpExtractor.extract("Your OTP is 445566, valid for 10 min.\n98765432109")
        assertEquals("445566", info?.code)
        assertNull(info?.retrieverHash)
    }

    @Test
    fun `extracts webotp trailing line`() {
        val info = OtpExtractor.extract("Your OTP is 998877 for login.\n@example.com #998877")
        assertEquals("998877", info?.code)
        assertEquals("example.com", info?.webOtpDomain)
    }

    @Test
    fun `returns null when there is no code at all`() {
        assertNull(OtpExtractor.extract("Thanks for shopping with us, see you again soon."))
    }
}
