package app.dak.classify.scam

import app.dak.classify.TemplateBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Corpus tests for [FakeCreditDetector]: realistic (masked) genuine alerts from DLT headers must never be flagged,
 * and the common fake-credit / return / collect scams must be.
 */
class FakeCreditDetectorTest {

    private val detector = FakeCreditDetector(TemplateBundle.loadDefault())
    private val now = 1_758_000_000_000L
    private val hour = 60L * 60 * 1000

    private data class Sample(val address: String, val body: String, val contact: Boolean = false)

    /** Genuine messages: zero LIKELY_SCAM allowed, and none should even be SUSPICIOUS. */
    private val genuine = listOf(
        Sample("VM-HDFCBK-S", "Money Received - INR 5,000.00 in your HDFC Bank A/c xx1234 on 12-09-26 by A/c linked to VPA rahul.k@okicici (UPI Ref No 426312345678). Not you? Call 18002586161 -HDFC Bank"),
        Sample("AD-SBIINB-T", "Dear UPI user A/C X1234 credited by Rs.2500.00 on date 11Sep26 trf from RAMESH K Refno 426198765432. If not u? call 1800111109. -SBI"),
        Sample("VM-SBIPSG", "Your a/c no. XXXXXXXX1234 is credited by Rs.45,000.00 on 01-09-26 by a/c linked to mobile 9XXXXXX999-SALARY (IMPS Ref no 123456789012)."),
        Sample("AX-ICICIB-S", "ICICI Bank Account XX123 credited:Rs. 1,200.00 on 10-Sep-26. Info NEFT-HDFCN52026091012345-ACME PVT LTD. Available Balance is Rs. 56,789.10."),
        Sample("VK-AXISBK-S", "INR 3,000.00 credited to A/c no. XX4567 on 09-09-26 at 14:22:10 IST. Info- UPI/P2A/426212345678/SURESH M/Axis Bank. - Axis Bank"),
        Sample("BZ-KOTAKB-S", "Received Rs.750.00 in your Kotak Bank a/c X9876 from priya@okaxis on 08-09-26.UPI Ref:426112345678. Not you? Call 18602662666"),
        Sample("VM-PAYTM-S", "Rs.500 received from Amit Sharma in your Paytm Wallet. Updated Balance: Rs.1,234. Txn ID: 12345678901"),
        Sample("VD-PHONEPE-S", "Received Rs.1,000 from Neha on PhonePe. Credited to your Bank of Baroda a/c XX2222."),
        Sample("JM-HDFCBK-S", "Update! INR 65,000.00 deposited in HDFC Bank A/c XX1234 on 01-SEP-26 for SALARY SEP26 ACME. Avl bal INR 72,345.00. Cheque deposits in A/C are subject to clearing"),
        Sample("VM-HDFCBK", "Rs 500 wrongly credited to your a/c XX1234 on 10-09-26 has been reversed. Please contact branch for details -HDFC Bank"),
        Sample("VM-AMAZON-S", "Refund of Rs 1,299.00 for your order 403-1234567 has been credited to your ICICI Bank credit card ending 4321."),
        Sample("VM-SBIINB", "प्रिय ग्राहक, आपके खाते XX1234 में रु 5,000 जमा किए गए हैं। -SBI"),
        Sample("TX-CANBNK-S", "An amount of INR 2,000.00 has been CREDITED to your account XXX567 on 05/09/2026 towards UPI. Total Avail.bal INR 9,876.00. - Canara Bank"),
        Sample("VM-KOTAKB-T", "Rs 1500 debited from Kotak Bank a/c X9876 to swiggy@icici on 06-09-26. Not you? https://kotak.com/fraud"),
        // Long codes some banks use for OTP / missed-call balance replies: no alert wording, so never flagged.
        Sample("+918422971234", "Your OTP for transaction of Rs 2,500.00 at AMAZON on HDFC Bank card xx4321 is 482913. Do not share. -HDFC Bank"),
        Sample("+919223008586", "Dear Customer, your A/c XX1234 balance is Rs 12,345.00 as on 12-09-26. -Canara Bank"),
        // Personal chats.
        Sample("+919812345678", "Bhai 500 bhej diya gpay pe, check kar le", contact = true),
        Sample("+919812345678", "Hi, reached home. Call you later"),
        Sample("+919812345678", "Sorry, that message was sent by mistake"),
        Sample("+919876543210", "Received your parcel, thanks!"),
        Sample("VK-AMAZON-P", "Great Indian Festival! Up to 80% off on mobiles. Shop now at amazon.in"),
    )

