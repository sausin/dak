package app.dak.finance.parser.corpus

/**
 * A second batch, written after the parser rules to check they generalise: new wording, line breaks, unmasked
 * tails, own-account markers, offers and service messages, and a few non-Indian formats. Same conventions as
 * [SmsCorpus]; every name, header and number is made up.
 */
internal object SmsCorpusMore {

    val debits = listOf(
        case(
            "VM-NOVABK",
            "Dear Customer, INR 3,450.75 has been debited from your Savings Account **9087 on 14-SEP-2026 towards POS purchase at METRO FRESH. Available Balance: INR 21,300.10",
            debit("3450.75").on(BANK, "XX9087").bal("21300.10"),
        ),
        case(
            "VM-ABCBNK",
            "Alert! Your A/c no. XXXXXX6612 is debited by Rs. 1,000.00 on 14/09/26 for UPI-624800001111-GROCERLY. Bal: Rs. 6,000.00 Cr",
            debit("1000").on(BANK, "XXXXXX6612").bal("6000"),
        ),
        case(
            "VM-NOVABK",
            "Amt Sent Rs.2,000.00\nFrom NOVA A/C *3344\nTo RAJESH KUMAR\nOn 14-09\nRef 624811112222\nNot You? Call 18002000000",
            debit("2000").on(BANK, "X3344"),
        ),
        case(
            "VM-NOVABK",
            "You have done a UPI txn. Check details!\nRs.150.00 debited from A/c XX0987 on 14 Sep 2026 to VPA 9876543210@ybl. UPI Ref:624811113333",
            debit("150").on(BANK, "XX0987").merchant("9876543210@ybl"),
        ),
        case(
            "VM-LMNBNK",
            "INR 10,000.00 withdrawn from your a/c XX1122 at ATM. Balance INR 4,500.00. Call 1800-000-000 if not done by you.",
            debit("10000").on(BANK, "XX1122").bal("4500"),
        ),
        case(
            "VM-QRSBNK",
            "Your ac XX4422 has been debited INR 60.00 as SMS charges for Q2. Bal INR 1,940.00",
            debit("60").on(BANK, "XX4422").bal("1940"),
        ),
        case(
            "VM-ABCBNK",
            "Debited Rs 845.00 from A/c XX5511 for POWERCO bill of Sep-26. Avl bal Rs 11,155.00",
            debit("845").on(BANK, "XX5511").bal("11155"),
        ),
        case(
            "VM-PQRBNK",
            "ECS/ACH debit of Rs 2,999 for FUNDHOUSE SIP from a/c XX1010 processed on 14-09-26",
            debit("2999").on(BANK, "XX1010"),
        ),
        case("VM-NOVABK", "Rs 600 debited from A/c 1212 on 14-09-26 via UPI. Ref 624811110000", debit("600").on(BANK, "1212")),
        case("VM-NOVABK", "Rs 250.00 debited from A/c No. XXXXXXXXXX1212 on 14-09-2026", debit("250").on(BANK, "XXXXXXXXXX1212")),
        case("VM-NOVABK", "Rs.99 debited from your account ending in 1212.", debit("99").on(BANK, "1212")),
    )

