package app.dak.finance.parser

import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TransactionParserTest {

    // --- HDFC ---

    @Test
    fun `HDFC debit card spend at merchant`() {
        val txn = TransactionParser.parse(
            "VM-HDFCBK",
            "Rs.1,499.00 spent on HDFC Bank Card XX1234 at AMAZON on 12-09-26. Avl Limit: Rs.45,320.00",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(149900L, txn.amountMinor)
        assertEquals("INR", txn.currency)
        assertEquals("1234", txn.last4)
        assertEquals(InstrumentType.CREDIT_CARD, txn.instrument)
        assertEquals("HDFC Bank", txn.institution)
        assertEquals("AMAZON", txn.merchant)
    }

    @Test
    fun `HDFC UPI debit with available balance`() {
        val txn = TransactionParser.parse(
            "VK-HDFCBK",
            "Rs.500.00 debited from A/c XX5678 on 21-09-26 to VPA swiggy@icici. UPI Ref 987654321012. Avl Bal Rs.12,345.67",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(50000L, txn.amountMinor)
        assertEquals("5678", txn.last4)
        assertEquals(InstrumentType.UPI, txn.instrument)
        assertEquals("swiggy@icici", txn.merchant)
        assertEquals("987654321012", txn.reference)
        assertEquals(1234567L, txn.balanceMinor)
        assertEquals("INR", txn.balanceCurrency)
        assertEquals("HDFC Bank", txn.institution)
    }

    // --- ICICI ---

    @Test
    fun `ICICI credit to account`() {
        val txn = TransactionParser.parse(
            "AD-ICICIB",
            "INR 25,000.00 credited to your A/c XX9012 on 20-Sep-26. Info: NEFT-SALARY. Avl Bal: INR 48,210.55",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.CREDIT, txn.direction)
        assertEquals(2500000L, txn.amountMinor)
        assertEquals("9012", txn.last4)
        assertEquals(InstrumentType.BANK_ACCOUNT, txn.instrument)
        assertEquals("NEFT-SALARY", txn.merchant)
        assertEquals(4821055L, txn.balanceMinor)
        assertEquals("ICICI Bank", txn.institution)
    }

    @Test
    fun `ICICI foreign currency credit card spend`() {
        val txn = TransactionParser.parse(
            "AX-ICICIT",
            "Your ICICI Bank Credit Card XX4321 used for AED 120.50 at DUBAI MALL on 18-09-26.",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(12050L, txn.amountMinor)
        assertEquals("AED", txn.currency)
        assertEquals("4321", txn.last4)
        assertEquals(InstrumentType.CREDIT_CARD, txn.instrument)
        assertEquals("DUBAI MALL", txn.merchant)
        assertEquals("ICICI Bank", txn.institution)
        assertNull(txn.balanceMinor)
    }

    @Test
    fun `ICICI settlement of the foreign card spend in INR`() {
        val txn = TransactionParser.parse(
            "AX-ICICIT",
            "Rs.10,850.00 (AED 120.50) debited on ICICI Bank Credit Card XX4321 at DUBAI MALL on 20-09-26.",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(1085000L, txn.amountMinor)
        assertEquals("INR", txn.currency)
        assertEquals("4321", txn.last4)
    }

    // --- SBI ---

    @Test
    fun `SBI ATM withdrawal`() {
        val txn = TransactionParser.parse(
            "ATMSBI",
            "Dear Customer, Rs.2000.00 withdrawn at ATM from A/c XX7788 on 19-09-26. Avl Bal Rs.5,432.10",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(200000L, txn.amountMinor)
        assertEquals("7788", txn.last4)
        assertEquals("State Bank of India", txn.institution)
        assertEquals(543210L, txn.balanceMinor)
    }

    @Test
    fun `SBI UPI received`() {
        val txn = TransactionParser.parse(
            "SBIUPI",
            "Rs.1,200.00 received in your A/c XX3344 via UPI from rahul@sbi. Ref No 445566778899.",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.CREDIT, txn.direction)
        assertEquals(InstrumentType.UPI, txn.instrument)
        assertEquals("445566778899", txn.reference)
    }

    // --- Axis ---

    @Test
    fun `Axis EMI auto-debit`() {
        val txn = TransactionParser.parse(
            "AXISBK",
            "EMI of Rs.4,599.00 auto-debited from A/c XX2211 towards Loan a/c on 05-09-26. Avl Bal Rs.22,000.00",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(459900L, txn.amountMinor)
        assertEquals("Axis Bank", txn.institution)
    }

    // --- Kotak ---

    @Test
    fun `Kotak card purchase`() {
        val txn = TransactionParser.parse(
            "KOTAKB",
            "Purchase of Rs.899.00 done on your Kotak Credit Card ending 5566 at NETFLIX on 15-09-26.",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(89900L, txn.amountMinor)
        assertEquals("5566", txn.last4)
        assertEquals(InstrumentType.CREDIT_CARD, txn.instrument)
        assertEquals("Kotak Mahindra Bank", txn.institution)
        assertEquals("NETFLIX", txn.merchant)
    }

    // --- Paytm / PhonePe / Amazon Pay wallets ---

    @Test
    fun `Paytm wallet debit`() {
        val txn = TransactionParser.parse(
            "PAYTMB",
            "Rs.150.00 paid to Chai Point from Paytm Wallet. Txn ID T2609211234.",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(15000L, txn.amountMinor)
        assertEquals(InstrumentType.WALLET, txn.instrument)
        assertEquals("Paytm Payments Bank", txn.institution)
        assertEquals("T2609211234", txn.reference)
    }

    @Test
    fun `PhonePe UPI sent`() {
        val txn = TransactionParser.parse(
            "PHONPE",
            "You sent Rs.350.00 to Ramesh Kumar via PhonePe UPI. UPI Ref No. 334455667788.",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals(35000L, txn.amountMinor)
        assertEquals(InstrumentType.UPI, txn.instrument)
        assertEquals("PhonePe", txn.institution)
    }

    @Test
    fun `Amazon Pay balance credit`() {
        val txn = TransactionParser.parse(
            "AMZNPB",
            "Rs.200.00 credited to your Amazon Pay balance as refund for order #123-4567890.",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.CREDIT, txn.direction)
        assertEquals("Amazon Pay", txn.institution)
    }

    // --- Negative cases ---

    @Test
    fun `OTP mentioning an amount is not a transaction`() {
        val txn = TransactionParser.parse("VM-HDFCBK", "OTP for txn of Rs 500 at AMAZON is 123456. Valid for 10 mins.")
        assertNull(txn)
    }

    @Test
    fun `promotional cashback offer is not a transaction`() {
        val txn = TransactionParser.parse("VM-HDFCBK", "Get cashback up to Rs.500 on your next credit card spend. T&C apply.")
        assertNull(txn)
    }

    @Test
    fun `bill reminder is not a transaction`() {
        val txn = TransactionParser.parse(
            "VM-HDFCBK",
            "Your HDFC Bank Credit Card XX1234 bill of Rs.12,000.00 is due on 25-09-26. Minimum amount due: Rs.1,200.00",
        )
        assertNull(txn)
    }

    @Test
    fun `bill reminder can be parsed separately as BillReminder`() {
        val reminder = TransactionParser.parseBillReminder(
            "VM-HDFCBK",
            "Your HDFC Bank Credit Card XX1234 bill of Rs.12,000.00 is due on 25-09-26. Minimum amount due: Rs.1,200.00",
        )
        assertNotNull(reminder)
        assertEquals(1200000L, reminder.amountMinor)
        assertEquals("HDFC Bank", reminder.institution)
    }

    @Test
    fun `plain OTP with no amount is not a transaction`() {
        val txn = TransactionParser.parse("VM-HDFCBK", "123456 is your OTP for login. Do not share it with anyone.")
        assertNull(txn)
    }

    @Test
    fun `non-financial promotional message is not a transaction`() {
        val txn = TransactionParser.parse("VM-AMAZNP", "Hurry! Flat 50% off on electronics. Download the app now.")
        assertNull(txn)
    }
}