    /**
     * A fund's allotment or a broker's contract note from a header the bundle does not know is recorded as a credit to
     * the folio / demat account (money invested). It must never be taken for a fake bank credit, or the ledger would
     * drop it.
     */
    @Test
    fun `investment confirmations from unknown headers are not fake credits`() {
        val bodies = listOf(
            "Dear Investor, your SIP instalment of Rs.5,000.00 in Peak Flexi Cap Fund - Direct Growth, Folio No. XXXX1234 has been processed. Units allotted: 45.678 at NAV Rs.109.4563 on 05-Sep-2026.",
            "15.500 units redeemed from Silver Arbitrage Fund (Folio: 55443322) at NAV Rs 30.1200. Redemption amount Rs 466.86 credited to your registered bank account.",
            "IDCW of Rs 1,250.00 declared under Peak Equity Income Fund for folio XXXX1234 has been paid to your bank a/c XX4321 on 20-Sep-2026.",
            "Contract Note for 12-Sep-2026: Bought 10 ACME INDUSTRIES LTD @ 2,345.50. Net amount payable Rs 23,480.25 incl. charges. Client ID AB1234.",
            "Dear Investor, the current value of your investments in Folio XXXX1234 as on 19-Sep-2026 is Rs 1,23,456.78. Units held: 1,127.890.",
        )
        for (header in listOf("VM-PEAKMF-S", "JD-ZENBRK-T", "AX-NEWAMC")) {
            for (body in bodies) {
                val v = detector.evaluate(header, body, hint = TransactionHint(HintDirection.CREDIT, 500_000L, "1234"), dateMillis = now)
                assertTrue(v.level != ScamLevel.LIKELY_SCAM, "$header: $body -> $v")
            }
        }
    }

    @Test
    fun `genuine corpus is never flagged`() {
        for (s in genuine) {
            val verdict = detector.evaluate(s.address, s.body, isSavedContact = s.contact, dateMillis = now)
            assertEquals(ScamLevel.NONE, verdict.level, "false positive for ${s.address}: ${s.body} -> $verdict")
        }
    }

    @Test
    fun `classic fake credit from a mobile number signed as a bank is likely scam`() {
        val v = detector.evaluate(
            "+919876512345",
            "Your A/c XX1234 credited with Rs 25,000.00 on 12-09-26 by IMPS. Avl Bal Rs 25,340.50 -SBI",
            dateMillis = now,
        )
        assertEquals(ScamLevel.LIKELY_SCAM, v.level, v.toString())
        assertTrue(ScamReason.CREDIT_ALERT_FROM_PHONE_NUMBER in v.reasons)
        assertTrue(ScamReason.PHONE_NUMBER_CLAIMS_BANK in v.reasons)
        assertEquals("State Bank of India", v.claimedInstitution)
    }

    @Test
    fun `fake credit asking to return to a mobile number`() {
        val v = detector.evaluate(
            "+919812300000",
            "Dear customer, Rs.10,000 credited to your account XX5678 by mistake. Please return the amount to 9812300000 immediately.",
            dateMillis = now,
        )
        assertEquals(ScamLevel.LIKELY_SCAM, v.level)
        assertTrue(ScamReason.RETURN_REQUEST in v.reasons)
        assertTrue(ScamReason.MOBILE_NUMBER_IN_ALERT in v.reasons)
    }

    @Test
    fun `hinglish follow-up after a fake credit from another number`() {
        val credit = RecentMessage("+919800011111", "INR 15,000 credited to your A/c XX4321 via UPI. -HDFC Bank", now - 2 * hour)
        val v = detector.evaluate(
            "+917000022222",
            "Sir maine galti se aapke account me 15000 bhej diya, please wapas kar do 7000022222 pe. Bahut zaroori hai",
            recentMessages = listOf(credit),
            dateMillis = now,
        )
        assertEquals(ScamLevel.LIKELY_SCAM, v.level, v.toString())
        assertTrue(ScamReason.FOLLOW_UP_AFTER_CREDIT in v.reasons)
        assertTrue(ScamReason.RETURN_REQUEST in v.reasons)
    }

