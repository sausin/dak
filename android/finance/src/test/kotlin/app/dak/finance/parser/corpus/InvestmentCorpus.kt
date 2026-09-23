package app.dak.finance.parser.corpus

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.InvestmentAction
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.Money
import java.math.BigDecimal

/**
 * What the parser must make of one investment-related message: the usual transaction fields plus the investment
 * action, units, NAV / price, units held, ISIN and whether it is an own transfer (never spending or income).
 */
internal data class InvestExpect(
    val direction: TransactionDirection,
    val amount: String,
    val instrument: InstrumentType,
    val action: InvestmentAction?,
    val currency: String = "INR",
    /** The masked number recorded; null = none. */
    val masked: String? = null,
    /** Units moved; null = none may be recorded. [ANY] skips the check. */
    val units: String? = ANY,
    val unitPrice: String? = ANY,
    val unitsHeld: String? = ANY,
    /** Balance / current value in major units; null = none may be recorded. */
    val balance: String? = null,
    val merchant: String? = ANY,
    val isin: String? = ANY,
    val ownTransfer: Boolean = true,
    /** Digits that must never become the recorded number (e.g. the bank account a fund paid out to). */
    val notOwn: List<String> = emptyList(),
) {
    fun mismatches(actual: ExtractedTransaction): List<String> {
        val out = ArrayList<String>()
        fun check(what: String, want: Any?, got: Any?) {
            if (want != got) out += "$what: expected <$want> got <$got>"
        }
        check("direction", direction, actual.direction)
        check("amount", minor(amount), actual.amountMinor)
        check("currency", currency, actual.currency)
        check("instrument", instrument, actual.instrument)
        check("action", action, actual.investmentAction)
        check("maskedNumber", masked, actual.maskedNumber)
        if (units != ANY) check("units", units, actual.units)
        if (unitPrice != ANY) check("unitPrice", unitPrice, actual.unitPrice)
        if (unitsHeld != ANY) check("unitsHeld", unitsHeld, actual.unitsHeld)
        check("balance", balance?.let { minor(it) }, actual.balanceMinor)
        if (merchant != ANY) check("merchant", merchant, actual.merchant)
        if (isin != ANY) check("isin", isin, actual.isin)
        check("ownTransfer", ownTransfer, actual.ownTransfer)
        for (digits in notOwn) {
            if (actual.maskedNumber?.endsWith(digits) == true) out += "account $digits became the recorded number"
        }
        return out
    }

    private fun minor(major: String): Long = Money.ofMajor(BigDecimal(major), currency).amountMinor
}

internal data class InvestCase(val sender: String, val body: String, val expect: InvestExpect?)

private fun inv(sender: String, body: String, expect: InvestExpect?) = InvestCase(sender, body, expect)

private val MF = InstrumentType.MUTUAL_FUND
private val DEMAT = InstrumentType.DEMAT
private val BANK_AC = InstrumentType.BANK_ACCOUNT

private fun purchase(amount: String, masked: String?) =
    InvestExpect(TransactionDirection.CREDIT, amount, MF, InvestmentAction.PURCHASE, masked = masked)

private fun redemption(amount: String, masked: String?) =
    InvestExpect(TransactionDirection.DEBIT, amount, MF, InvestmentAction.REDEMPTION, masked = masked)

private fun valuation(value: String, instrument: InstrumentType, masked: String?) =
    InvestExpect(TransactionDirection.CREDIT, "0", instrument, InvestmentAction.VALUATION, masked = masked, balance = value, units = null)

/** A bank's side of the same money: an ordinary bank transaction, marked as an own transfer when it is investment money. */
private fun bank(direction: TransactionDirection, amount: String, masked: String, balance: String?, ownTransfer: Boolean) =
    InvestExpect(direction, amount, BANK_AC, null, masked = masked, balance = balance, units = null, unitPrice = null, unitsHeld = null, ownTransfer = ownTransfer)

