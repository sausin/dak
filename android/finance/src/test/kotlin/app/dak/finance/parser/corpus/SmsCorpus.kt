package app.dak.finance.parser.corpus

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.Money
import java.math.BigDecimal

/**
 * One corpus message and what the parser must make of it. Every sender header, bank name, person and merchant here
 * is made up; the wording, ordering, casing, punctuation and abbreviations follow the many styles real Indian (and a
 * few foreign) bank / card / UPI / wallet / loan SMS use.
 *
 * [expect] null = not a completed transaction (the parser must return null).
 */
internal data class CorpusCase(val sender: String, val body: String, val expect: Expect?)

/** A field the case does not check. */
internal const val ANY = "\u0000any"

internal data class Expect(
    val direction: TransactionDirection,
    /** Amount in major units as written ("1,23,456.78" style grouping removed). */
    val amount: String,
    val currency: String = "INR",
    val instrument: InstrumentType? = null,
    /** The user's own account/card number as normalised by the parser; null = none may be recorded. */
    val masked: String? = ANY,
    val linked: String? = ANY,
    /** Balance in major units, in [balanceCurrency]; null = no balance may be recorded. */
    val balance: String? = ANY,
    val balanceCurrency: String = currency,
    val merchant: String? = ANY,
    /** Digits of the other party's account(s): they must never become the user's number (masked or linked). */
    val notOwn: List<String> = emptyList(),
) {
    fun on(instrument: InstrumentType, masked: String? = ANY) = copy(instrument = instrument, masked = masked)
    fun linked(number: String?) = copy(linked = number)
    fun bal(amount: String?, currency: String = this.currency) = copy(balance = amount, balanceCurrency = currency)
    fun merchant(name: String?) = copy(merchant = name)
    fun notOwn(vararg digits: String) = copy(notOwn = digits.toList())

    /** Every mismatch between [actual] and this expectation, as readable lines. */
    fun mismatches(actual: ExtractedTransaction): List<String> {
        val out = ArrayList<String>()
        fun check(what: String, want: Any?, got: Any?) {
            if (want != got) out += "$what: expected <$want> got <$got>"
        }
        check("direction", direction, actual.direction)
        check("amount", minor(amount, currency), actual.amountMinor)
        check("currency", currency, actual.currency)
        instrument?.let { check("instrument", it, actual.instrument) }
        if (masked != ANY) check("maskedNumber", masked, actual.maskedNumber)
        if (linked != ANY) check("linkedMaskedNumber", linked, actual.linkedMaskedNumber)
        if (balance != ANY) {
            check("balance", balance?.let { minor(it, balanceCurrency) }, actual.balanceMinor)
            if (balance != null) check("balanceCurrency", balanceCurrency, actual.balanceCurrency)
        }
        if (merchant != ANY) check("merchant", merchant, actual.merchant)
        for (digits in notOwn) {
            if (actual.maskedNumber?.endsWith(digits) == true || actual.linkedMaskedNumber?.endsWith(digits) == true) {
                out += "the other party's account $digits became the user's (masked=${actual.maskedNumber}, linked=${actual.linkedMaskedNumber})"
            }
        }
        return out
    }

    private fun minor(major: String, currency: String): Long = Money.ofMajor(BigDecimal(major), currency).amountMinor
}

internal fun debit(amount: String, currency: String = "INR") = Expect(TransactionDirection.DEBIT, amount, currency)
internal fun credit(amount: String, currency: String = "INR") = Expect(TransactionDirection.CREDIT, amount, currency)

internal val BANK = InstrumentType.BANK_ACCOUNT
internal val CC = InstrumentType.CREDIT_CARD
internal val DC = InstrumentType.DEBIT_CARD
internal val PREPAID = InstrumentType.PREPAID_CARD
internal val WALLET = InstrumentType.WALLET
internal val UPI = InstrumentType.UPI
internal val LOAN = InstrumentType.LOAN

internal fun case(sender: String, body: String, expect: Expect?) = CorpusCase(sender, body, expect)

/** The corpus, by category. Keys are category names used in failure reports. */
internal object SmsCorpus {