    val credits = listOf(
        case(
            "VM-QRSBNK",
            "Dear Customer, your A/c XXXX5566 has been credited with INR 45,000.00 on 14-SEP-26 by NEFT from ACME TECH (UTR: NOVA26257001122). Avl Bal INR 60,000.00",
            credit("45000").on(BANK, "XXXX5566").bal("60000"),
        ),
        case(
            "VM-ABCBNK",
            "Rs 2,000 deposited in cash to your A/c XX3434 at BR 0123 on 14/09. Avl Bal Rs 12,000",
            credit("2000").on(BANK, "XX3434").bal("12000"),
        ),
        case(
            "VM-QPAYAP",
            "Hi, you received ₹1,500.00 from SONAL M via UPI in NOVA Bank account ending 9090. Ref 624811114444",
            credit("1500").on(BANK, "9090"),
        ),
        case(
            "VM-PNSBNK",
            "A/c *7788 Credited for Rs:25,000.00 on 14-09-2026 by Transfer from ANAND. Avl Bal Rs:30,000.00",
            credit("25000").on(BANK, "X7788").bal("30000"),
        ),
        case(
            "VM-LMNBNK",
            "NEFT Credit: INR 7,777.00 received in A/c XX2020 from GLOBEX LTD on 14-09-26.",
            credit("7777").on(BANK, "XX2020"),
        ),
        case(
            "VM-ABCBNK",
            "Your account XX1111 is credited with USD 250.00 (INR 20,875.00) on 14-09-2026 - Inward remittance from JOHN DOE.",
            credit("250", "USD").on(BANK, "XX1111"),
        ),
        case(
            "VM-NOVABK",
            "Rs.5,000.00 credited to your a/c XX4545 on 14-09-26 by transfer from your a/c XX6767. Avl Bal Rs.7,000.00",
            credit("5000").on(BANK, "XX4545").bal("7000"),
        ),
        case(
            "VM-NOVABK",
            "Credit Alert: Rs 10 lakh credited to A/c XX1212 on 14-09-26. Info: LOAN DISB. Avl Bal Rs 10,05,000.00",
            credit("1000000").on(BANK, "XX1212").bal("1005000"),
        ),
    )

    val cards = listOf(
        case(
            "VM-NOVABK",
            "Txn Rs.500.00 On NOVA Bank Card 1234 At SHOPKART by UPI 624811115555 On 14-09. Not You? Call 18002586000",
            debit("500").on(CC, "1234").merchant("SHOPKART"),
        ),
        case(
            "VM-NOVABK",
            "Your NOVA Credit Card ending 4040 has been charged INR 3,299.00 at AIRLINK on 14 Sep. Available limit: INR 96,701.00",
            debit("3299").on(CC, "4040").bal(null).merchant("AIRLINK"),
        ),
        case(
            "VM-LMNBNK",
            "INR 499.00 spent on credit card XX3030 at MUSICBOX via auto-debit (SI) on 14-09-26. Avl Lmt: INR 45,000.00",
            debit("499").on(CC, "XX3030").bal(null),
        ),
        case(
            "VM-LMNBNK",
            "We have credited INR 1,200.00 to your credit card XX6060 on 14-09-26 towards reversal of charges.",
            credit("1200").on(CC, "XX6060"),
        ),
        case(
            "VM-NOVABK",
            "Payment of INR 25,000.00 has been received on your NOVA Credit Card ending 7070 on 14/09/2026. Thank you.",
            credit("25000").on(CC, "7070"),
        ),
        case(
            "VM-ABCBNK",
            "Dear Cardholder, your card XX8080 was used at INTL MERCHANT LONDON for GBP 45.00 on 14-09-26. Avl limit INR 55,000",
            debit("45", "GBP").on(CC, "XX8080").bal(null),
        ),
        case(
            "VM-QRSBNK",
            "ALERT: INR 2,100.00 debited at FUEL POINT on Debit Card XX9191 linked to A/c XX2323. Avl Bal INR 8,900.00",
            debit("2100").on(DC, "XX9191").linked("XX2323").bal("8900"),
        ),
        case(
            "VM-NOVABK",
            "USD 12.99 charged on your Debit Card XX6767 for CLOUDAPP. INR 1,090.00 debited from A/c XX1212 incl. markup Rs 38.15.",
            debit("12.99", "USD").on(DC, "XX6767").linked("XX1212"),
        ),
        case("VM-ABCBNK", "Card no. ending 5566 used for Rs 700 at KIOSK", debit("700").on(CC, "5566").merchant("KIOSK")),
    )

