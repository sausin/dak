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
)