    val accountDebits = listOf(
        case(
            "VM-NOVABK-S",
            "Rs.500.00 debited from A/c XX1234 on 12-09-26 to VPA ravi.k@okhdfc. UPI Ref 612345678901. Avl Bal Rs.12,345.67",
            debit("500").on(BANK, "XX1234").bal("12345.67").merchant("ravi.k@okhdfc"),
        ),
        case(
            "AD-PQRBNK",
            "INR 2,450.00 debited from your A/c no. XXXXXXXX4821 on 05-Sep-2026 for NEFT to SURESH TRADERS. Ref no N2489123456. Available Balance INR 1,02,340.50",
            debit("2450").on(BANK, "XXXXXXXX4821").bal("102340.50"),
        ),
        case(
            "JD-QRSBNK-S",
            "Dear Customer, your Ac XXXX7732 has been debited with INR 1,200.00 on 12/09/2026 10:32:11 towards IMPS/612309876543/MEERA. Clr Bal INR 8,806.20",
            debit("1200").on(BANK, "XXXX7732").bal("8806.20"),
        ),
        case(
            "VK-ABCBNK",
            "Your a/c *3319 is debited for Rs 3,000 on 11SEP26 by ATM WDL at MG ROAD. Avail Bal Rs 15,040.00",
            debit("3000").on(BANK, "X3319").bal("15040"),
        ),
        case(
            "AX-QRSBNK-S",
            "A/C X5073 Debited INR 750.00 on 06-09-2026 13:44 via UPI Ref 624812345678. Bal INR 9,250.00 - QRS Bank",
            debit("750").on(BANK, "X5073").bal("9250"),
        ),
        case(
            "VM-LMNBNK-T",
            "Acct XX905 debited for Rs 120.00 on 13-Sep-26; SHARMA STORES credited. UPI:624812349999. Call 18001234567 for dispute.",
            debit("120").on(BANK, "XX905").bal(null),
        ),
        case(
            "BZ-UVWBNK",
            "Rs:500.00 debited from A/c *8812 on 12-09-2026 by Mob Bk ref no 612341234123 Avl Bal Rs:4,210.00. Never share OTP/PIN/CVV with anyone.",
            debit("500").on(BANK, "X8812").bal("4210"),
        ),
        case(
            "VM-CNRBNK",
            "An amount of INR 1,999.00 has been DEBITED to your account XXX4455 on 14/09/2026 towards UPI/624899887766/FLIPMART. Total Avail.bal INR 22,001.00.",
            debit("1999").on(BANK, "XXX4455").bal("22001"),
        ),
        case(
            "VM-INDSBK",
            "Your A/C 2XXXXX6789 has been debited by INR 5,000.00 on 10/09/2026 towards Cheque No. 000123. New Bal: INR 45,000.00",
            debit("5000").on(BANK, "XXXXX6789").bal("45000"),
        ),
        case(
            "VM-ABCBNK",
            "Rs 5,000.00 withdrawn from A/c ending 2211 at ATM ID S1A2B3 on 12-09-26. Avl bal Rs 1,23,456.00",
            debit("5000").on(BANK, "2211").bal("123456"),
        ),
        case(
            "AD-PQRBNK-S",
            "INR 500.00 debited A/c no. XX1234 12-09-26, 10:30:00 UPI/P2A/624812340000/RAVI K Not you? SMS BLOCKUPI Cust ID to 919999900000 - PQR Bank",
            debit("500").on(BANK, "XX1234").bal(null),
        ),
        case(
            "VM-PNSBNK",
            "Ac XXXXXXXX5566 debited INR 250.00 on 12-09-26 thru UPI:624812345111. Bal INR 3,000.00 CR. Not you? call 18001801234",
            debit("250").on(BANK, "XXXXXXXX5566").bal("3000"),
        ),
        case(
            "VM-BRDBNK",
            "Rs.500 Dr. from A/C XXXXXX8899 and Cr. to vendor@oksbi. Ref:624812347777. AvlBal:Rs4,500.00(12 Sep 2026 10:30). Not you? Call 18005700000",
            debit("500").on(BANK, "XXXXXX8899").bal("4500"),
        ),
        case(
            "VM-NOVABK",
            "Sent Rs.349.00 From NOVA Bank A/C x4321 To ZIPCART On 12/09/26 Ref 624812341234 Not You? Call 18001230000/SMS BLOCK UPI to 7300000000",
            debit("349").on(BANK, "X4321").merchant("ZIPCART"),
        ),
        case(
            "VM-XYZBNK",
            "Dear UPI user A/C X9876 debited by 250.0 on date 12Sep26 trf to ANITA DEVI Refno 624812345555. If not u? call 1800111000. -XYZ Bank",
            debit("250").on(BANK, "X9876"),
        ),
        case(
            "VM-ABCBNK-S",
            "Your account XX6655 has been debited by Rs 15,000.00 on 01-Sep-2026 towards SIP - FUNDHOUSE MF. Available balance: Rs 48,210.10",
            debit("15000").on(BANK, "XX6655").bal("48210.10"),
        ),
        case(
            "VM-PQRBNK",
            "Txn of INR 899.00 debited from Savings Account XX3434 on 09-Sep-26 for ACH D- STREAMFLIX. Avl bal INR 10,101.00",
            debit("899").on(BANK, "XX3434").bal("10101"),
        ),
        case(
            "VM-LMNBNK",
            "Alert: INR 12,000.00 has been debited from your account ending with 7788 on 2026-09-12 via NEFT. Ref: N255123456789. Balance is INR 88,000.00",
            debit("12000").on(BANK, "7788").bal("88000"),
        ),
        case(
            "VM-QRSBNK",
            "Rs 2,000 debited from a/c **4321 on 12-SEP-2026 (UPI Ref: 624812340001). Avl Bal: Rs 7,654.32",
            debit("2000").on(BANK, "XX4321").bal("7654.32"),
        ),
        case(
            "VM-NOVABK",
            "Money Transfer:Rs 1,500.00 from NOVA Bank A/c XX3456 on 12-09-26 to Ramesh via UPI Ref 624812349876. Not you? Call 18001230000",
            debit("1500").on(BANK, "XX3456"),
        ),
        case(
            "AD-PQRBNK",
            "Debit INR 5000.00 A/c no. XX1234 12-09-26 10:30:00 IMPS/P2A/624812340000/ANIL",
            debit("5000").on(BANK, "XX1234"),
        ),
        case(
            "VM-ABCBNK",
            "Your A/c XX2468 is debited with INR 12.50 on 12-09-26 towards SMS charges. Avl Bal INR 5,012.50",
            debit("12.50").on(BANK, "XX2468").bal("5012.50"),
        ),
        case(
            "VM-NOVABK",
            "Rs 500 debited from A/c XX1234 on 12-09. Never share your OTP, PIN or CVV with anyone. -NOVA",
            debit("500").on(BANK, "XX1234"),
        ),
    )