    val walletsAndUpi = listOf(
        case(
            "VM-QPAYWL",
            "₹200 received in your QPay wallet from Arun. Updated wallet balance: ₹1,200",
            credit("200").on(WALLET, null).bal("1200"),
        ),
        case("VM-QPAYWL", "Paid ₹45 to Metro Card Recharge using QPay wallet. Balance ₹955", debit("45").on(WALLET, null).bal("955")),
        case("VM-QPAYAP", "You paid Rs 120 to AUTO DRIVER via UPI from QPay. Ref 624811116666", debit("120").on(UPI, null)),
    )

    val notTransactions = listOf(
        case(
            "VM-NOVABK",
            "Your NEFT of Rs 50,000 to A/c XX1234 is under process. You will be notified once it is credited.",
            null,
        ),
        case("VM-NOVABK", "Dear Customer, your request for cheque book has been received. Charges Rs 50 will be debited.", null),
        case("VM-NOVABK", "Your A/c XX1234 has been credited with 500 reward points.", null),
        case("VM-FINOFF", "Get a personal loan of up to Rs 5,00,000 credited to your account in 10 minutes. Apply now.", null),
        case(
            "VM-NOVABK",
            "Beneficiary ANITA (A/c XX4411) added successfully. You can transfer up to Rs 50,000 in the first 24 hours.",
            null,
        ),
        case(
            "VM-NOVABK",
            "Your Debit Card XX1234 has been blocked as requested. Any txn of Rs 1 or more will be declined.",
            null,
        ),
        case("VM-NOVABK", "Amount of Rs 5,000.00 is on hold on your A/c XX1234 (lien marked).", null),
        case("VM-LMNBNK", "Rs 1,499 will be auto-debited from your card XX4321 on 20-09 for CLOUDBOX. To stop, visit the app.", null),
        case(
            "VM-FUNDHS",
            "Your SIP of Rs 5,000 in FUNDHOUSE has been registered. First installment will be debited on 10-10-26.",
            null,
        ),
        case(
            "VM-QPAYAP",
            "Dear Customer, you have a pending payment request of Rs 350 from kiran@ybl. Ignore if not initiated by you.",
            null,
        ),
        case("VM-LMNBNK", "Credit card XX4321 statement: Total due Rs 5,600; Min due Rs 280; Pay by 05-10-26.", null),
        case("VM-NOVABK", "OTP 482913 for NEFT of Rs 25,000 to beneficiary ANITA. Valid 10 min.", null),
        case("VM-NOVABK", "123456 is the OTP to add ANITA as beneficiary with limit Rs 1,00,000.", null),
        case("VM-QPAYAP", "Your UPI transaction of Rs 250 to shop@ybl is pending. Please check status after 2 hours.", null),
        case(
            "VM-NOVABK",
            "Dear Customer, due to a technical issue your txn of Rs 999 at SHOPKART was not completed. Amount will be reversed if debited.",
            null,
        ),
    )

    val counterparties = listOf(
        case(
            "VM-NOVABK",
            "Rs 12,000 sent to RAVI TEJA A/c XX5454 IFSC NOVA0000999 from NOVA A/c XX1212 via IMPS. Ref 624811117777",
            debit("12000").on(BANK, "XX1212").notOwn("5454"),
        ),
        case(
            "VM-NOVABK",
            "IMPS Ref 624811118888: INR 3,000.00 credited to A/c XX5454 (RAVI TEJA). Debited from your A/c XX1212.",
            debit("3000").on(BANK, "XX1212").notOwn("5454"),
        ),
        case(
            "VM-NOVABK",
            "Transfer of Rs 1,000 to own A/c XX9999 from A/c XX1212 successful.",
            debit("1000").on(BANK, "XX1212"),
        ),
        case(
            "VM-ABCBNK",
            "Dear Customer, Rs 5,000 has been debited from your account XX1212 and credited to beneficiary PRIYA's account XX8989 via IMPS.",
            debit("5000").on(BANK, "XX1212").notOwn("8989"),
        ),
        case(
            "VM-NOVABK",
            "Money received from SURESH (A/c XX3131) to your A/c XX1212: Rs 900.00. Ref 624811119999",
            credit("900").on(BANK, "XX1212").notOwn("3131"),
        ),
        case(
            "VM-NOVABK",
            "Your fund transfer request of Rs 7,000 to A/c XX4646 has been successfully processed. Ref 624800003333",
            debit("7000").copy(masked = null).notOwn("4646"),
        ),
        case(
            "VM-PQRBNK",
            "IMPS/NEFT: Rs 20,000 debited from your A/c XX7070 towards A/c XX8181 of PRIYA on 14-09. Ref 624800002222",
            debit("20000").on(BANK, "XX7070").notOwn("8181"),
        ),
    )

