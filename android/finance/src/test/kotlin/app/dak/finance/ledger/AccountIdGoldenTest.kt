package app.dak.finance.ledger

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import app.dak.finance.parser.TransactionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Account ids are persisted (ledger rows, aliases, user overrides, statement days are all keyed by them), so the id a
 * given SMS produces must never drift. These golden values pin the id format and the parser inputs that feed it; a
 * change here needs a migration (and a LOGIC_REVISION bump), not a test update.
 */
class AccountIdGoldenTest {

    @Test
    fun `id format is institution key, instrument name and visible digits`() {
        assertEquals("HDFC_BANK:BANK_ACCOUNT:1234", Account.idFor("HDFC Bank", InstrumentType.BANK_ACCOUNT, "1234"))
        assertEquals("AU_SMALL_FINANCE_BANK:CREDIT_CARD:5073", Account.idFor("AU Small Finance Bank", InstrumentType.CREDIT_CARD, "5073"))
        // Any whitespace run is one underscore; case is folded (locale-independently).
        assertEquals("IDFC_FIRST_BANK:UPI:9999", Account.idFor("idfc  first\tbank", InstrumentType.UPI, "9999"))
        assertEquals("UNKNOWN:WALLET:0000", Account.idFor(null, InstrumentType.WALLET, null))
        assertEquals("NEWBNK:LOAN:123456789", Account.idFor("NEWBNK", InstrumentType.LOAN, "123456789"))
        for (instrument in InstrumentType.entries) {
            assertEquals("X:${instrument.name}:1", Account.idFor("x", instrument, "1"))
        }
    }

    @Test
    fun `partsOf inverts idFor and rejects malformed ids`() {
        assertEquals(Triple("HDFC_BANK", "BANK_ACCOUNT", "440065"), Account.partsOf("HDFC_BANK:BANK_ACCOUNT:440065"))
        assertNull(Account.partsOf("HDFC_BANK:BANK_ACCOUNT"))
        assertNull(Account.partsOf("a:b:c:d"))
        assertNull(Account.partsOf(""))
    }

    private fun txn(
        instrument: InstrumentType,
        masked: String?,
        last4: String? = masked?.filter { it.isDigit() }?.takeLast(4),
        linked: String? = null,
    ) = ExtractedTransaction(
        direction = TransactionDirection.DEBIT,
        amountMinor = 100,
        currency = "INR",
        instrument = instrument,
        last4 = last4,
        institution = "HDFC Bank",
        maskedNumber = masked,
        linkedMaskedNumber = linked,
    )

    @Test
    fun `digitsOf uses every visible digit only when there are more than four`() {
        assertEquals("440065", Account.digitsOf(txn(InstrumentType.BANK_ACCOUNT, "XX440065")))
        assertEquals("1234", Account.digitsOf(txn(InstrumentType.BANK_ACCOUNT, "XXXX1234")))
        // A three-digit mask ("XX123") keeps last4 as the parser set it.
        assertEquals("123", Account.digitsOf(txn(InstrumentType.BANK_ACCOUNT, "XX123")))
        assertNull(Account.digitsOf(txn(InstrumentType.BANK_ACCOUNT, null)))
        assertEquals("HDFC_BANK:BANK_ACCOUNT:0000", Account.idOf(txn(InstrumentType.BANK_ACCOUNT, null)))
    }

    @Test
    fun `linkedIdOf only links debit cards and loans to a bank account with at least four digits`() {
        assertEquals(
            "HDFC_BANK:BANK_ACCOUNT:1234",
            Account.linkedIdOf(txn(InstrumentType.DEBIT_CARD, "XX5678", linked = "XX1234")),
        )
        assertEquals(
            "HDFC_BANK:BANK_ACCOUNT:440065",
            Account.linkedIdOf(txn(InstrumentType.LOAN, "XX9012", linked = "XX440065")),
        )
        assertNull(Account.linkedIdOf(txn(InstrumentType.CREDIT_CARD, "XX5678", linked = "XX1234")))
        assertNull(Account.linkedIdOf(txn(InstrumentType.BANK_ACCOUNT, "XX5678", linked = "XX1234")))
        assertNull(Account.linkedIdOf(txn(InstrumentType.DEBIT_CARD, "XX5678", linked = "XX123")))
        assertNull(Account.linkedIdOf(txn(InstrumentType.DEBIT_CARD, "XX5678", linked = null)))
    }