    val accountCredits = listOf(
        case(
            "AD-PQRBNK",
            "INR 25,000.00 credited to your A/c XX9012 on 20-Sep-26. Info: NEFT-SALARY ACME. Avl Bal: INR 48,210.55",
            credit("25000").on(BANK, "XX9012").bal("48210.55").merchant("NEFT-SALARY ACME"),
        ),
        case(
            "AX-QRSBNK-S",
            "Credited INR 50,000.00 to A/c X5073 on 06-AUG-2026 Ref IMPS-62181 -ABC XYZ -NEFT. Bal INR 1,00,000.00.",
            credit("50000").on(BANK, "X5073").bal("100000"),
        ),
        case(
            "VM-ABCBNK",
            "Rs.1,200.00 received in your A/c XX3344 via UPI from rahul.m@okicici. Ref No 445566778899.",
            credit("1200").on(BANK, "XX3344").merchant("rahul.m@okicici"),
        ),
        case(
            "VM-ABCBNK",
            "Your A/c XXXXXXXX1234 is credited by Rs.500.00 on 12-09-26 by A/c linked to VPA priya@oksbi (UPI Ref no 624812345678).",
            credit("500").on(BANK, "XXXXXXXX1234"),
        ),
        case(
            "VM-NOVABK",
            "Update! INR 5,000.00 deposited in NOVA Bank A/c XX1234 on 12-SEP-26 for NEFT Cr-PQRS0001234-ACME CORP-XXXX. Avl bal INR 55,000.00.",
            credit("5000").on(BANK, "XX1234").bal("55000"),
        ),
        case(
            "VM-QRSBNK",
            "Dear Customer, Acct XX123 is credited with Rs 7,500.00 on 12-Sep-26 from MEENA S. UPI:624812341111-QRS Bank.",
            credit("7500").on(BANK, "XX123"),
        ),
        case(
            "VM-NOVABK",
            "Received Rs.500.00 in your NOVA Bank AC X1234 from ravi@okaxis on 12-09-26.UPI Ref:624812345678.",
            credit("500").on(BANK, "X1234").merchant("ravi@okaxis"),
        ),
        case(
            "VM-XYZBNK",
            "ur A/cX1234 credited by Rs500 on 12Sep26 by (Ref no 624812341234). -XYZ Bank",
            credit("500").on(BANK, "X1234"),
        ),
        case(
            "VM-ABCBNK",
            "Your a/c no. XXXXXXXX7788 is credited for Rs.10,000.00 on 12-09-26 and a/c XXXXXXXX1122 debited (IMPS Ref no 624812345678).",
            credit("10000").on(BANK, "XXXXXXXX7788").notOwn("1122"),
        ),
        case(
            "VM-PQRBNK",
            "INR 1,00,000.00 credited to A/c no. XX4455 on 12-09-2026 by a/c XX9911 (IMPS Ref 624812340000). Avl Bal INR 1,50,000.00",
            credit("100000").on(BANK, "XX4455").bal("150000").notOwn("9911"),
        ),
        case(
            "VM-LMNBNK",
            "Rs 3,500.00 has been credited to your account ending 5051 on 12/09/2026 by UPI from sunita@okaxis. Balance Rs 13,500.00",
            credit("3500").on(BANK, "5051").bal("13500"),
        ),
        case(
            "VM-ABCBNK",
            "Salary of INR 85,000.00 credited to a/c XX7766 on 01-09-26. Avl Bal INR 91,234.00",
            credit("85000").on(BANK, "XX7766").bal("91234"),
        ),
        case(
            "VM-CNRBNK",
            "Dear Customer, Rs.15,000 is credited in your SB A/C XXXX2233 on 12.09.2026 through NEFT with UTR N255260001234 by ACME PVT LTD. Clear Bal Rs.20,500.00",
            credit("15000").on(BANK, "XXXX2233").bal("20500"),
        ),
        case(
            "VM-PQRBNK",
            "Interest of Rs 123.45 credited to your A/c XX9012 on 30-09-26. Avl Bal Rs 45,123.45",
            credit("123.45").on(BANK, "XX9012").bal("45123.45"),
        ),
        case(
            "VM-ABCBNK",
            "Cash deposit of Rs 20,000.00 in A/c XX3344 on 12-09-26 at branch. Avl bal Rs 32,000.00",
            credit("20000").on(BANK, "XX3344").bal("32000"),
        ),
        case(
            "VM-LMNBNK",
            "INR 2,000.00 Cr to A/c XX1234 on 12/09/26 by IMPS from ASHA. Avl Bal INR 2,500.00",
            credit("2000").on(BANK, "XX1234").bal("2500"),
        ),
        case(
            "VM-NOVABK",
            "You have received Rs 750 from Kiran (kiran@ybl) in your A/c XX5678. UPI Ref 624812345678",
            credit("750").on(BANK, "XX5678"),
        ),
        case(
            "VM-NOVABK",
            "INR 1,000 credited to A/c XX1234 on 12-09-26. Bank never asks for OTP or PIN.",
            credit("1000").on(BANK, "XX1234"),
        ),
    )

    val upi = listOf(
        case(
            "VM-QPAYAP",
            "You sent Rs.350.00 to Ramesh Kumar via QPay UPI. UPI Ref No. 334455667788.",
            debit("350").on(UPI, null),
        ),
        case(
            "VM-QPAYAP",
            "Paid Rs.60 to foodhub@okicici via QPay. UPI Ref 112233445566.",
            debit("60").on(UPI, null).merchant("foodhub@okicici"),
        ),
        case(
            "VM-NOVABK",
            "₹1,250 paid to GREEN GROCERS from your NOVA Bank A/c XX1234. UPI Ref: 624812340099",
            debit("1250").on(BANK, "XX1234"),
        ),
        case(
            "VM-QPAYAP",
            "Received ₹500 from Kiran Rao on QPay. Money credited to your bank account XX4321. UPI Ref 624812345678",
            credit("500").on(BANK, "XX4321"),
        ),
        case(
            "VM-ABCBNK",
            "Rs. 200.00 sent to anil@ybl from A/c XX8765 on 12-09-2026. UPI Ref: 624812341212",
            debit("200").on(BANK, "XX8765").merchant("anil@ybl"),
        ),
        case(
            "VM-QPAYAP",
            "Your UPI payment of Rs 99.00 to STREAMFLIX is successful. UPI Ref 624812345678. -QPay",
            debit("99").on(UPI, null),
        ),
        case(
            "VM-QPAYAP",
            "Kiran has sent you Rs 300 on QPay UPI. Ref 624812345678",
            credit("300").on(UPI, null),
        ),
        case(
            "VM-LMNBNK",
            "Money received! ₹2,000 from VPA mohan@okaxis to your A/c XX1122 on 12-09-26. Ref 624812345678",
            credit("2000").on(BANK, "XX1122").merchant("mohan@okaxis"),
        ),
        case(
            "VM-ABCBNK",
            "INR 150.00 debited from A/c XX7788 on 12-09-26 towards UPI txn to VPA chaiwala@paytm. Ref 624812345678. Avl Bal INR 4,850.00",
            debit("150").on(BANK, "XX7788").bal("4850").merchant("chaiwala@paytm"),
        ),
        case(
            "VM-QPAYAP",
            "You've paid ₹75 to Ramu Tea Stall. Paid from ABC Bank a/c ****1234 via UPI. Ref 624812345678",
            debit("75").on(BANK, "XXXX1234"),
        ),
    )

