package app.dak.classify

import app.dak.classify.scam.AccountHint
import app.dak.classify.scam.FakeCreditDetector
import app.dak.classify.scam.ScamLevel
import app.dak.classify.scam.ScamReason
import app.dak.core.model.Category
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Messages users actually reported (names and numbers changed), checked on the classify side. The courier and
 * call-alert reports (Blue Dart, Shift, Xpressbees, "available to take calls") are in [LogisticsAndCallAlertTest].
 */
class RealWorldRegressionTest {

    private val templates = TemplateBundle.loadDefault()
    private val detector = FakeCreditDetector(templates)
    private val india = ClassifierPipeline(templates, NaiveBayesModel.loadDefault(), regionFor = { SenderRegion.INDIA })
    private val now = 1_758_000_000_000L

    private val auCredit =
        "Credited INR 50,000.00 to A/c X5073 on 06-AUG-2026 Ref IMPS-62181 -ABC XYZ -SBIN. Bal INR 1,00,000.00.\n-AU Bank"
    private val auBeneficiaryNeft =
        "Confirmation! INR 100,000.00 credited to beneficiary A/c XX5632 for your NEFT on 07-Dec-2024 at 08:02 PM. Ref N34224216. - AU BANK"

    /** Commit 003a547: the single-X mask "A/c X5073" is AU Bank's account number. */
    @Test
    fun `AU Bank X5073 credit is a genuine transaction and matches the user's account`() {
        assertEquals(Category.TRANSACTION, runBlocking { india.classify("AX-AUBANK-S", auCredit, 1) }.category)
        assertEquals("5073", FakeCreditDetector.maskIn(auCredit))
        assertEquals(ScamLevel.NONE, detector.evaluate("AX-AUBANK-S", auCredit, dateMillis = now).level)
        // Even from a header the bundle does not vouch for, the account is recognised as the user's.
        val known = setOf(AccountHint("AU Small Finance Bank", "5073"))
        val v = detector.evaluate("AX-AUSFBK-S", auCredit, knownAccounts = known, dateMillis = now)
        assertFalse(ScamReason.UNKNOWN_ACCOUNT in v.reasons, v.toString())
    }

    /** Commit 80b0844: the payee's account in a NEFT confirmation is not an account the user lacks. */
    @Test
    fun `AU Bank beneficiary NEFT confirmation is not a fake credit to an unknown account`() {
        assertEquals(Category.TRANSACTION, runBlocking { india.classify("AX-AUBANK-S", auBeneficiaryNeft, 1) }.category)
        val known = setOf(AccountHint("AU Small Finance Bank", "5073"))
        assertEquals(ScamLevel.NONE, detector.evaluate("AX-AUBANK-S", auBeneficiaryNeft, knownAccounts = known, dateMillis = now).level)
        val unknownHeader = detector.evaluate("AX-AUSFBK-S", auBeneficiaryNeft, knownAccounts = known, dateMillis = now)
        assertFalse(ScamReason.UNKNOWN_ACCOUNT in unknownHeader.reasons, unknownHeader.toString())
    }

    /** Commit 8c45aff, as the user saw it: the OTP copy chip and categories on the real message shapes. */
    @Test
    fun `carrier call alert carries no OTP even though it has a long number`() {
        val c = runBlocking { india.classify("JM-JIOSVC-S", "Dear Customer, +919999999999 is now available to take calls.", 1) }
        assertEquals(Category.PERSONAL, c.category)
        assertEquals(null, c.otp)
    }

    /** Scam reason codes are stored in index labels (`scam-reason:<code>`): they must never change. */
    @Test
    fun `scam reason codes are stable`() {
        val golden = mapOf(
            ScamReason.CREDIT_ALERT_FROM_PHONE_NUMBER to "phone-credit-alert",
            ScamReason.UNKNOWN_SENDER_ALERT to "unknown-sender-alert",
            ScamReason.DEBIT_ALERT_FROM_PHONE_NUMBER to "phone-debit-alert",
            ScamReason.PHONE_NUMBER_CLAIMS_BANK to "phone-claims-bank",
            ScamReason.LOOKALIKE_SENDER to "lookalike-sender",
            ScamReason.MIXED_SCRIPT_SENDER to "mixed-script-sender",
            ScamReason.UNVERIFIED_SENDER to "unverified-sender",
            ScamReason.UNPREFIXED_BANK_HEADER to "unprefixed-header",
            ScamReason.PROMOTIONAL_ROUTE to "promotional-route",
            ScamReason.BRAND_MISMATCH to "brand-mismatch",
            ScamReason.UNKNOWN_ACCOUNT to "unknown-account",
            ScamReason.NO_ACCOUNT_AT_BANK to "no-account-at-bank",
            ScamReason.RETURN_REQUEST to "return-request",
            ScamReason.MOBILE_NUMBER_IN_ALERT to "mobile-in-alert",
            ScamReason.PAYMENT_HANDLE_WITH_RETURN to "payment-handle",
            ScamReason.LINK_IN_ALERT to "link-in-alert",
            ScamReason.PIN_TO_RECEIVE to "pin-to-receive",
            ScamReason.COLLECT_REQUEST to "collect-request",
            ScamReason.FOLLOW_UP_AFTER_CREDIT to "follow-up",
            ScamReason.RETURN_AFTER_GENUINE_CREDIT to "return-after-credit",
        )
        assertEquals(ScamReason.entries.toSet(), golden.keys, "a reason was added: give it a code and pin it here")
        for ((reason, code) in golden) {
            assertEquals(code, reason.code)
            assertEquals(reason, ScamReason.fromCode(code))
        }
        assertEquals(null, ScamReason.fromCode("from-a-newer-version"))
        assertEquals(ScamReason.entries.size, ScamReason.entries.map { it.code }.distinct().size)
    }
}