    /** (sender, body) -> the persisted account id. Real-world shapes; every name and number is made up. */
    private val golden = listOf(
        Triple("VM-HDFCBK", "Rs.1,499.00 spent on HDFC Bank Card XX1234 at AMAZON on 12-09-26. Avl Limit: Rs.45,320.00", "HDFC_BANK:CREDIT_CARD:1234"),
        Triple("VK-HDFCBK", "Rs.500.00 debited from A/c XX5678 on 21-09-26 to VPA swiggy@icici. UPI Ref 987654321012. Avl Bal Rs.12,345.67", "HDFC_BANK:BANK_ACCOUNT:5678"),
        Triple("AD-ICICIB", "INR 25,000.00 credited to your A/c XX9012 on 20-Sep-26. Info: NEFT-SALARY. Avl Bal: INR 48,210.55", "ICICI_BANK:BANK_ACCOUNT:9012"),
        Triple("AX-ICICIT", "Your ICICI Bank Credit Card XX4321 used for AED 120.50 at DUBAI MALL on 18-09-26.", "ICICI_BANK:CREDIT_CARD:4321"),
        Triple("ATMSBI", "Dear Customer, Rs.2000.00 withdrawn at ATM from A/c XX7788 on 19-09-26. Avl Bal Rs.5,432.10", "STATE_BANK_OF_INDIA:BANK_ACCOUNT:7788"),
        Triple("VM-HDFCBK", "Rs.500.00 debited from A/c XX440065 on 12-09-26. Avl Bal Rs.1,000.00", "HDFC_BANK:BANK_ACCOUNT:440065"),
        Triple("VM-ICICIB", "INR 250.00 debited from A/c **5678 on 01-Sep. Avl Bal INR 900.00", "ICICI_BANK:BANK_ACCOUNT:5678"),
        Triple("VM-AXISBK", "INR 120.00 spent on Axis Bank Credit Card ending 7788 at CAFE on 01-09-26.", "AXIS_BANK:CREDIT_CARD:7788"),
        Triple(
            "AX-AUBANK-S",
            "Credited INR 50,000.00 to A/c X5073 on 06-AUG-2026 Ref IMPS-62181 -ABC XYZ -SBIN. Bal INR 1,00,000.00.\n-AU Bank",
            "AU_SMALL_FINANCE_BANK:BANK_ACCOUNT:5073",
        ),
        Triple("AX-NEWBNK-S", "Rs 500 debited from A/c XX1234 on 07-12-24.", "NEWBNK:BANK_ACCOUNT:1234"),
        Triple("PAYTMB", "Rs.150.00 paid to Chai Point from Paytm Wallet. Txn ID T2609211234.", "PAYTM_PAYMENTS_BANK:WALLET:0000"),
        Triple("PHONPE", "You sent Rs.350.00 to Ramesh Kumar via PhonePe UPI. UPI Ref No. 334455667788.", "PHONEPE:UPI:0000"),
        Triple("+919812345678", "Rs 500 debited from A/c XX1234 on 07-12-24.", "UNKNOWN:BANK_ACCOUNT:1234"),
    )

    @Test
    fun `parsed SMS keep their golden account ids`() {
        val problems = golden.mapNotNull { (sender, body, id) ->
            val txn = TransactionParser.parse(sender, body) ?: return@mapNotNull "[$sender] no transaction: $body"
            val actual = Account.idOf(txn)
            if (actual != id) "[$sender] expected $id got $actual: $body" else null
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `the same account written with a different mask style keeps one id`() {
        val bodies = listOf(
            "Rs 500 debited from A/c XX1234 on 07-12-24.",
            "Rs 500 debited from A/c **1234 on 07-12-24.",
            "Rs 500 debited from A/c xxxx1234 on 07-12-24.",
            "Rs 500 debited from A/c no. XX1234 on 07-12-24.",
            "Rs 500 debited from a/c ending 1234 on 07-12-24.",
            "Rs 500 debited from A/c XX१२३४ on 07-12-24.",
        )
        val ids = bodies.map { body ->
            val txn = TransactionParser.parse("VM-HDFCBK", body) ?: fail("no transaction: $body")
            Account.idOf(txn)
        }
        assertEquals(listOf("HDFC_BANK:BANK_ACCOUNT:1234"), ids.distinct(), ids.toString())
    }

    @Test
    fun `every DLT prefix and route suffix of one bank maps to the same institution`() {
        val ids = listOf("VM-HDFCBK", "AD-HDFCBK", "JD-HDFCBK-S", "VK-HDFCBK-T", "HDFCBK").map { sender ->
            Account.idOf(TransactionParser.parse(sender, "Rs 500 debited from A/c XX1234 on 07-12-24.") ?: fail(sender))
        }
        assertEquals(listOf("HDFC_BANK:BANK_ACCOUNT:1234"), ids.distinct())
    }
}