    @Test
    fun `follow-up counts an already flagged credit and ignores ones older than 48 hours`() {
        val body = "Hello I transferred Rs 7,500 to you wrongly sent, kindly send it back on 9123412345@ybl"
        val flagged = RecentMessage("+919800011111", "anything", now - 10 * hour, flaggedCredit = true)
        assertTrue(ScamReason.FOLLOW_UP_AFTER_CREDIT in detector.evaluate("+916123456789", body, recentMessages = listOf(flagged), dateMillis = now).reasons)
        val old = flagged.copy(dateMillis = now - 49 * hour)
        assertFalse(ScamReason.FOLLOW_UP_AFTER_CREDIT in detector.evaluate("+916123456789", body, recentMessages = listOf(old), dateMillis = now).reasons)
    }

    @Test
    fun `return request after a genuine bank credit is capped at suspicious`() {
        val genuineCredit = RecentMessage("VM-HDFCBK-S", "Money Received - INR 5,000.00 in your HDFC Bank A/c xx1234 by VPA x@okicici", now - hour)
        val v = detector.evaluate(
            "+919999912345",
            "Hi, I sent Rs 5,000 to your number by mistake. Please return it, it was for my mother's hospital",
            recentMessages = listOf(genuineCredit),
            dateMillis = now,
        )
        assertEquals(ScamLevel.SUSPICIOUS, v.level, v.toString())
        assertTrue(ScamReason.RETURN_AFTER_GENUINE_CREDIT in v.reasons)
    }

    @Test
    fun `return request from an unknown number with no prior credit is suspicious`() {
        val v = detector.evaluate("+919999912345", "Sir I sent 2000 rupees to you by mistake, please return", dateMillis = now)
        assertEquals(ScamLevel.SUSPICIOUS, v.level)
    }

    @Test
    fun `upi collect trick with pin to receive is likely scam`() {
        val v = detector.evaluate(
            "+918888812345",
            "Congratulations! You have received Rs 4,999 cashback from PhonePe. Tap the link and enter UPI PIN to receive the money: http://bit.ly/abc123",
            dateMillis = now,
        )
        assertEquals(ScamLevel.LIKELY_SCAM, v.level)
        assertTrue(ScamReason.PIN_TO_RECEIVE in v.reasons)
        assertTrue(ScamReason.COLLECT_REQUEST in v.reasons)

        val fromHeader = detector.evaluate(
            "VM-RWRDPT",
            "You have a money request of Rs 2,000 pending. Enter your UPI PIN to accept money into your account.",
            dateMillis = now,
        )
        assertEquals(ScamLevel.LIKELY_SCAM, fromHeader.level, fromHeader.toString())
    }

    @Test
    fun `collect request without pin wording is at least suspicious`() {
        val v = detector.evaluate("VM-RWRDPT", "You have received a payment request of Rs 2,000. Approve the request to receive your refund", dateMillis = now)
        assertEquals(ScamLevel.SUSPICIOUS, v.level)
    }

    @Test
    fun `bank lookalike alphanumeric sender`() {
        val v = detector.evaluate("HDFC-BANK", "Rs.50,000.00 credited to your A/c XX9012. Call 9123456780 for details.", dateMillis = now)
        assertEquals(ScamLevel.LIKELY_SCAM, v.level)
        assertTrue(ScamReason.LOOKALIKE_SENDER in v.reasons)
        assertEquals(ScamLevel.LIKELY_SCAM, detector.evaluate("SBIBANK", "Your A/c XX1234 is credited with INR 20,000.00. -SBI", dateMillis = now).level)
    }

    @Test
    fun `numeric sender quoting a bank header in the body`() {
        val v = detector.evaluate("+916200012345", "[VM-HDFCBK] INR 12,000.00 deposited in HDFC Bank A/c XX0011 on 12-09-26. Avl bal INR 12,410.00", dateMillis = now)
        assertEquals(ScamLevel.LIKELY_SCAM, v.level)
        assertEquals("HDFC Bank", v.claimedInstitution)
    }

    @Test
    fun `hindi and hinglish fakes`() {
        val hindi = detector.evaluate(
            "+917012345678",
            "आपके खाते XX4455 में रु 30,000 जमा हुए हैं। गलती से भेजे गए हैं, कृपया वापस करें। -SBI",
            dateMillis = now,
        )
        assertEquals(ScamLevel.LIKELY_SCAM, hindi.level, hindi.toString())
        val hinglish = detector.evaluate(
            "+919090912345",
            "Aapke account XX7788 me Rs 8,000 credit ho gaye hai galti se. Plz paise wapas kar do 9090912345 pe",
            dateMillis = now,
        )
        assertEquals(ScamLevel.LIKELY_SCAM, hinglish.level, hinglish.toString())
    }