    val debitCards = listOf(
        case(
            "VM-NOVABK",
            "Rs 850.00 spent using Nova Bank Debit Card XX1234 at FOODHUB on 12-Sep-26. Avl Bal Rs 9,150.00",
            debit("850").on(DC, "XX1234").bal("9150").merchant("FOODHUB"),
        ),
        case(
            "VM-ABCBNK",
            "Your Debit Card ending 4455 has been used for INR 1,299.00 at SHOPNOW on 12-09-2026 10:30. If not done by you, call 18001230000",
            debit("1299").on(DC, "4455").merchant("SHOPNOW"),
        ),
        case(
            "VM-NOVABK",
            "Rs.2,500.00 debited from A/c XX1234 using Debit Card XX5678 at CROMARTS on 12-09-26. Avl Bal Rs.40,000.00",
            debit("2500").on(DC, "XX5678").linked("XX1234").bal("40000"),
        ),
        case(
            "VM-PQRBNK",
            "Txn of Rs 640.00 on your DC XX9911 at METROCAB on 02-09-26 from A/c no. XX440065. Avl bal Rs 5,000",
            debit("640").on(DC, "XX9911").linked("XX440065").bal("5000"),
        ),
        case(
            "VM-LMNBNK",
            "Thank you for using your Debit Card no. XXXXXXXXXXXX3131 for Rs 499.00 at BOOKNOOK on 12-09-2026.",
            debit("499").on(DC, "XXXXXXXXXXXX3131").merchant("BOOKNOOK"),
        ),
        case(
            "VM-QRSBNK",
            "POS txn of INR 2,340.00 done at BIGMART on Debit Card 4XXXXXXXXXXX7788 on 12-09-26. Avl Bal INR 12,000.00",
            debit("2340").on(DC, "XXXXXXXXXXX7788").bal("12000"),
        ),
        case(
            "VM-NOVABK",
            "ATM Cash Withdrawal of Rs 10,000 from Debit Card XX3131 at ATM ID NB1234 on 12-09-26. Avl Bal Rs 25,000",
            debit("10000").on(DC, "XX3131").bal("25000"),
        ),
    )

    val creditCards = listOf(
        case(
            "VM-NOVABK",
            "Rs.1,499.00 spent on Nova Bank Credit Card XX1234 at SHOPNOW on 12-09-26. Avl Limit: Rs.45,320.00",
            debit("1499").on(CC, "XX1234").bal(null).merchant("SHOPNOW"),
        ),
        case(
            "AD-PQRBNK",
            "Spent Card no. XX4321 INR 500 12-09-26 10:30:11 FOODHUB Avl Lmt INR 45,000 SMS BLOCK 4321 to 919999900000",
            debit("500").on(CC, "XX4321").bal(null),
        ),
        case(
            "VM-LMNBNK",
            "Alert: You've spent INR 2,999.00 on your credit card no. XX8080 at TRAVELCO on 2026-09-12:10:30:00. Avl Lmt INR 1,97,001.00",
            debit("2999").on(CC, "XX8080").bal(null).merchant("TRAVELCO"),
        ),
        case(
            "VM-ABCBNK",
            "Your Credit Card XX6677 has been used for a transaction of INR 12,450.50 at LUXE STORE on 12-Sep-2026. Available credit limit is INR 87,549.50",
            debit("12450.50").on(CC, "XX6677").bal(null).merchant("LUXE STORE"),
        ),
        case(
            "VM-QRSBNK",
            "Transaction alert: INR 350.00 charged on your credit card ending 2020 at CAFE BREW. Total outstanding INR 12,350.00",
            debit("350").on(CC, "2020").bal(null),
        ),
        case(
            "VM-NOVABK",
            "Avl Limit: Rs.45,320.00. Rs.1,499.00 spent on Nova Bank Card XX1234 at SHOPNOW on 12-09-26.",
            debit("1499").on(CC, "XX1234").bal(null),
        ),
        case(
            "VM-ABCBNK",
            "Dear Cardmember, a purchase of USD 42.10 was made on your Credit Card XX4321 at WEBSTORE INC on 12-Sep-26. INR equivalent approx Rs 3,520.00",
            debit("42.10", "USD").on(CC, "XX4321"),
        ),
        case(
            "AX-PQRBNK",
            "Your Credit Card XX4321 used for AED 120.50 at DESERT MALL on 18-09-26.",
            debit("120.50", "AED").on(CC, "XX4321").merchant("DESERT MALL"),
        ),
        case(
            "AX-PQRBNK",
            "Rs.10,850.00 (AED 120.50) debited on Nova Bank Credit Card XX4321 at DESERT MALL on 20-09-26.",
            debit("10850").on(CC, "XX4321"),
        ),
        case(
            "VM-LMNBNK",
            "EUR 25.00 spent on your credit card ending 1005 at CAFE PARIS. Foreign currency markup of Rs 57.50 will be charged. Avl Lmt Rs 80,000",
            debit("25", "EUR").on(CC, "1005").bal(null),
        ),
        case(
            "VM-NOVABK",
            "Payment of Rs 15,000.00 received towards your Credit Card XX1234 on 12-09-26. Thank you.",
            credit("15000").on(CC, "XX1234"),
        ),
        case(
            "VM-LMNBNK",
            "We have received payment of INR 5,000.00 on your credit card ending 8080 on 12/09/2026. Avl Limit INR 1,02,000.00",
            credit("5000").on(CC, "8080").bal(null),
        ),
        case(
            "VM-NOVABK",
            "Refund of Rs 499.00 from SHOPNOW has been credited to your Credit Card XX1234 on 12-09-26.",
            credit("499").on(CC, "XX1234"),
        ),
        case(
            "VM-NOVABK",
            "INR 1,000.00 paid towards your Nova Bank Credit Card XX5566 from A/c XX1234 on 12-09-26.",
            debit("1000").on(BANK, "XX1234"),
        ),
        case(
            "VM-QPAYAP",
            "Rs 5,000 paid to Credit Card XX9876 via UPI. Ref 624812345678",
            credit("5000").on(CC, "XX9876"),
        ),
        case(
            "VM-ABCBNK",
            "USD 18.40 spent at BOOKSHOP LONDON on your card ending 1005. Avl credit limit INR 50,000",
            debit("18.40", "USD").on(CC, "1005").bal(null),
        ),
        case(
            "VM-QRSBNK",
            "Thank you for using your card XX2020 for Rs 300.00 at CAFE BREW on 01-09-26. Avl Lmt Rs 49,700.00",
            debit("300").on(CC, "XX2020").bal(null).merchant("CAFE BREW"),
        ),
        case(
            "VM-PQRBNK",
            "Your Credit Card 4375XXXXXXXX1234 has been used for INR 780.00 at FUELSTOP on 12-09-26.",
            debit("780").on(CC, "XXXXXXXX1234"),
        ),
    )

