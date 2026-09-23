package app.dak.finance.parser

import app.dak.core.model.TransactionDirection
import app.dak.core.model.TransactionDirection.CREDIT
import app.dak.core.model.TransactionDirection.DEBIT
import app.dak.finance.money.MoneyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The direction and amount-role rules, one rule per case, so a regression names the rule it broke. */
class DirectionAndRolesTest {

    private fun check(cases: List<Pair<String, Pair<TransactionDirection, Long>?>>) {
        val problems = cases.mapNotNull { (body, want) ->
            val got = TransactionParser.parse("VM-NEWBNK-S", body)?.let { it.direction to it.amountMinor }
            if (got != want) "expected $want got $got: $body" else null
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `finite verbs decide the direction`() = check(
        listOf(
            "Rs 500 debited from A/c XX1234." to (DEBIT to 50_000L),
            "Rs 500 credited to A/c XX1234." to (CREDIT to 50_000L),
            "Rs 500 spent on Card XX1234 at SHOP." to (DEBIT to 50_000L),
            "Rs 500 withdrawn from A/c XX1234 at ATM." to (DEBIT to 50_000L),
            "Rs 500 deposited in A/c XX1234." to (CREDIT to 50_000L),
            "Rs 500 received in A/c XX1234 from ravi@upi." to (CREDIT to 50_000L),
            "Your Card XX1234 was charged Rs 500." to (DEBIT to 50_000L),
            "Rs 500 has been successfully credited to your A/c XX1234." to (CREDIT to 50_000L),
        ),
    )

    @Test
    fun `transfer verbs are a credit only when the money went to you`() = check(
        listOf(
            "Rs 500 sent to ravi@upi from A/c XX1234." to (DEBIT to 50_000L),
            "Rs 500 transferred to your A/c XX1234." to (CREDIT to 50_000L),
            "Kiran has sent you Rs 300." to (CREDIT to 30_000L),
            // Paying your own card is money you send.
            "Rs 5,000 paid to your Credit Card XX9876 from A/c XX1234." to (DEBIT to 500_000L),
        ),
    )

    @Test
    fun `refunds and reversals are credits even with failure words`() = check(
        listOf(
            "Txn of Rs 500 failed. Amount refunded to A/c XX1234." to (CREDIT to 50_000L),
            "Rs 500 debited earlier has been reversed to A/c XX1234." to (CREDIT to 50_000L),
            "Rs 500 credited back to your A/c XX1234." to (CREDIT to 50_000L),
        ),
    )

    @Test
    fun `negated, conditional, future and failed movements are not transactions`() = check(
        listOf(
            "Rs 500 will be debited from A/c XX1234 on 12-09." to null,
            "Rs 500 is scheduled to be debited from A/c XX1234." to null,
            "If Rs 500 is not credited, call us." to null,
            "Rs 500 not credited to A/c XX1234 due to technical error." to null,
            "Txn of Rs 500 on A/c XX1234 failed." to null,
            "Txn of Rs 500 declined on Card XX1234: insufficient balance." to null,
            "Your autopay mandate of Rs 500 is registered." to null,
            "Ravi has requested Rs 500 from you on UPI." to null,
            "Your e-statement for A/c XX1234 is ready." to null,
        ),
    )

    @Test
    fun `balance, limit, due, fee and equivalent amounts are never the transaction amount`() = check(
        listOf(
            "Avl Bal Rs 10,000. Rs 500 debited from A/c XX1234." to (DEBIT to 50_000L),
            "Rs 500 debited from A/c XX1234. Avl Bal Rs 10,000." to (DEBIT to 50_000L),
            "Avl Lmt Rs 45,000. Rs 500 spent on Card XX1234." to (DEBIT to 50_000L),
            "Charges of Rs 5.90 and Rs 500 debited from A/c XX1234." to (DEBIT to 50_000L),
            "Rs 10,850 (AED 120.50) debited on Card XX1234." to (DEBIT to 1_085_000L),
            "Rs 500 credited to A/c XX1234. Rs 10,000 is your available balance." to (CREDIT to 50_000L),
        ),
    )

    @Test
    fun `a cashback is the amount of a credit but not of a debit`() = check(
        listOf(
            "Cashback of Rs 25 credited to your wallet." to (CREDIT to 2_500L),
            "Rs 500 spent on Card XX1234; cashback Rs 25 will be credited." to (DEBIT to 50_000L),
        ),
    )

    @Test
    fun `the amount nearest the deciding verb wins`() = check(
        listOf("Rs 200 for order 1, Rs 300 debited from A/c XX1234." to (DEBIT to 30_000L)),
    )

    @Test
    fun `a bare number after the verb is money only with a currency from the message or the DLT header`() {
        val dlt = TransactionParser.parse("AD-NEWBNK-S", "A/C X9876 debited by 250.0 on 12-09-26.")
        assertEquals(DEBIT to 25_000L, dlt?.let { it.direction to it.amountMinor })
        assertEquals("INR", dlt?.currency)
        assertNull(TransactionParser.parse("+15551234567", "A/C X9876 debited by 250.0 on 12-09-26."))
        // The balance's currency is the amount's too.
        val usd = TransactionParser.parse("+15551234567", "A/C X9876 debited by 250.00 on 12-09-26. Avl Bal USD 1,000.00")
        assertEquals("USD", usd?.currency)
        assertEquals(25_000L, usd?.amountMinor)
        // "credited with 500 reward points" is not money.
        assertNull(TransactionParser.parse("AD-NEWBNK-S", "Your A/c X9876 credited with 500 reward points today"))
    }

    @Test
    fun `OTP gate ignores safety footers but catches codes`() {
        assertTrue(DirectionCues.isOtp("Use 482913 to authorise Rs 500 at SHOP."))
        assertTrue(DirectionCues.isOtp("482913 is your one time password for Rs 500."))
        assertFalse(DirectionCues.isOtp("Rs 500 debited from A/c XX1234. Never share your OTP with anyone."))
        assertFalse(DirectionCues.isOtp("Rs 500 debited. Bank never asks for OTP."))
    }

    @Test
    fun `merchant and reference extraction`() {
        val upi = TransactionParser.parse("VM-NEWBNK-S", "Rs 500 debited from A/c XX1234 to VPA shop.name@okbank. UPI Ref No: 612345678901.")
        assertEquals("shop.name@okbank", upi?.merchant)
        assertEquals("612345678901", upi?.reference)
        val credit = TransactionParser.parse("VM-NEWBNK-S", "Rs 500 credited to A/c XX1234 from ravi.k@okbank. RRN 998877.")
        assertEquals("ravi.k@okbank", credit?.merchant)
        assertEquals("998877", credit?.reference)
        // "to your A/c" is not a payee name.
        val own = TransactionParser.parse("VM-NEWBNK-S", "Rs 500 transferred from A/c XX1234 to your A/c XX5678.")
        assertNull(own?.merchant)
        assertEquals("T12345", TransactionParser.referenceOf("Paid Rs 50. Txn ID: T12345"))
        assertEquals("998877", TransactionParser.referenceOf("Reference No. 998877"))
        assertEquals("N34224216", TransactionParser.referenceOf("NEFT done. UTR: N34224216"))
        assertNull(TransactionParser.referenceOf("Rs 500 debited from A/c XX1234"))
    }

    @Test
    fun `investment transfers are marked so they are never spending or income`() {
        assertTrue(TransactionParser.isInvestmentTransfer("Rs 5000 debited towards SIP", DEBIT))
        assertTrue(TransactionParser.isInvestmentTransfer("Rs 5000 debited for Demat A/c top-up", DEBIT))
        assertFalse(TransactionParser.isInvestmentTransfer("Rs 5000 debited at SHOP", DEBIT))
        assertTrue(TransactionParser.isInvestmentTransfer("Redemption proceeds Rs 5000 credited", CREDIT))
        assertFalse(TransactionParser.isInvestmentTransfer("Dividend of Rs 50 for folio 1234 credited", CREDIT))
        assertFalse(TransactionParser.isInvestmentTransfer("Interest Rs 50 credited", CREDIT))
    }

    @Test
    fun `promotions are recognised`() {
        assertTrue(TransactionParser.isPromotion("Get flat 20% off, use code SAVE20"))
        assertTrue(TransactionParser.isPromotion("You are pre-approved for a loan up to Rs 5 lakh"))
        assertFalse(TransactionParser.isPromotion("Rs 500 debited from A/c XX1234"))
    }

    @Test
    fun `institution table and DLT headers`() {
        assertTrue(InstitutionTable.isDltSender("VM-NEWBNK-S"))
        assertTrue(InstitutionTable.isDltSender(" ad-hdfcbk "))
        assertTrue(InstitutionTable.isDltSender("JD-123ABC"))
        assertFalse(InstitutionTable.isDltSender("JD-123456")) // a header needs a letter
        assertFalse(InstitutionTable.isDltSender("HDFCBK"))
        assertFalse(InstitutionTable.isDltSender("+919812345678"))
        assertEquals("IN", InstitutionTable.countryOf("hdfc bank"))
        assertNull(InstitutionTable.countryOf("Revolut"))
        assertNull(InstitutionTable.countryOf(" "))
        assertNull(InstitutionTable.countryOf(null))
        // A suffix glued to a known header still resolves.
        assertEquals("HDFC Bank", InstitutionTable.institutionFor("HDFCBKS"))
    }

    @Test
    fun `amount roles by position`() {
        val body = "Rs 45,000 available limit. Rs 50 cashback credited. Rs 500 debited."
        val roles = AmountRoles.classify(body, MoneyParser.findAll(body)).map { it.role }
        assertEquals(listOf(AmountRole.LIMIT, AmountRole.CASHBACK, AmountRole.NONE), roles)
    }
}
