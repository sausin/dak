package app.dak.finance.ledger

import app.dak.core.model.ExtractedTransaction
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
    /** The number as the bank last showed it (e.g. `XX440065`), when the SMS showed a mask; see [ExtractedTransaction.maskedNumber]. */
    val maskedNumber: String? = null,
) {
    /** Every digit the bank shows of this account's number (e.g. `440065`), falling back to [last4]. */
    val visibleDigits: String? get() = maskedNumber?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() } ?: last4

    val type: AccountType
        get() = when (instrument) {
            InstrumentType.CREDIT_CARD -> AccountType.CREDIT_CARD
            InstrumentType.BANK_ACCOUNT, InstrumentType.UPI -> AccountType.BANK_ACCOUNT
            InstrumentType.WALLET -> AccountType.WALLET
            InstrumentType.UNKNOWN -> AccountType.UNKNOWN
        }

    companion object {
        /**
         * Derives a stable account id from institution + instrument + the account's visible digits (usually the
         * last 4; all of them when the bank shows more, e.g. `440065` for `XX440065`), as sender-merge groups do for
         * threads.
         */
        fun idFor(institution: String?, instrument: InstrumentType, last4: String?): String {
            val inst = institution?.uppercase()?.replace(Regex("\\s+"), "_") ?: "UNKNOWN"
            val last = last4 ?: "0000"
            return "$inst:${instrument.name}:$last"
        }

        /**
         * The account id of a parsed transaction. Uses every visible digit when the SMS shows more than 4, so
         * `XX440065` and `XX120065` (different accounts sharing a last-4) never collide; a bank that later shows
         * fewer digits for the same account (`XX40065`) yields a different id, which [AccountMatcher] detects and
         * the user confirms (see [AccountAliases]).
         */
        fun idOf(transaction: ExtractedTransaction): String =
            idFor(transaction.institution, transaction.instrument, digitsOf(transaction))

        /** The digits that identify [transaction]'s account: all visible digits when more than 4, else last-4. */
        fun digitsOf(transaction: ExtractedTransaction): String? {
            val visible = transaction.maskedNumber?.filter { it.isDigit() }.orEmpty()
            return if (visible.length > 4) visible else transaction.last4
        }

        /** Parts of an id made by [idFor]: (institution key, instrument name, digits), or null if malformed. */
        fun partsOf(accountId: String): Triple<String, String, String>? {
            val parts = accountId.split(':')
            if (parts.size != 3) return null
            return Triple(parts[0], parts[1], parts[2])
        }
    }
}