    val wallets = listOf(
        case("VM-QPAYWL", "Rs.150.00 paid to Chai Point from QPay Wallet. Txn ID T2609211234.", debit("150").on(WALLET, null)),
        case(
            "VM-QPAYWL",
            "Rs 500 added to your QPay Wallet from A/c XX1234. Wallet balance Rs 750",
            credit("500").on(WALLET, null).bal("750"),
        ),
        case("VM-QPAYWL", "Money added: Rs 1,000 to your wallet. Updated balance: Rs 1,250.00", credit("1000").on(WALLET, null).bal("1250")),
        case("VM-QPAYWL", "Rs 99 debited from your wallet for DTH recharge. Balance Rs 401.", debit("99").on(WALLET, null).bal("401")),
        case(
            "VM-QPAYWL",
            "Cashback of Rs 25 credited to your wallet for your payment of Rs 500 at FOODHUB.",
            credit("25").on(WALLET, null),
        ),
        case(
            "VM-QPAYWL",
            "Rs 500 paid to FOODHUB from wallet. You also got a cashback of Rs 25 on this payment!",
            debit("500").on(WALLET, null),
        ),
    )

    val loans = listOf(
        case(
            "VM-NOVABK",
            "EMI of Rs.12,500.00 debited towards your Loan A/c XX9876 on 05-09-26. Outstanding principal: Rs.4,20,000.00",
            debit("12500").on(LOAN, "XX9876").bal("420000"),
        ),
        case(
            "VM-PQRBNK",
            "Rs 8,000 debited from A/c XX1212 for Loan Account XX3434 EMI on 07-09-26.",
            debit("8000").on(LOAN, "XX3434").linked("XX1212"),
        ),
        case("VM-FINSRV", "Received payment of Rs 3,200 for Loan Account No. 40512345678. Thank you.", credit("3200").on(LOAN, "XX5678")),
        case("VM-FINSRV", "Your EMI of Rs 2,100 for personal loan has been deducted successfully.", debit("2100").on(LOAN)),
        case(
            "VM-FINSRV",
            "Dear Customer, EMI of Rs 4,599 for your loan a/c no. XXXXXX7890 is due on 05-10-2026. Please maintain sufficient balance.",
            null,
        ),
        case(
            "VM-FINSRV",
            "Your loan EMI of Rs 3,333 could not be processed due to insufficient balance. Bounce charges Rs 590 applicable.",
            null,
        ),
        case(
            "VM-FINSRV",
            "Loan amount of Rs 2,00,000.00 has been disbursed to your A/c XX1234 on 12-09-26 against Loan A/c XX5678.",
            credit("200000"),
        ),
    )

    val refundsAndReversals = listOf(
        case(
            "VM-NOVABK",
            "Rs 499.00 debited on 10-09 for txn at SHOPNOW has been reversed to your A/c XX1234 on 12-09-26. Avl Bal Rs 5,499.00",
            credit("499").on(BANK, "XX1234").bal("5499"),
        ),
        case(
            "VM-ABCBNK",
            "Reversal: INR 1,200.00 credited to your A/c XX4455 against failed UPI txn Ref 624812345678 dated 10-09-26.",
            credit("1200").on(BANK, "XX4455"),
        ),
        case(
            "VM-LMNBNK",
            "Your purchase of Rs 2,000 at GADGETCO on credit card XX4321 has been refunded. It will reflect in your next statement.",
            credit("2000").on(CC, "XX4321"),
        ),
        case(
            "VM-SHOPNW",
            "Refund of Rs 350.00 for order 402-123 has been initiated and will be credited to your A/c in 5-7 working days.",
            null,
        ),
        case(
            "VM-ABCBNK",
            "Txn of Rs 1,000 at FUELSTOP on your Debit Card XX5566 failed. Amount debited, if any, will be reversed in 5 days.",
            null,
        ),
        case(
            "VM-NOVABK",
            "Rs 250 credited back to your A/c XX1234 for failed UPI txn to foodhub@ybl. Ref 624812345678",
            credit("250").on(BANK, "XX1234"),
        ),
        case(
            "VM-NOVABK",
            "UPI txn of Rs 500 to rohan@ybl reversed. Rs 500 credited to your A/c XX1234.",
            credit("500").on(BANK, "XX1234"),
        ),
        case(
            "VM-LMNBNK",
            "Refund of INR 1,250.00 from TRAVELCO credited to your card ending 8080 on 12/09/2026.",
            credit("1250").on(CC, "8080"),
        ),
    )