    val amounts = listOf(
        case(
            "VM-NOVABK",
            "Rs.2,50,000.00 debited from A/c XX1212 on 14-09-26 for RTGS to GLOBEX (UTR NOVAR52026091400012345). Bal Rs.3,12,345.67 CR",
            debit("250000").on(BANK, "XX1212").bal("312345.67"),
        ),
        case(
            "VM-NOVABK",
            "Sent Rs 1,00,000 to A/c XX5656 on 14-09 (charges Rs 17.70 incl GST). Balance Rs 9,000",
            debit("100000").copy(masked = null).bal("9000").notOwn("5656"),
        ),
        case(
            "VM-LMNBNK",
            "Rs 350.50 spent on card XX4321 at CAFE ALPHA on 14-09. Cashback of Rs 35.05 will be credited in 3 days. Avl Lmt Rs 40,000.00",
            debit("350.50").on(CC, "XX4321").bal(null),
        ),
        case(
            "VM-NOVABK",
            "Your A/c XX1212 debited for INR 0.99 on 14-09 as international transaction markup fee.",
            debit("0.99").on(BANK, "XX1212"),
        ),
    )

    val foreign = listOf(
        case(
            "HarbourBank",
            "HarbourBank: You spent £23.40 at CORNER CAFE on your debit card ending 1122. Balance £1,204.55.",
            debit("23.40", "GBP").on(DC, "1122").bal("1204.55"),
        ),
        case(
            "DesertBank",
            "Purchase of AED 350.00 with Credit Card ending 3344 at MALL STORE. Available limit AED 12,650.00",
            debit("350", "AED").on(CC, "3344").bal(null),
        ),
        case(
            "LionBank",
            "LionBank: A transfer of SGD 500.00 to JOHN TAN was made from your account ending 5566 on 14 Sep.",
            debit("500", "SGD").on(BANK, "5566"),
        ),
        case(
            "LionBank",
            "LionBank: You have received SGD 80.00 from MARY LIM to your account ending 7788.",
            credit("80", "SGD").on(BANK, "7788"),
        ),
        case(
            "NorthBank",
            "NorthBank: Your debit card purchase of \$54.21 at GROCERY OUTLET was approved. Available balance: \$1,245.00",
            debit("54.21", "USD").on(DC, null).bal("1245"),
        ),
        case("NorthBank", "NorthBank: Direct deposit of \$2,500.00 posted to account ending 0099.", credit("2500", "USD").on(BANK, "0099")),
        case("NorthBank", "NorthBank: Payment of \$40.00 sent to ALEX P from your checking account ending 0099.", debit("40", "USD").on(BANK, "0099")),
    )

    val all: Map<String, List<CorpusCase>> = linkedMapOf(
        "more: debits" to debits,
        "more: credits" to credits,
        "more: cards" to cards,
        "more: wallets and upi" to walletsAndUpi,
        "more: not transactions" to notTransactions,
        "more: counterparties" to counterparties,
        "more: amounts" to amounts,
        "more: foreign" to foreign,
    )
}
