package app.dak.finance.parser

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Which instrument (and number) a transaction SMS is about, across Indian and international phrasing. */
class InstrumentDetectionTest {

    private fun parse(sender: String, body: String): ExtractedTransaction =
        assertNotNull(TransactionParser.parse(sender, body), "not parsed: $body")

    private fun assertInstrument(expected: InstrumentType, sender: String, body: String, last4: String? = null): ExtractedTransaction {
        val txn = parse(sender, body)
        assertEquals(expected, txn.instrument, body)
        if (last4 != null) assertEquals(last4, txn.last4, body)
        return txn
    }

    // --- Debit cards ---

    @Test
    fun `debit card with masked number`() {
        assertInstrument(InstrumentType.DEBIT_CARD, "VM-ICICIB", "Rs 850.00 spent using ICICI Bank Debit Card XX1234 at ZOMATO on 12-Sep-26.", "1234")
    }

    @Test
    fun `debit card ending`() {
        assertInstrument(InstrumentType.DEBIT_CARD, "VM-SBIINB", "Rs.320 spent on your SBI debit card ending 4455 at DMART on 10/09/26.", "4455")
    }

    @Test
    fun `DC abbreviation`() {
        assertInstrument(InstrumentType.DEBIT_CARD, "VM-KOTAKB", "Rs.1,200.00 debited on Kotak DC XX7788 at BIGBASKET on 01-09-26.", "7788")
    }

    @Test
    fun `ATM card withdrawal`() {
        assertInstrument(InstrumentType.DEBIT_CARD, "ATMSBI", "Rs.2000 withdrawn using ATM card XX3131 at SBI ATM KORAMANGALA on 19-09-26.", "3131")
    }

    @Test
    fun `Visa debit card ending international`() {
        val txn = assertInstrument(
            InstrumentType.DEBIT_CARD,
            "Barclays",
            "Your Visa debit card ending 1234 was charged USD 42.10 at STARBUCKS on 21 Sep.",
            "1234",
        )
        assertEquals("USD", txn.currency)
        assertEquals(4210L, txn.amountMinor)
    }

    @Test
    fun `plain card debited from your account is a debit card`() {
        assertInstrument(InstrumentType.DEBIT_CARD, "VM-HDFCBK", "Rs.499 spent on HDFC Bank Card XX1234 at MYNTRA. Amount debited from your A/c.", "1234")
    }

    @Test
    fun `plain card with available balance is a debit card`() {
        val txn = assertInstrument(
            InstrumentType.DEBIT_CARD,
            "VM-HDFCBK",
            "Rs.499.00 spent on HDFC Bank Card XX1234 at MYNTRA on 12-09-26. Avl bal: Rs 10,000.00",
            "1234",
        )
        assertEquals(1000000L, txn.balanceMinor)
    }

    @Test
    fun `debit card and account in one SMS links them`() {
        val txn = assertInstrument(
            InstrumentType.DEBIT_CARD,
            "VM-HDFCBK",
            "Rs.2,500.00 debited from A/c XX1234 using Debit Card XX5678 at CROMA on 12-09-26. Avl Bal Rs.40,000.00",
            "5678",
        )
        assertEquals("XX5678", txn.maskedNumber)
        assertEquals("XX1234", txn.linkedMaskedNumber)
        assertEquals("HDFC_BANK:BANK_ACCOUNT:1234", Account.linkedIdOf(txn))
        assertEquals(4000000L, txn.balanceMinor)
    }

    @Test
    fun `untyped card and account in one SMS is a linked debit card`() {
        val txn = assertInstrument(
            InstrumentType.DEBIT_CARD,
            "VM-AXISBK",
            "INR 640.00 debited from A/c no. XX440065 for Card XX9911 txn at UBER on 02-09-26.",
            "9911",
        )
        assertEquals("XX440065", txn.linkedMaskedNumber)
        assertEquals("AXIS_BANK:BANK_ACCOUNT:440065", Account.linkedIdOf(txn))
    }

    @Test
    fun `debit card alone has no linked account`() {
        val txn = parse("VM-ICICIB", "Rs 850.00 spent using ICICI Bank Debit Card XX1234 at ZOMATO on 12-Sep-26.")
        assertNull(txn.linkedMaskedNumber)
        assertNull(Account.linkedIdOf(txn))
    }

    // --- Credit cards ---

    @Test
    fun `credit card masked`() {
        assertInstrument(InstrumentType.CREDIT_CARD, "AX-ICICIT", "Your ICICI Bank Credit Card XX4321 used for Rs 999.00 at FLIPKART on 18-09-26.", "4321")
    }

    @Test
    fun `CC abbreviation`() {
        assertInstrument(InstrumentType.CREDIT_CARD, "VM-AXISBK", "Spent INR 1,450.00 on Axis Bank CC XX6677 at SWIGGY on 09-09-26.", "6677")
    }