    val failedAndDeclined = listOf(
        case(
            "VM-LMNBNK",
            "Your txn of Rs 5,000.00 at GADGETCO on Credit Card XX4321 has been declined due to insufficient credit limit.",
            null,
        ),
        case(
            "VM-ABCBNK",
            "Transaction of INR 2,500 on Debit Card XX1234 was declined due to insufficient balance. Avl Bal INR 1,200.",
            null,
        ),
        case(
            "VM-QPAYAP",
            "Your UPI payment of Rs 800 to rohan@okaxis has failed. If money was debited, it will be refunded within 48 hrs.",
            null,
        ),
        case(
            "VM-NOVABK",
            "NEFT of Rs 10,000 from A/c XX1234 to beneficiary SURESH could not be processed. Reason: invalid account.",
            null,
        ),
        case("VM-ABCBNK", "Txn of Rs 999 using card XX5678 is unsuccessful. Please try again.", null),
        case(
            "VM-LMNBNK",
            "Your transaction of USD 50.00 at WEBSHOP was declined as international usage is disabled on card XX7788.",
            null,
        ),
        case("VM-NOVABK", "IMPS txn of Rs 3,000 to A/c XX9988 cancelled by you.", null),
        case(
            "VM-QPAYAP",
            "Payment of Rs 1,500 to FOODHUB failed. Rs 1,500 debited from A/c XX1234 will be reversed within 3 working days.",
            null,
        ),
        case("VM-ABCBNK", "Transaction declined: Rs 2,000 at ATM. Incorrect PIN entered on card XX3131.", null),
        case("VM-FINSRV", "Your auto-debit of Rs 4,599 for Loan A/c XX7788 has bounced due to insufficient funds.", null),
        case("VM-ABCBNK", "Your Visa card ending 1234 was declined for $25.00 at CORNER STORE.", null),
    )

    val futureRequestsAndMandates = listOf(
        case("VM-NOVABK", "Rs 499.00 will be debited from your A/c XX1234 on 25-09-26 towards STREAMFLIX mandate.", null),
        case("VM-NOVABK", "Your credit card XX1234 bill of Rs 12,000 is due on 25-09-26.", null),
        case(
            "VM-FINSRV",
            "Auto-debit of Rs 4,599.00 towards Loan A/c XX7788 is scheduled on 05-10-2026. Please maintain sufficient balance.",
            null,
        ),
        case("VM-ABCBNK", "E-mandate registered successfully for max Rs 5,000.00 on A/c XX1234 towards FUNDHOUSE SIP.", null),
        case("VM-NOVABK", "AutoPay set up for Rs 499 per month to STREAMFLIX from your A/c XX1234 via UPI.", null),
        case(
            "VM-QPAYAP",
            "Kiran Rao has requested Rs 500 from you on QPay UPI. Approve the request only if you know the person.",
            null,
        ),
        case("VM-QPAYAP", "Collect request of INR 2,000.00 from merchant@okaxis. Approve payment request in your UPI app.", null),
        case(
            "VM-LMNBNK",
            "Pre-debit notification: INR 299.00 will be charged to your card XX4321 on 01-10-26 for MUSICBOX subscription.",
            null,
        ),
        case("VM-ABCBNK", "Dear Customer, your SIP of Rs 5,000 is due for debit on 10-10-2026 from A/c XX1234.", null),
        case("VM-QPAYAP", "UPI mandate of Rs 1,000.00 created for ONLINESHOP. Money will be debited when you shop.", null),
        case("VM-LMNBNK", "Standing instruction registered on your card XX4321 for Rs 999 per month. First debit on 05-10-26.", null),
        case("VM-NOVABK", "Your A/c XX1234 will be credited with Rs 25,000 on 01-10-26 as per your FD maturity instructions.", null),
        case("VM-PWRBIL", "Reminder: Your payment of Rs 2,500 to POWERGRID is due tomorrow.", null),
        case("VM-QPAYAP", "Ravi sent you a payment request of Rs 300. Tap to pay.", null),
        case("VM-NOVABK", "Scheduled transfer of Rs 10,000 from A/c XX1234 to A/c XX5678 on 15-09-26.", null),
        case("VM-NOVABK", "Your recurring payment of Rs 1,499 to CLOUDBOX will be processed on 03-10-26 from card XX4321.", null),
    )

    val balancesAndStatements = listOf(
        case("VM-NOVABK", "Available balance in your A/c XX1234 is Rs 5,432.10 as on 12-09-26.", null),
        case("VM-ABCBNK", "Your A/c XX7788 balance as on 12-Sep-2026 is INR 1,23,456.00 (Clr Bal INR 1,20,000.00).", null),
        case(
            "VM-LMNBNK",
            "Statement for your Credit Card XX4321 generated. Total amt due Rs 12,345.00, Min amt due Rs 620.00, due by 05-10-26.",
            null,
        ),
        case(
            "VM-LMNBNK",
            "Your e-statement for Sep-26 is ready. Total Amount Due: INR 8,900.00. Minimum Due: INR 445.00. Payments received after 12-09 are not included.",
            null,
        ),
        case("VM-NOVABK", "Mini statement A/c XX1234: 10/09 DR 500.00; 11/09 CR 2,000.00; Bal Rs 12,000.00", null),
        case("VM-LMNBNK", "Credit card XX4321: Available limit Rs 45,000. Outstanding Rs 5,000.", null),
        case("VM-NOVABK", "Low balance alert: A/c XX1234 balance is Rs 450.00, below Rs 1,000.00.", null),
        case("VM-NOVABK", "Your FD XX1234 of Rs 1,00,000 matures on 05-10-26.", null),
        case("VM-FINSRV", "Loan A/c XX5678 outstanding as on 12-09-26: Rs 4,20,000. Next EMI Rs 12,500 on 05-10-26.", null),
        case("VM-LMNBNK", "Reward points: You earned 250 points on your card XX4321 for spends of Rs 12,500 in Aug.", null),
        case(
            "VM-LMNBNK",
            "You have earned cashback of Rs 150 on your spend of Rs 3,000 at SHOPNOW using card XX4321. It will be credited in 90 days.",
            null,
        ),
    )

    val otps = listOf(
        case("VM-LMNBNK", "OTP for txn of Rs 5,000.00 at GADGETCO on card XX4321 is 482913. Valid for 5 mins. Do not share.", null),
        case("VM-QPAYAP", "482913 is your One Time Password to pay Rs 999 to STREAMFLIX. Never share it.", null),
        case("VM-ABCBNK", "Use 482913 to authorise txn of INR 1,500.00 on your Debit Card XX1234. Do not share this code.", null),
        case("VM-NOVABK", "Dear Customer, OTP is 123456 for transaction of INR 25,000.00 on A/c XX1234. OTP valid for 3 min.", null),
        case("VM-NOVABK", "Your verification code for adding beneficiary with limit Rs 50,000 is 771122.", null),
    )