    @Test
    fun `fake salary credit and fake debit`() {
        assertEquals(
            ScamLevel.LIKELY_SCAM,
            detector.evaluate("+918123456789", "Dear Employee, your salary of Rs 42,500 has been credited to a/c XX3344. -Axis Bank", dateMillis = now).level,
        )
        assertEquals(
            ScamLevel.LIKELY_SCAM,
            detector.evaluate("+918123456789", "Rs 9,999 debited from your a/c XX1234. If not done by you call 9876500000 -SBI", dateMillis = now).level,
        )
    }

    @Test
    fun `fake refund with link and promotional route credit are suspicious`() {
        val refund = detector.evaluate("+916000012345", "Your refund of Rs 3,450 is pending. Click here to receive it: http://refund-now.xyz", dateMillis = now)
        assertTrue(refund.isFlagged, refund.toString())
        val promo = detector.evaluate("VK-CASHKR-P", "Rs 5,000 credited to your wallet! Claim now at http://cashkr.xyz/c", dateMillis = now)
        assertEquals(ScamLevel.SUSPICIOUS, promo.level)
        assertTrue(ScamReason.PROMOTIONAL_ROUTE in promo.reasons)
    }

    @Test
    fun `mobile credit alert without a bank name is suspicious only`() {
        val v = detector.evaluate("+919812300000", "Rs 5000 credited to your a/c XX1234 via IMPS", dateMillis = now)
        assertEquals(ScamLevel.SUSPICIOUS, v.level)
    }

    @Test
    fun `saved contact reduces but does not eliminate`() {
        val body = "Rs.10,000 credited to your SBI account XX5678 by mistake. Please return the amount."
        val unknown = detector.evaluate("+919812300000", body, dateMillis = now)
        val contact = detector.evaluate("+919812300000", body, isSavedContact = true, dateMillis = now)
        assertEquals(ScamLevel.LIKELY_SCAM, unknown.level)
        assertEquals(ScamLevel.SUSPICIOUS, contact.level)
        assertTrue(contact.score < unknown.score)
    }

    @Test
    fun `account the user does not have`() {
        val known = setOf(AccountHint("HDFC Bank", "1234"), AccountHint("ICICI Bank", "0987"))
        val body = "INR 20,000.00 credited to HDFC Bank A/c XX9999 on 12-09-26."
        val v = detector.evaluate("AX-FSTPAY", body, knownAccounts = known, dateMillis = now)
        assertTrue(ScamReason.UNKNOWN_ACCOUNT in v.reasons, v.toString())
        assertTrue(ScamReason.UNVERIFIED_SENDER in v.reasons)
        assertEquals(ScamLevel.SUSPICIOUS, v.level)

        val matching = detector.evaluate("AX-FSTPAY", body.replace("XX9999", "XX001234"), knownAccounts = known, dateMillis = now)
        assertFalse(ScamReason.UNKNOWN_ACCOUNT in matching.reasons)

        val noAccount = detector.evaluate("+919812300000", "Rs 5000 credited to your Kotak a/c XX1111", knownAccounts = known, dateMillis = now)
        assertTrue(ScamReason.NO_ACCOUNT_AT_BANK in noAccount.reasons)
        // Genuine alerts from verified headers are never flagged, even for an unknown account.
        assertEquals(ScamLevel.NONE, detector.evaluate("VM-HDFCBK-S", body, knownAccounts = known, dateMillis = now).level)
    }

    @Test
    fun `beneficiary confirmation is the user's own transfer, not a credit to an unknown account`() {
        val known = setOf(AccountHint("HDFC Bank", "1234"))
        val body = "Confirmation! INR 100,000.00 credited to beneficiary A/c XX5632 for your NEFT on 07-Dec-2024. Ref N34224216. - HDFC Bank"
        val v = detector.evaluate("AX-FSTPAY", body, knownAccounts = known, dateMillis = now)
        assertFalse(ScamReason.UNKNOWN_ACCOUNT in v.reasons, v.toString())
        // A lookalike sender still counts as one; only the payee's account is ignored.
        val fake = detector.evaluate("AX-FSTPAY", "INR 20,000.00 credited to HDFC Bank A/c XX9999 on 12-09-26.", knownAccounts = known, dateMillis = now)
        assertTrue(ScamReason.UNKNOWN_ACCOUNT in fake.reasons)
    }