    @Test
    fun `plain card with available limit is a credit card`() {
        assertInstrument(InstrumentType.CREDIT_CARD, "VM-HDFCBK", "Rs.1,499.00 spent on HDFC Bank Card XX1234 at AMAZON on 12-09-26. Avl Limit: Rs.45,320.00", "1234")
    }

    @Test
    fun `card ending with credit limit wording`() {
        assertInstrument(InstrumentType.CREDIT_CARD, "VM-RBLBNK", "Your card ending 8080 was used for Rs 700 at PVR. Available credit limit Rs 80,000.", "8080")
    }

    @Test
    fun `SBI Card sender is a credit card issuer`() {
        assertInstrument(InstrumentType.CREDIT_CARD, "VM-SBICRD", "Rs.2,999.00 spent on your SBI Card ending 3344 at AJIO on 05/09/26.", "3344")
    }

    @Test
    fun `Chase Sapphire ending in`() {
        val txn = assertInstrument(
            InstrumentType.CREDIT_CARD,
            "Chase",
            "Chase: A charge of USD 25.00 at AMAZON was authorized on your Sapphire Preferred ending in 1234.",
            "1234",
        )
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(2500L, txn.amountMinor)
    }

    @Test
    fun `American Express card`() {
        assertInstrument(InstrumentType.CREDIT_CARD, "AMEX", "You spent GBP 18.40 at PRET A MANGER on your American Express Card ending 1005.", "1005")
    }

    @Test
    fun `ambiguous card stays a credit card`() {
        assertInstrument(InstrumentType.CREDIT_CARD, "VM-YESBNK", "Rs 300 spent on Yes Bank Card XX2020 at CAFE COFFEE DAY on 01-09-26.", "2020")
    }

    // --- Prepaid, forex, travel, gift, e-money cards ---

    @Test
    fun `forex card`() {
        val txn = assertInstrument(
            InstrumentType.PREPAID_CARD,
            "VM-HDFCBK",
            "USD 60.00 spent on your HDFC Bank Forex Card XX7001 at MACY'S NEW YORK. Avl bal USD 540.00",
            "7001",
        )
        assertEquals("USD", txn.balanceCurrency)
        assertEquals(54000L, txn.balanceMinor)
    }

    @Test
    fun `multi-currency card`() {
        assertInstrument(InstrumentType.PREPAID_CARD, "VM-AXISBK", "EUR 12.00 spent on your Axis Bank Multi-Currency Card XX2323 at LIDL.", "2323")
    }

    @Test
    fun `multicurrency forex card`() {
        assertInstrument(InstrumentType.PREPAID_CARD, "VM-ICICIB", "AED 80.00 spent using ICICI Multicurrency Forex Card XX4545 at CARREFOUR.", "4545")
    }

    @Test
    fun `travel card`() {
        assertInstrument(InstrumentType.PREPAID_CARD, "VM-KOTAKB", "GBP 9.99 debited on Kotak Travel Card XX1111 at TESCO.", "1111")
    }

    @Test
    fun `prepaid card`() {
        assertInstrument(InstrumentType.PREPAID_CARD, "VM-PAYTMB", "Rs 250 spent on Paytm Prepaid Card XX8181 at BLINKIT.", "8181")
    }

    @Test
    fun `gift card`() {
        assertInstrument(InstrumentType.PREPAID_CARD, "VM-AMAZNP", "Rs.500 debited from your gift card balance for order 402-1. Gift card XX9090.", "9090")
    }

    @Test
    fun `Wise card`() {
        assertInstrument(InstrumentType.PREPAID_CARD, "Wise", "You spent EUR 25.00 at FNAC with your Wise card ending 6789.", "6789")
    }

    @Test
    fun `Revolut card with balance`() {
        val txn = assertInstrument(
            InstrumentType.PREPAID_CARD,
            "Revolut",
            "Revolut: You paid EUR 12.50 at LIDL with card ending 1234. Balance: EUR 230.10",
            "1234",
        )
        assertEquals(1250L, txn.amountMinor)
        assertEquals(23010L, txn.balanceMinor)
        assertEquals("EUR", txn.balanceCurrency)
    }

    @Test
    fun `explicit debit card beats e-money brand`() {
        assertInstrument(InstrumentType.DEBIT_CARD, "Revolut", "Revolut: EUR 8.00 spent at SPAR with your debit card ending 4242.", "4242")
    }

    // --- Loans ---