/**
 * Investment SMS in many styles: SIP confirmations, allotments, redemptions, switches, IDCW, valuations, CAS, contract
 * notes, demat security alerts, pledges, e-DIS, NFO promotions, OTPs, and the bank-side debits / credits of the same
 * money. Every fund, scheme, security, broker, header and number is made up; the wording follows the structure real
 * fund, registrar, broker and depository messages use (see finance/README.md, "Investments").
 */
internal object InvestmentCorpus {

    val fundPurchases = listOf(
        inv(
            "VM-PEAKMF",
            "Dear Investor, your SIP instalment of Rs.5,000.00 in Peak Flexi Cap Fund - Direct Growth, Folio No. XXXX1234 has been processed. Units allotted: 45.678 at NAV Rs.109.4563 on 05-Sep-2026.",
            purchase("5000", "XXXX1234").copy(units = "45.678", unitPrice = "109.4563", unitsHeld = null, merchant = "Peak Flexi Cap Fund - Direct Growth"),
        ),
        inv(
            "BZ-ORBITM",
            "Units allotted! Folio 12345678/90: 102.345 units of Orbit Liquid Fund - Regular Plan allotted @ Rs 2,445.1234 for your purchase of INR 2,50,250.00. Balance units: 512.001",
            purchase("250250", "XXXX5678").copy(units = "102.345", unitPrice = "2445.1234", unitsHeld = "512.001", merchant = "Orbit Liquid Fund - Regular Plan"),
        ),
        inv(
            "JD-ZENFND",
            "Your purchase of Rs 10,000 in folio 9988776 (Zen Small Cap Fund Growth) is confirmed. 312.5 units allotted at NAV 32.00. Txn Ref: ZF12345.",
            purchase("10000", "XXXX8776").copy(units = "312.5", unitPrice = "32"),
        ),
        inv(
            "VM-SILVMF",
            "Dear Investor, 25.431 units of Silver Balanced Advantage Fund have been allotted to you at NAV of Rs 39.3200 for your lumpsum investment of Rs 1,000.",
            purchase("1000", null).copy(units = "25.431", unitPrice = "39.32", merchant = "Silver Balanced Advantage Fund"),
        ),
        inv(
            "VM-PEAKMF",
            "SIP of Rs 2,000 debited from your bank a/c XX4321 for Folio XXXX1234 on 05/09/2026. Units: 18.270 @ NAV Rs 109.4700",
            purchase("2000", "XXXX1234").copy(units = "18.27", unitPrice = "109.47", notOwn = listOf("4321")),
        ),
        inv(
            "AD-NOVAMF-S",
            "PURCHASE CONFIRMED: FOLIO NO 44556677, NOVA INDEX FUND - DIRECT GROWTH, AMOUNT INR 7,500.00, NAV 21.5500, UNITS 348.028, TOTAL UNITS 1,002.500",
            purchase("7500", "XXXX6677").copy(units = "348.028", unitPrice = "21.55", unitsHeld = "1002.5"),
        ),
        inv(
            "VM-PEAKMF",
            "IDCW reinvestment: 3.456 units allotted in folio XXXX1234 (Peak Balanced Fund) at NAV Rs 28.9300 for Rs 100.00",
            purchase("100", "XXXX1234").copy(units = "3.456", unitPrice = "28.93"),
        ),
        inv(
            "JD-ZENFND",
            "Dear Investor, units allotted 10.000 at NAV 50.00 for Rs 500 in Folio 9988776. Current value of your holdings: Rs 12,345.67",
            purchase("500", "XXXX8776").copy(units = "10", unitPrice = "50", balance = "12345.67"),
        ),
    )