    @Test
    fun `unprefixed real bank header alone is not flagged but with a return request is`() {
        assertEquals(ScamLevel.NONE, detector.evaluate("HDFCBK", "INR 2,000.00 credited to HDFC Bank A/c XX1234", dateMillis = now).level)
        assertEquals(
            ScamLevel.LIKELY_SCAM,
            detector.evaluate("HDFCBK", "INR 2,000.00 credited to HDFC Bank A/c XX1234 by mistake. Please return to 9812345670", dateMillis = now).level,
        )
    }

    @Test
    fun `caller hints are used`() {
        val v = detector.evaluate(
            "+919812300000",
            "Transfer done to your a/c. -ICICI",
            hint = TransactionHint(HintDirection.CREDIT, 100_000, "4321"),
            dateMillis = now,
        )
        assertEquals(ScamLevel.LIKELY_SCAM, v.level)
    }

    @Test
    fun `amount and mask helpers`() {
        assertEquals(setOf(2_500_000L), FakeCreditDetector.amountsIn("Rs 25,000.00 credited"))
        assertEquals(setOf(50_000L), FakeCreditDetector.amountsIn("INR500 received"))
        assertEquals(setOf(300_000L), FakeCreditDetector.amountsIn("रु 3,000 जमा"))
        assertEquals(setOf(150_000L), FakeCreditDetector.amountsIn("1500/- sent"))
        assertEquals("1234", FakeCreditDetector.maskIn("A/c XX1234 credited"))
        assertEquals("5073", FakeCreditDetector.maskIn("Credited INR 50,000.00 to A/c X5073 on 06-AUG-2026"))
        assertEquals(null, FakeCreditDetector.maskIn("order X12345 shipped"))
        assertTrue(FakeCreditDetector.digitsMatch("001234", "1234"))
        assertFalse(FakeCreditDetector.digitsMatch("1234", "9999"))
    }

    @Test
    fun `labels round-trip and ledger exclusion`() {
        val v = ScamVerdict(ScamLevel.LIKELY_SCAM, listOf(ScamReason.RETURN_REQUEST, ScamReason.CREDIT_ALERT_FROM_PHONE_NUMBER), "State Bank of India")
        val labels = ScamLabels.toLabels(v)
        assertTrue(ScamLabels.LIKELY in labels)
        val back = ScamLabels.fromLabels(labels)!!
        assertEquals(ScamLevel.LIKELY_SCAM, back.level)
        assertEquals(setOf(ScamReason.RETURN_REQUEST, ScamReason.CREDIT_ALERT_FROM_PHONE_NUMBER), back.reasons.toSet())
        assertEquals("State Bank of India", back.claimedInstitution)
        assertTrue(ScamLabels.excludedFromLedger(labels))
        assertFalse(ScamLabels.excludedFromLedger(labels + ScamLabels.DISMISSED))
        assertNull(ScamLabels.fromLabels(labels + ScamLabels.DISMISSED))
        assertTrue(ScamLabels.toLabels(ScamVerdict.None).isEmpty())
        assertTrue(labels.all { ScamLabels.isScamLabel(it) })
    }

    @Test
    fun `outside india only generic signals apply`() {
        // A US bank alert from a short code / long code is normal there.
        val genuineUs = detector.evaluate("+14155550123", "Chase: A deposit of $1,250.00 was credited to your account ending 1234.", dateMillis = now, region = "US")
        assertEquals(ScamLevel.NONE, genuineUs.level, genuineUs.toString())
        val genuineUk = detector.evaluate("HSBC", "HSBC: GBP 300.00 has been received into your account ending 5678.", dateMillis = now, region = "GB")
        assertEquals(ScamLevel.NONE, genuineUk.level)
        // Unknown sender + credit + return urgency is still a likely scam anywhere.
        val scam = detector.evaluate(
            "+14155550199",
            "Your account ending 1234 was credited with USD 900.00 by mistake. Please send it back to 4155550199 today.",
            dateMillis = now,
            region = "US",
        )
        assertEquals(ScamLevel.LIKELY_SCAM, scam.level, scam.toString())
        assertTrue(ScamReason.UNKNOWN_SENDER_ALERT in scam.reasons)
        // India-only rules do not fire elsewhere.
        val india = detector.evaluate("+919876512345", "Your A/c XX1234 credited with Rs 25,000.00 -SBI", dateMillis = now)
        val abroad = detector.evaluate("+919876512345", "Your A/c XX1234 credited with Rs 25,000.00 -SBI", dateMillis = now, region = "AE")
        assertEquals(ScamLevel.LIKELY_SCAM, india.level)
        assertEquals(ScamLevel.NONE, abroad.level)
        assertEquals(ScamLevel.LIKELY_SCAM, detector.evaluate("+14155550123", "Tap the link and enter UPI PIN to receive $500 cashback", dateMillis = now, region = "US").level)
    }

