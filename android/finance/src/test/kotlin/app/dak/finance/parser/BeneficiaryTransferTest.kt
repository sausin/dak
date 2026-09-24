package app.dak.finance.parser

import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The other party's account in a transfer message is never one of the user's accounts. */
class BeneficiaryTransferTest {

    @Test
    fun `beneficiary credit confirmation without the user's account is not a transaction`() {
        assertNull(
            TransactionParser.parse(
                "AX-AUBANK-S",
                "Confirmation! INR 100,000.00 credited to beneficiary A/c XX5632 for your NEFT on 07-Dec-2024 at 08:02 PM. Ref N34224216. - AU BANK",
            ),
        )
        assertNull(TransactionParser.parse("VM-NEWBNK-T", "Rs 2,500 has been credited to the payee Ramesh via IMPS. Ref 123456."))
        assertNull(TransactionParser.parse("VM-NEWBNK-T", "Beneficiary A/c XX5632 credited with Rs 2,500 via IMPS on 07-12-24."))
        assertNull(TransactionParser.parse("VM-NEWBNK-T", "Rs 2,500 credited to recipient account ending 5632 via IMPS."))
    }

    @Test
    fun `beneficiary credit naming the user's account is that account's debit`() {
        val txn = TransactionParser.parse(
            "VM-NEWBNK-T",
            "INR 5,000.00 credited to beneficiary A/c XX5632 from your A/c XX1234 via NEFT on 07-12-24.",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals("XX1234", txn.maskedNumber)
    }

    @Test
    fun `payee account written first does not become the user's account`() {
        val txn = TransactionParser.parse(
            "VM-NEWBNK-T",
            "IMPS of Rs 2,000 to payee account XX9876 debited from A/c XX1234 on 07-12-24. Ref 612345.",
        )
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertEquals("XX1234", txn.maskedNumber)

        val beneFirst = TransactionParser.parse("VM-NEWBNK-T", "Rs 500 debited from A/c XX1234 to beneficiary A/c XX5632 via NEFT.")
        assertNotNull(beneFirst)
        assertEquals("XX1234", beneFirst.maskedNumber)
    }

    @Test
    fun `a debit naming only the payee's account has no account of the user's`() {
        val txn = TransactionParser.parse("VM-NEWBNK-T", "NEFT of Rs 500 to beneficiary A/c XX5632 has been debited on 07-12-24.")
        assertNotNull(txn)
        assertEquals(TransactionDirection.DEBIT, txn.direction)
        assertNull(txn.maskedNumber)
    }

    @Test
    fun `own credits are unaffected`() {
        val txn = TransactionParser.parse("AX-AUBANK-S", "Credited INR 50,000.00 to A/c X5073 on 06-AUG-2026 Ref IMPS-62181 -ABC XYZ -SBIN.")
        assertNotNull(txn)
        assertEquals(TransactionDirection.CREDIT, txn.direction)
        assertEquals("X5073", txn.maskedNumber)
    }

    @Test
    fun `a bank missing from the table still gets its own accounts`() {
        assertEquals("NEWBNK", InstitutionTable.institutionFor("AX-NEWBNK-S"))
        assertEquals("NEWBNK", InstitutionTable.institutionFor("VM-NEWBNK"))
        assertNull(InstitutionTable.institutionFor("+919812300000"))
        val a = TransactionParser.parse("AX-NEWBNK-S", "Rs 500 debited from A/c XX1234 on 07-12-24.")
        val b = TransactionParser.parse("AX-OTHRBK-S", "Rs 500 debited from A/c XX1234 on 07-12-24.")
        assertNotNull(a)
        assertNotNull(b)
        assertEquals("NEWBNK:BANK_ACCOUNT:1234", Account.idOf(a))
        assertEquals("OTHRBK:BANK_ACCOUNT:1234", Account.idOf(b))
    }
}
