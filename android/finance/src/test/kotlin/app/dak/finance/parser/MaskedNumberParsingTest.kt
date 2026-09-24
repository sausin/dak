package app.dak.finance.parser

import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class MaskedNumberParsingTest {

    @Test
    fun `keeps every visible digit of a long mask`() {
        val txn = TransactionParser.parse("VM-HDFCBK", "Rs.500.00 debited from A/c XX440065 on 12-09-26. Avl Bal Rs.1,000.00")
        assertNotNull(txn)
        assertEquals("XX440065", txn.maskedNumber)
        assertEquals("0065", txn.last4)
        assertEquals("HDFC_BANK:BANK_ACCOUNT:440065", Account.idOf(txn))
    }

    @Test
    fun `shorter mask of the same account gets its own id`() {
        val before = TransactionParser.parse("VM-HDFCBK", "Rs.500.00 debited from A/c XX440065 on 12-09-26.")
        val after = TransactionParser.parse("VM-HDFCBK", "Rs.200.00 debited from A/c XX40065 on 14-09-26.")
        assertNotNull(before)
        assertNotNull(after)
        assertEquals("XX40065", after.maskedNumber)
        assertEquals(before.last4, after.last4)
        assertNotEquals(Account.idOf(before), Account.idOf(after))
    }

    @Test
    fun `different accounts sharing a last-4 do not collide`() {
        val a = TransactionParser.parse("VM-HDFCBK", "Rs.500.00 debited from A/c XX440065 on 12-09-26.")
        val b = TransactionParser.parse("VM-HDFCBK", "Rs.500.00 debited from A/c XX120065 on 12-09-26.")
        assertNotNull(a)
        assertNotNull(b)
        assertNotEquals(Account.idOf(a), Account.idOf(b))
    }

    @Test
    fun `four digit masks keep the old ids`() {
        val txn = TransactionParser.parse("VM-HDFCBK", "Rs.1,499.00 spent on HDFC Bank Card XX1234 at AMAZON on 12-09-26.")
        assertNotNull(txn)
        assertEquals("XX1234", txn.maskedNumber)
        assertEquals(Account.idFor("HDFC Bank", txn.instrument, "1234"), Account.idOf(txn))
    }

    @Test
    fun `star masks are normalised`() {
        val txn = TransactionParser.parse("VM-ICICIB", "INR 250.00 debited from A/c **5678 on 01-Sep. Avl Bal INR 900.00")
        assertNotNull(txn)
        assertEquals("5678", txn.last4)
        assertEquals("XX5678", txn.maskedNumber)
    }

    @Test
    fun `ending in form has no mask`() {
        val txn = TransactionParser.parse("VM-AXISBK", "INR 120.00 spent on Axis Bank Credit Card ending 7788 at CAFE on 01-09-26.")
        assertNotNull(txn)
        assertEquals("7788", txn.maskedNumber)
        assertEquals("7788", txn.last4)
    }

    @Test
    fun `single mask character after A-c is an account number (AU Bank)`() {
        val txn = TransactionParser.parse(
            "AX-AUBANK-S",
            "Credited INR 50,000.00 to A/c X5073 on 06-AUG-2026 Ref IMPS-62181 -ABC XYZ -SBIN. Bal INR 1,00,000.00.\n-AU Bank",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.CREDIT, txn.direction)
        assertEquals(5_000_000L, txn.amountMinor)
        assertEquals(10_000_000L, txn.balanceMinor)
        assertEquals(InstrumentType.BANK_ACCOUNT, txn.instrument)
        assertEquals("X5073", txn.maskedNumber)
        assertEquals("5073", txn.last4)
        assertEquals("AU Small Finance Bank", txn.institution)
    }

    @Test
    fun `a lone X-number without an account keyword is not an account`() {
        val txn = TransactionParser.parse("VM-HDFCBK", "Rs.500.00 debited for order X12345 on 12-09-26.")
        assertEquals(null, txn?.maskedNumber)
    }
}