    @Test
    fun `loan account with number`() {
        val txn = assertInstrument(
            InstrumentType.LOAN,
            "VM-HDFCBK",
            "EMI of Rs.12,500.00 debited towards your Loan A/c XX9876 on 05-09-26. Outstanding principal: Rs.4,20,000.00",
            "9876",
        )
        assertEquals(1250000L, txn.amountMinor)
        assertEquals(42000000L, txn.balanceMinor)
        assertNull(txn.linkedMaskedNumber)
    }

    @Test
    fun `EMI towards loan from an account links the account`() {
        val txn = assertInstrument(
            InstrumentType.LOAN,
            "AXISBK",
            "EMI of Rs.4,599.00 auto-debited from A/c XX2211 towards Loan a/c on 05-09-26. Avl Bal Rs.22,000.00",
        )
        assertNull(txn.maskedNumber)
        assertEquals("XX2211", txn.linkedMaskedNumber)
        assertEquals(2200000L, txn.balanceMinor)
        assertEquals("AXIS_BANK:BANK_ACCOUNT:2211", Account.linkedIdOf(txn))
    }

    @Test
    fun `loan account debited from account names both`() {
        val txn = assertInstrument(
            InstrumentType.LOAN,
            "VM-ICICIB",
            "Rs 8,000 debited from A/c XX1212 for Loan Account XX3434 EMI on 07-09-26.",
            "3434",
        )
        assertEquals("XX1212", txn.linkedMaskedNumber)
    }

    @Test
    fun `unmasked loan number is masked to its last four`() {
        val txn = assertInstrument(InstrumentType.LOAN, "VM-BAJAJF", "Received payment of Rs 3,200 for Loan Account No. 40512345678. Thank you.", "5678")
        assertEquals("XX5678", txn.maskedNumber)
    }

    @Test
    fun `EMI wording without numbers`() {
        assertInstrument(InstrumentType.LOAN, "VM-BAJAJF", "Your EMI of Rs 2,100 for personal loan has been deducted successfully.")
    }

    // --- Wallets ---

    @Test
    fun `Paytm wallet`() {
        assertInstrument(InstrumentType.WALLET, "PAYTMB", "Rs.150.00 paid to Chai Point from Paytm Wallet. Txn ID T2609211234.")
    }

    @Test
    fun `Amazon Pay balance`() {
        assertInstrument(InstrumentType.WALLET, "AMZNPB", "Rs.200.00 credited to your Amazon Pay balance as refund for order #123-4567890.")
    }

    @Test
    fun `PhonePe wallet`() {
        assertInstrument(InstrumentType.WALLET, "PHONPE", "Paid Rs.99 to Jio Recharge from your PhonePe Wallet.")
    }

    @Test
    fun `MobiKwik`() {
        assertInstrument(InstrumentType.WALLET, "VM-MOBIKW", "Rs 120 paid to Uber from MobiKwik. Txn ID MK1234.")
    }

    @Test
    fun `Airtel Money`() {
        assertInstrument(InstrumentType.WALLET, "AIRTEL", "Rs.75 debited from your Airtel Money for electricity bill payment.")
    }

    @Test
    fun `wallet top-up from a bank account is the bank account`() {
        assertInstrument(InstrumentType.BANK_ACCOUNT, "VM-HDFCBK", "Rs.1,000 debited from A/c XX4444 to add money to Paytm Wallet.", "4444")
    }

    // --- UPI ---

    @Test
    fun `UPI with VPA and no account`() {
        assertInstrument(InstrumentType.UPI, "PHONPE", "Paid Rs.60 to swiggy@icici via PhonePe. UPI Ref 112233445566.")
    }

    @Test
    fun `VPA handle alone`() {
        assertInstrument(InstrumentType.UPI, "GPAY", "You sent Rs 250 to ravi@okaxis. Ref 998877.")
    }

    @Test
    fun `UPI naming the account debited is the bank account`() {
        assertInstrument(
            InstrumentType.BANK_ACCOUNT,
            "VK-HDFCBK",
            "Rs.500.00 debited from A/c XX5678 on 21-09-26 to VPA swiggy@icici. UPI Ref 987654321012.",
            "5678",
        )
    }

    // --- Bank accounts ---

    @Test
    fun `account wording`() {
        assertInstrument(InstrumentType.BANK_ACCOUNT, "AD-ICICIB", "INR 25,000.00 credited to your A/c XX9012 on 20-Sep-26. Info: NEFT-SALARY.", "9012")
    }

    @Test
    fun `international account ending`() {
        assertInstrument(InstrumentType.BANK_ACCOUNT, "HSBC", "GBP 1,200.00 received in your account ending 3030 from ACME LTD.", "3030")
    }

    @Test
    fun `bare mask with no wording is unknown`() {
        val txn = assertInstrument(InstrumentType.UNKNOWN, "VM-HDFCBK", "Rs.300 debited XXXX4321 at SHOP on 01-09-26.")
        assertEquals("4321", txn.last4)
    }
}