    val fundRedemptionsAndSwitches = listOf(
        inv(
            "BZ-ORBITM",
            "Redemption of 50.000 units from Orbit Liquid Fund, Folio 12345678/90 processed at NAV Rs 2,450.5000. Amount of Rs 1,22,525.00 will be credited to your bank a/c XX4321 in 1-2 working days.",
            redemption("122525", "XXXX5678").copy(units = "50", unitPrice = "2450.5", notOwn = listOf("4321")),
        ),
        inv(
            "VM-SILVMF",
            "15.500 units redeemed from Silver Arbitrage Fund (Folio: 55443322) at NAV Rs 30.1200. Redemption amount Rs 466.86 credited to your registered bank account.",
            redemption("466.86", "XXXX3322").copy(units = "15.5", unitPrice = "30.12"),
        ),
        inv(
            "JD-ZENFND",
            "Switch of 100.000 units from Zen Liquid Fund to Zen Small Cap Fund in folio 9988776 processed. Switch amount Rs 25,000.00. Units allotted in target scheme: 780.25 at NAV 32.0410.",
            InvestExpect(TransactionDirection.CREDIT, "25000", MF, InvestmentAction.SWITCH, masked = "XXXX8776", units = "100", unitPrice = "32.041"),
        ),
    )

    val fundIncome = listOf(
        inv(
            "VM-PEAKMF",
            "IDCW of Rs 1,250.00 declared under Peak Equity Income Fund for folio XXXX1234 has been paid to your bank a/c XX4321 on 20-Sep-2026. TDS Rs 125.00.",
            InvestExpect(TransactionDirection.CREDIT, "1250", MF, InvestmentAction.DIVIDEND, masked = "XXXX1234", ownTransfer = false, notOwn = listOf("4321")),
        ),
        inv(
            "VM-SILVMF",
            "Dividend of Rs 845.50 paid for 1,250.000 units held in Silver Equity Savings Fund, Folio 55443322.",
            InvestExpect(TransactionDirection.CREDIT, "845.5", MF, InvestmentAction.DIVIDEND, masked = "XXXX3322", unitsHeld = "1250", ownTransfer = false),
        ),
    )

    val valuations = listOf(
        inv(
            "VM-PEAKMF",
            "Dear Investor, the current value of your investments in Folio XXXX1234 as on 19-Sep-2026 is Rs 1,23,456.78. Units held: 1,127.890.",
            valuation("123456.78", MF, "XXXX1234").copy(unitsHeld = "1127.89"),
        ),
        inv(
            "VM-FOLIOS",
            "Your Consolidated Account Statement (CAS) for Aug-2026 has been sent to your registered email. Total valuation as on 31-Aug-2026: INR 5,43,210.50 across 4 folios.",
            valuation("543210.5", MF, null),
        ),
        inv(
            "VM-ZENBRK",
            "Your demat holdings value as on 19-Sep-2026 is Rs 8,76,543.21. BO ID XXXXXXXX00012345",
            valuation("876543.21", DEMAT, "XXXX2345"),
        ),
        inv(
            "BZ-ORBITM",
            "Valuation of your folio 12345678 as on 20/09/2026: Rs 2,34,567.89 (NAV 2,455.1000).",
            valuation("234567.89", MF, "XXXX5678").copy(unitPrice = "2455.1"),
        ),
    )

