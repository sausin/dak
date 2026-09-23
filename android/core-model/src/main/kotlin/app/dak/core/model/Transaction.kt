package app.dak.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionDirection { DEBIT, CREDIT }

@Serializable
enum class InstrumentType { BANK_ACCOUNT, CREDIT_CARD, WALLET, UPI, UNKNOWN }

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
)