    val counterparties = listOf(
        case(
            "AX-QRSBNK-S",
            "Confirmation! INR 100,000.00 credited to beneficiary A/c XX5632 for your NEFT on 07-Dec-2024 at 08:02 PM. Ref N34224216.",
            null,
        ),
        case(
            "VM-NOVABK",
            "Rs 5,000 transferred to A/c XX5632 via IMPS on 12-09-26. Ref 624812345678.",
            debit("5000").copy(masked = null).notOwn("5632"),
        ),
        case(
            "VM-NOVABK",
            "Rs 5,000 transferred from your A/c XX1234 to A/c XX5632 via IMPS on 12-09-26.",
            debit("5000").on(BANK, "XX1234").notOwn("5632"),
        ),
        case(
            "VM-NOVABK",
            "Rs 5,000 transferred to your A/c XX1234 from A/c XX5632 on 12-09-26.",
            credit("5000").on(BANK, "XX1234").notOwn("5632"),
        ),
        case(
            "VM-NOVABK",
            "Rs 5,000 debited from A/c XX1234 and credited to A/c XX5632 on 12-09-26 (IMPS Ref 624812345678).",
            debit("5000").on(BANK, "XX1234").notOwn("5632"),
        ),
        case(
            "VM-NOVABK",
            "INR 2,000.00 credited to A/c XX5632 of RAMESH K from your A/c XX1234 on 12-09-26.",
            debit("2000").on(BANK, "XX1234").notOwn("5632"),
        ),
        case(
            "VM-ABCBNK",
            "You have successfully transferred Rs 7,500 to SURESH (A/c XX8899). Ref 624812345678.",
            debit("7500").copy(masked = null).notOwn("8899"),
        ),
        case(
            "VM-ABCBNK",
            "Fund transfer of Rs 25,000 to A/c XXXXXX4411 (beneficiary: ANITA) successful from A/c XXXXXX1234.",
            debit("25000").on(BANK, "XXXXXX1234").notOwn("4411"),
        ),
        case(
            "VM-LMNBNK",
            "Your IMPS transaction of Rs 1,500.00 to A/c no. XX6677 IFSC NOVA0001234 is successful. Ref 624812345678.",
            debit("1500").copy(masked = null).notOwn("6677"),
        ),
        case("VM-NOVABK", "Rs 3,000.00 credited to A/c XX5632 (payee: VIJAY) on 12-09-26. Ref 624812345678", null),
        case(
            "VM-NOVABK",
            "Rs 500 sent to A/c XX3322 (IFSC NOVA0000123) from your A/c XX1234. UPI Ref 624812345678",
            debit("500").on(BANK, "XX1234").notOwn("3322"),
        ),
        case(
            "VM-NOVABK",
            "Rs 4,000 received from A/c XX7766 of MOHAN in your A/c XX1234 via IMPS. Avl Bal Rs 14,000",
            credit("4000").on(BANK, "XX1234").bal("14000").notOwn("7766"),
        ),
        case(
            "VM-ABCBNK",
            "A/c XX1234 debited Rs 10,000 on 12-09-26; A/c XX5678 credited. IMPS Ref 624812345678",
            debit("10000").on(BANK, "XX1234").notOwn("5678"),
        ),
        case(
            "VM-NOVABK",
            "Rs 10,000 credited to your A/c XX5678 from A/c XX1234 on 12-09-26 (self transfer).",
            credit("10000").on(BANK, "XX5678").notOwn("1234"),
        ),
        case(
            "VM-QRSBNK",
            "Rs 2,500 has been credited to the payee Ramesh via IMPS. Ref 123456.",
            null,
        ),
        case(
            "VM-QRSBNK",
            "Your a/c XX1234 is debited for Rs 900.00 towards IMPS to recipient a/c ending 4411. Avl Bal Rs 100.00",
            debit("900").on(BANK, "XX1234").bal("100").notOwn("4411"),
        ),
    )

    val amountTraps = listOf(
        case(
            "VM-NOVABK",
            "Avl Bal Rs 12,345.00 after debit of Rs 500.00 from A/c XX1234 on 12-09-26.",
            debit("500").on(BANK, "XX1234").bal("12345"),
        ),
        case(
            "VM-NOVABK",
            "Rs 500 debited from A/c XX1234 on 12/09/2026 10:30. Ref 624812345678. Call 18001234567 if not you. Avl Bal Rs 2,500",
            debit("500").on(BANK, "XX1234").bal("2500"),
        ),
        case("VM-NOVABK", "UPI Ref 624812345678 INR 500.00 credited to A/c XX1234 on 12-09-26", credit("500").on(BANK, "XX1234")),
        case(
            "VM-NOVABK",
            "A/c XX1234 debited on 12-09-2026 INR 750.00 UPI/FOODHUB. Avl bal INR 1,250.00",
            debit("750").on(BANK, "XX1234").bal("1250"),
        ),
        case(
            "VM-LMNBNK",
            "Card XX4321 INR 1,250.00 spent at FUELSTOP on 12-09-26. Avl Lmt INR 48,750.00",
            debit("1250").on(CC, "XX4321").bal(null),
        ),
        case(
            "VM-LMNBNK",
            "Rs 2,000 spent on card XX4321 at GADGETCO. You earned 200 reward points worth Rs 50. Avl Lmt Rs 38,000",
            debit("2000").on(CC, "XX4321").bal(null),
        ),
        case(
            "VM-NOVABK",
            "Rs 999 debited from A/c XX1234 for STREAMFLIX. Available Balance Rs 999.00",
            debit("999").on(BANK, "XX1234").bal("999"),
        ),
        case(
            "VM-NOVABK",
            "Your A/c XX1234 has a balance of Rs 10,000.00. Rs 500.00 was debited on 12-09-26 for UPI txn.",
            debit("500").on(BANK, "XX1234").bal("10000"),
        ),
        case(
            "VM-NOVABK",
            "Rs 1,23,456.78 credited to your A/c XX1234 on 12-09-26. Avl Bal Rs 2,00,000.00",
            credit("123456.78").on(BANK, "XX1234").bal("200000"),
        ),
        case(
            "VM-NOVABK",
            "INR 1,000.00 debited from A/c XX4321 on 12-09-26 incl. charges of INR 5.90. Avl Bal INR 9,000.00",
            debit("1000").on(BANK, "XX4321").bal("9000"),
        ),
        case(
            "VM-NOVABK",
            "Rs.500/- debited from your a/c XX1234 on 12.09.26 for mobile recharge 9876543210. Bal Rs.1500/-",
            debit("500").on(BANK, "XX1234").bal("1500"),
        ),
        case(
            "VM-NOVABK",
            "₹ 2,500 credited to your account XX9876 on 12 Sep 2026 at 10:15 AM. Balance: ₹ 12,500",
            credit("2500").on(BANK, "XX9876").bal("12500"),
        ),
        case(
            "VM-NOVABK",
            "INR100000.00 credited to A/c XX1234 on 12-09-26. Avl Bal INR150000.00",
            credit("100000").on(BANK, "XX1234").bal("150000"),
        ),
        case(
            "VM-NOVABK",
            "Rs 1,000.00 debited from A/c XX4321 on 12-09-26. Avl Bal Rs 5,000.00, Clr Bal Rs 4,000.00",
            debit("1000").on(BANK, "XX4321").bal("5000"),
        ),
        case(
            "VM-NOVABK",
            "Rs 50 cashback credited to your A/c XX1234 on your payment of Rs 500 at FOODHUB.",
            credit("50").on(BANK, "XX1234"),
        ),
        case(
            "VM-NOVABK",
            "Your A/c XX1234 debited for Rs 2,000.00 on 12-09-26. A/c balance: Rs 3,000.00 (incl. OD limit Rs 1,000.00)",
            debit("2000").on(BANK, "XX1234").bal("3000"),
        ),
    )