    val trades = listOf(
        inv(
            "VM-ZENBRK",
            "Contract Note for 12-Sep-2026: Bought 10 ACME INDUSTRIES LTD @ 2,345.50. Net amount payable Rs 23,480.25 incl. charges. Client ID AB1234.",
            InvestExpect(TransactionDirection.CREDIT, "23480.25", DEMAT, InvestmentAction.BUY, masked = "XXXX1234", units = "10", unitPrice = "2345.5", merchant = "ACME INDUSTRIES LTD"),
        ),
        inv(
            "BZ-TRDNOW",
            "Trade executed: SOLD 5 shares of BETA POWER at Rs 1,200.00 on the exchange. Demat a/c XXXX5678.",
            InvestExpect(TransactionDirection.DEBIT, "6000", DEMAT, InvestmentAction.SELL, masked = "XXXX5678", units = "5", unitPrice = "1200", merchant = "BETA POWER"),
        ),
        inv(
            "VM-ZENBRK",
            "Trade confirmation: BUY 25 GAMMA TECH @ 99.50, trade value Rs 2,487.50, charges Rs 12.40. BO ID 1234567800012345",
            InvestExpect(TransactionDirection.CREDIT, "2487.5", DEMAT, InvestmentAction.BUY, masked = "XXXX2345", units = "25", unitPrice = "99.5"),
        ),
        inv(
            "VM-ZENBRK",
            "Contract note 15-Sep-2026: Bought 10 ACME LTD @ 100.00, Sold 5 DELTA CORP @ 400.00. Net obligation receivable Rs 1,000.00. Client ID XXXX1234",
            InvestExpect(TransactionDirection.DEBIT, "1000", DEMAT, InvestmentAction.SELL, masked = "XXXX1234", units = null, unitPrice = null, merchant = null),
        ),
        inv(
            "VM-ZENBRK",
            "Dividend of Rs 300.00 for 100 shares of ACME LTD (ISIN INE123A01016) credited to your bank a/c XX4321. Demat a/c XXXX5678",
            InvestExpect(TransactionDirection.CREDIT, "300", DEMAT, InvestmentAction.DIVIDEND, masked = "XXXX5678", isin = "INE123A01016", ownTransfer = false, notOwn = listOf("4321")),
        ),
        inv(
            "JM-DEPOSI",
            "Congratulations! 14 shares of OMEGA FOODS allotted to your demat a/c XXXX5678 against your IPO application of Rs 14,980.",
            InvestExpect(TransactionDirection.CREDIT, "14980", DEMAT, InvestmentAction.BUY, masked = "XXXX5678"),
        ),
        inv(
            "72000",
            "Trade confirmation: Bought 3 shares of ACME CORP at $150.25. Total $450.75",
            InvestExpect(TransactionDirection.CREDIT, "450.75", DEMAT, InvestmentAction.BUY, currency = "USD", masked = null, units = "3", unitPrice = "150.25"),
        ),
    )

    /** The bank's side of investment money: a transfer, not spending or income. */
    val bankSide = listOf(
        inv(
            "VM-NOVABK",
            "Rs.5,000.00 debited from A/c XX4321 on 05-09-26 towards ACH D- PEAK MF SIP. Avl Bal Rs.45,000.00",
            bank(TransactionDirection.DEBIT, "5000", "XX4321", "45000", ownTransfer = true),
        ),
        inv(
            "VM-NOVABK",
            "Your A/c XX4321 is debited for Rs 2,000.00 on 05-Sep-2026 towards SIP, Folio 12345678. Avl bal Rs 43,000.00",
            bank(TransactionDirection.DEBIT, "2000", "XX4321", "43000", ownTransfer = true),
        ),
        inv(
            "VM-NOVABK",
            "Rs 2,000.00 debited from your A/c XX4321 towards NAV-based SIP purchase in folio 12345678.",
            bank(TransactionDirection.DEBIT, "2000", "XX4321", null, ownTransfer = true),
        ),
        inv(
            "VM-NOVABK",
            "Rs 1,22,525.00 credited to A/c XX4321 on 22-09-2026 by NEFT - PEAK MUTUAL FUND REDEMPTION. Avl Bal Rs 1,50,000.00",
            bank(TransactionDirection.CREDIT, "122525", "XX4321", "150000", ownTransfer = true),
        ),
        inv(
            "VM-NOVABK",
            "INR 20,000.00 debited from A/c XX4321 on 12-09-2026 to trading account of client via NEFT. Avl bal INR 23,000.00",
            bank(TransactionDirection.DEBIT, "20000", "XX4321", "23000", ownTransfer = true),
        ),
        // Income, not a transfer.
        inv(
            "VM-NOVABK",
            "Rs 450.00 credited to your A/c XX4321 towards dividend from ACME LTD. Avl Bal Rs 50,450.00",
            bank(TransactionDirection.CREDIT, "450", "XX4321", "50450", ownTransfer = false),
        ),
        // Ordinary spending stays spending.
        inv(
            "VM-NOVABK",
            "Rs 640.00 debited from A/c XX4321 on 12-09-2026 at FRESH MART. Avl Bal Rs 49,810.00",
            bank(TransactionDirection.DEBIT, "640", "XX4321", "49810", ownTransfer = false),
        ),
    )