    @Test
    fun `cheap pre-checks agree with evaluate`() {
        assertFalse(detector.isCandidate("VM-HDFCBK-S", "INR 500 credited to A/c XX1234"))
        assertFalse(detector.isCandidate("+919812345678", "Hi, reached home"))
        assertTrue(detector.isCandidate("+919812345678", "Rs 500 credited to your a/c XX1234"))
        assertTrue(detector.needsRecentMessages("+917000022222", "maine galti se 15000 bhej diya, wapas kar do"))
        assertFalse(detector.needsRecentMessages("+917000022222", "Rs 500 credited to your a/c XX1234"))
        assertFalse(detector.needsRecentMessages("VM-HDFCBK-S", "I sent Rs 500 by mistake"))
        val flagged = ScamLabels.toLabels(detector.evaluate("+919876512345", "Your A/c XX1234 credited with Rs 25,000.00 -SBI", dateMillis = now))
        assertTrue(ScamLabels.isFlaggedCredit(flagged))
        val followUp = ScamLabels.toLabels(detector.evaluate("+919999912345", "Sir I sent 2000 rupees to you by mistake, please return", dateMillis = now))
        assertTrue(ScamLabels.isFlagged(followUp))
        assertFalse(ScamLabels.isFlaggedCredit(followUp))
        assertEquals("%\"scam:likely-fake-credit\"%", ScamLabels.likePattern(ScamLabels.LIKELY))
    }

    @Test
    fun `scheme-less links and numeric promotional headers count`() {
        // A scheme-less payment link in a fake alert is still a link.
        val fake = detector.evaluate(
            "+919876512345", "Rs 15,000 credited to your A/c XX4321 by IMPS. Check status at bit.ly/imps-status", dateMillis = now,
        )
        assertEquals(ScamLevel.LIKELY_SCAM, fake.level, fake.toString())
        assertTrue(ScamReason.LINK_IN_ALERT in fake.reasons, fake.toString())
        // A credit "alert" on a 6-digit (promotional-only) header.
        val promo = detector.evaluate("VM-612345", "INR 5,000 credited to your A/c XX1234 by NEFT. -SBI", dateMillis = now)
        assertTrue(ScamReason.PROMOTIONAL_ROUTE in promo.reasons, promo.toString())
        // Genuine alerts from an unknown (not in the table) bank header with a scheme-less "not you?" link stay clean.
        val genuine = detector.evaluate(
            "JD-NIMBUS-T", "Rs 4,500.00 debited from A/c XX1234 on 12-03-26. Not you? Report at nmb.in/fraud", dateMillis = now,
        )
        assertEquals(ScamLevel.NONE, genuine.level, genuine.toString())
    }

    @Test
    fun `stays fast on hostile bodies`() {
        val hostile = listOf(
            "x".repeat(50_000), "X".repeat(50_000) + "1", "*".repeat(50_000), "1".repeat(50_000), "rs ".repeat(20_000),
            "credited ".repeat(10_000), "a@b".repeat(20_000), "enter pin ".repeat(8_000), "9".repeat(50_000),
            "1,".repeat(25_000), "please return ".repeat(5_000), "a/c xx".repeat(10_000),
        )
        for (body in hostile) {
            val start = System.nanoTime()
            detector.evaluate("+919812300000", body, recentMessages = listOf(RecentMessage("+919800000000", body, now, false)), dateMillis = now)
            detector.evaluate("HDFC-BANK", body, dateMillis = now)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(ms < 1_000, "took ${ms}ms on ${body.take(10)}")
        }
    }
}
