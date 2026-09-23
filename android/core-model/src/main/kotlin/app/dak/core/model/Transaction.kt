package app.dak.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionDirection { DEBIT, CREDIT }

/**
 * The kind of payment instrument a transaction used. Stored and serialized by name, so values are only ever added
 * (never renamed or removed); new values are appended to keep ordinals stable too.
 */
@Serializable
enum class InstrumentType {
    /** A savings/current/checking account ("A/c XX1234"). */
    BANK_ACCOUNT,
    CREDIT_CARD,
    /** A stored-value wallet (Paytm Wallet, Amazon Pay balance, PhonePe wallet, MobiKwik, Airtel Money...). */
    WALLET,
    /** A UPI payment that names only a VPA / UPI app, not the bank account behind it. */
    UPI,
    UNKNOWN,
    /** A debit card; spends reduce the linked bank account (see [ExtractedTransaction.linkedMaskedNumber]). */
    DEBIT_CARD,
    /** A prepaid card: forex / travel / multi-currency cards, gift cards, e-money cards (Wise, Revolut). */
    PREPAID_CARD,
    /** A loan or EMI account. */
    LOAN,

    /** A mutual-fund folio ("Folio No. XXXX1234"): SIP / lumpsum purchases, redemptions, switches, IDCW / dividends. */
    MUTUAL_FUND,

    /** A demat / trading account holding securities ("BO ID ...", "DP ID ... Client ID ...", "Demat a/c XXXX1234"). */
    DEMAT,
}

/**
 * What an investment-account message ([InstrumentType.MUTUAL_FUND] / [InstrumentType.DEMAT]) records. Stored by name
 * (inside the transaction JSON and in the ledger), so values are only ever appended.
 */
@Serializable
enum class InvestmentAction {
    /** Units bought (SIP instalment, lumpsum, additional purchase): money moved from the bank into the folio. */
    PURCHASE,

    /** Units redeemed: money moved from the folio back to the bank. */
    REDEMPTION,

    /** Units switched from one scheme to another within the folio: no money entered or left it. */
    SWITCH,

    /** A dividend / IDCW paid out: income. */
    DIVIDEND,

    /** Securities bought (contract note / trade confirmation). */
    BUY,

    /** Securities sold (contract note / trade confirmation). */
    SELL,

    /**
     * A statement of the holding's current value (valuation, CAS, holdings statement): no money moved; the amount is
     * zero and the value is the balance.
     */
    VALUATION,
}

/**
 * A transaction extracted from a message. Amounts are kept exactly as written: minor units + ISO code.
 * Never pre-converted.
 */
@Serializable
data class ExtractedTransaction(
    val direction: TransactionDirection,
    /** Amount in minor units (paise, cents). */
    val amountMinor: Long,
    /** ISO 4217 code, e.g. "INR", "AED", "USD". */
    val currency: String,
    val instrument: InstrumentType = InstrumentType.UNKNOWN,
    /** Last 4 digits of the account/card, if present. */
    val last4: String? = null,
    val merchant: String? = null,
    val reference: String? = null,
    /** Available balance stated in the same SMS, in minor units of [balanceCurrency]. */
    val balanceMinor: Long? = null,
    val balanceCurrency: String? = null,
    val institution: String? = null,
    /**
     * The account/card number exactly as far as the SMS shows it, mask normalised to `X` and spaces removed
     * (e.g. `XX440065` or `XXXX1234`), if present. [last4] is its last four digits. Banks change how many digits
     * they reveal over time, so ledger code compares the visible digits rather than [last4] alone.
     */
    val maskedNumber: String? = null,
    /**
     * For a [InstrumentType.DEBIT_CARD] or [InstrumentType.LOAN] transaction whose SMS also names the bank account the
     * money moved from ("debited from A/c XX1234 using Debit Card XX5678"): that account's number, normalised like
     * [maskedNumber]. Never inferred across messages; null when the SMS names only one number.
     */
    val linkedMaskedNumber: String? = null,
    /** For an investment account ([InstrumentType.MUTUAL_FUND] / [InstrumentType.DEMAT]): what the message records. */
    val investmentAction: InvestmentAction? = null,
    /** Units (fund) or shares (trade) moved by this transaction, as a plain decimal string ("45.678"), if stated. */
    val units: String? = null,
    /** NAV per unit (fund) or price per share (trade), a plain decimal string in [currency], if stated. */
    val unitPrice: String? = null,
    /** Total units / shares held after this transaction ("Balance units 1,234.567"), if stated. */
    val unitsHeld: String? = null,
    /** The security's ISIN (`[A-Z]{2}[A-Z0-9]{9}\d`), if stated. */
    val isin: String? = null,
    /**
     * Money moved between the user's own accounts or into / out of the user's own investments (a bank's SIP debit, a
     * fund's allotment, a redemption payout): shown on both sides, never counted as spending or income.
     */
    val ownTransfer: Boolean = false,
)