    /** Security alerts, promotions, requests, OTPs and notices: never ledger entries. */
    val notTransactions = listOf(
        inv("JM-DEPOSI", "10 shares of ACME LTD (ISIN INE123A01016) debited from your demat a/c XXXX5678 on 18-Sep-2026. If not done by you, contact your DP immediately.", null),
        inv("VM-ZENBRK", "Pledge created for 50 shares of BETA POWER in your demat a/c XXXX5678 in favour of your broker. Value Rs 60,000.", null),
        inv("JM-DEPOSI", "Margin pledge invoked: 20 qty of GAMMA TECH transferred from BO ID 1234567800012345.", null),
        inv("VM-ZENBRK", "e-DIS request for 5 shares of DELTA CORP authorised. Securities will be debited from your demat account on T+1.", null),
        inv("JM-DEPOSI", "Securities debited: ISIN INE987Z01012 Qty 15 from your demat. DP ID IN300123 Client ID 10001234", null),
        inv("VM-ZENBRK", "Your OTP for e-DIS authorisation is 739201. Do not share it with anyone.", null),
        inv("JM-DEPOSI", "Your demat account XXXX5678 has been opened successfully. BO ID 1234567800015678", null),
        inv("VM-PEAKMF", "Your SIP of Rs 3,000 in Peak Midcap Fund, Folio XXXX1234 has been registered successfully. First instalment on 10-Oct-2026.", null),
        inv("VM-PEAKMF", "Reminder: SIP instalment of Rs 5,000 for folio XXXX1234 is due on 05-Oct-2026. Please keep sufficient balance in your bank account.", null),
        inv("VM-PEAKMF", "SIP instalment of Rs 5,000 in folio XXXX1234 could not be processed as the payment was rejected by your bank.", null),
        inv("VM-FOLIOS", "CAS for Aug-2026 has been emailed to your registered email ID. Please verify your folio details.", null),
        inv("VM-PEAKMF", "NAV of Peak Flexi Cap Fund - Direct Growth as on 19-Sep-2026 is Rs 110.2345.", null),
        inv("VM-PEAKMF", "NFO alert! Peak Manufacturing Fund opens on 01-Oct-2026. Invest now with SIP starting Rs 500. Returns up to 18% in past schemes*. T&C apply", null),
        inv("VM-PEAKMF", "Start your SIP of Rs 1,000 today in folio XXXX1234 and grow your wealth!", null),
        inv("VM-PEAKMF", "123456 is your OTP to confirm redemption of 50 units in folio XXXX1234. Do not share.", null),
        inv("VM-PEAKMF", "OTP for purchase of Rs 5,000 in Peak Flexi Cap Fund is 482913. Valid for 5 mins.", null),
        inv("BZ-ORBITM", "Redemption request for Rs 20,000 in folio XXXX1234 received. Amount will be credited within 3 working days.", null),
        inv("BZ-TRDNOW", "Open a free demat account in 5 minutes! Zero brokerage on delivery trades. Apply now: https://x.example", null),
        inv("VM-PEAKMF", "Dear Investor, KYC for your folio XXXX1234 has been updated successfully.", null),
        inv("BZ-TRDNOW", "Markets closed higher today. Our desk recommends BUY ACME @ 2,400 target 2,700. Invest now!", null),
        inv("VM-NOVABK", "Invest in top mutual funds with SIP starting Rs 100 on our app. T&C apply", null),
        inv("VM-SILVMF", "Your SIP in Silver Balanced Advantage Fund has been paused as requested.", null),
    )

    val all: Map<String, List<InvestCase>> = linkedMapOf(
        "fund purchases" to fundPurchases,
        "fund redemptions and switches" to fundRedemptionsAndSwitches,
        "fund income" to fundIncome,
        "valuations" to valuations,
        "trades" to trades,
        "bank side" to bankSide,
        "not transactions" to notTransactions,
    )
}