    val currencies = listOf(
        case(
            "VM-NOVABK",
            "USD 60.00 spent on your Nova Bank Forex Card XX7001 at MACYS NEW YORK. Avl bal USD 540.00",
            debit("60", "USD").on(PREPAID, "XX7001").bal("540", "USD"),
        ),
        case("VM-ABCBNK", "Your card ending 1234 was charged $42.10 at COFFEE CO on 21 Sep.", debit("42.10", "USD").on(CC, "1234")),
        case(
            "VM-NOVABK",
            "GBP 9.99 debited on Nova Travel Card XX1111 at CORNER SHOP. INR equivalent Rs 1,050.00",
            debit("9.99", "GBP").on(PREPAID, "XX1111"),
        ),
        case(
            "VM-LMNBNK",
            "Txn of SGD 45.50 on card XX4321 at HAWKER MART. Approx INR 2,850.00. Avl Lmt INR 60,000",
            debit("45.50", "SGD").on(CC, "XX4321").bal(null),
        ),
        case("VM-NOVABK", "Rs 5 lakh credited to your A/c XX1234 on 12-09-26 towards FD closure.", credit("500000").on(BANK, "XX1234")),
        case(
            "VM-NOVABK",
            "INR 5,00,000.00 debited from A/c XX1234 for property registration. Avl Bal INR 1,00,000.00",
            debit("500000").on(BANK, "XX1234").bal("100000"),
        ),
        case("EuroBank", "€ 12,50 spent at BAKERY with your debit card ending 4242.", debit("12.50", "EUR").on(DC, "4242")),
        case("NorthBank", "Deposit of \$1,200.00 posted to your account ending 1234.", credit("1200", "USD").on(BANK, "1234")),
        case(
            "VM-ABCBNK",
            "AED 120.50 spent on Card XXXX1234 at CITY MART. Avl Cr. Limit AED 5,000.00",
            debit("120.50", "AED").on(CC, "XXXX1234").bal(null),
        ),
    )

    val masks = listOf(
        case("VM-NOVABK", "Rs 100 debited from A/c no. XXXXXXXX1234 on 12-09-26.", debit("100").on(BANK, "XXXXXXXX1234")),
        case("VM-NOVABK", "Rs 100 debited from acct xxxx1234 on 12-09-26.", debit("100").on(BANK, "XXXX1234")),
        case("VM-NOVABK", "Rs 100 debited from account number ending 1234 on 12-09-26.", debit("100").on(BANK, "1234")),
        case("VM-NOVABK", "Rs 100 debited from A/c ...1234 on 12-09-26.", debit("100").on(BANK, "XXX1234")),
        case("VM-NOVABK", "Rs 100 debited from Ac No. XX1234 on 12-09-26.", debit("100").on(BANK, "XX1234")),
        case("VM-NOVABK", "Rs 100 debited from A/C: XX1234 on 12-09-26.", debit("100").on(BANK, "XX1234")),
        case("VM-NOVABK", "Rs 100 debited from a/c #XX1234 on 12-09-26.", debit("100").on(BANK, "XX1234")),
        case("VM-NOVABK", "Rs 100 debited from A/c No: XXXX1234 on 12-09-26.", debit("100").on(BANK, "XXXX1234")),
        case("VM-NOVABK", "Rs 100 debited from your a/c no 1234XXXXXX5678 on 12-09-26.", debit("100").on(BANK, "XXXXXX5678")),
        case("VM-NOVABK", "Rs 100 spent on your card ending with 4321 at KIOSK.", debit("100").on(CC, "4321")),
        case("VM-NOVABK", "Rs 100 debited from Savings A/c XX1234 on 12-09-26.", debit("100").on(BANK, "XX1234")),
        case("VM-NOVABK", "Rs 100 debited from A/c XX 1234 on 12-09-26.", debit("100").on(BANK, "XX1234")),
    )

    val all: Map<String, List<CorpusCase>> = linkedMapOf(
        "account debits" to accountDebits,
        "account credits" to accountCredits,
        "upi" to upi,
        "debit cards" to debitCards,
        "credit cards" to creditCards,
        "wallets" to wallets,
        "loans" to loans,
        "refunds and reversals" to refundsAndReversals,
        "failed and declined" to failedAndDeclined,
        "future, requests and mandates" to futureRequestsAndMandates,
        "balances and statements" to balancesAndStatements,
        "otps" to otps,
        "counterparties" to counterparties,
        "amount traps" to amountTraps,
        "currencies" to currencies,
        "masks" to masks,
    )
}
