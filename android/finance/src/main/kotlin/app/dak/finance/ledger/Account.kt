package app.dak.finance.ledger

import app.dak.core.model.InstrumentType

/** Broad shape of an [Account], used to decide how balance/outstanding is modelled. */
enum class AccountType { BANK_ACCOUNT, CREDIT_CARD, WALLET, UNKNOWN }

/**
 * A financial account inferred from SMS traffic: an institution plus an instrument plus (usually)
 * a last-4. Two SMS about "HDFC Bank" + "Card" + "1234" always resolve to the same [Account.id],
 * so entries from many messages accumulate into one ledger per real-world account.
 */
data class Account(
    val id: String,
    val institution: String,
    val instrument: InstrumentType,
    val last4: String?,
    /** The account's own currency, taken from a bank SMS rather than assumed; INR by default for Indian institutions. */
    val homeCurrency: String,
    /** Day of month (1-31) the credit-card statement is generated, if known/configured. Meaningless for non-cards. */
    val statementDay: Int? = null,
) {
    val type: AccountType
        get() = when (instrument) {
            InstrumentType.CREDIT_CARD -> AccountType.CREDIT_CARD
            InstrumentType.BANK_ACCOUNT, InstrumentType.UPI -> AccountType.BANK_ACCOUNT
            InstrumentType.WALLET -> AccountType.WALLET
            InstrumentType.UNKNOWN -> AccountType.UNKNOWN
        }

    companion object {
        /** Derives a stable account id from institution + instrument + last4, as sender-merge groups do for threads. */
        fun idFor(institution: String?, instrument: InstrumentType, last4: String?): String {
            val inst = institution?.uppercase()?.replace(Regex("\\s+"), "_") ?: "UNKNOWN"
            val last = last4 ?: "0000"
            return "$inst:${instrument.name}:$last"
        }
    }
}
