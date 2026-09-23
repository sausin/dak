package app.dak.classify.scam

/** How strongly a message looks like a fake bank-credit / "sent by mistake, please return" scam. */
public enum class ScamLevel {
    /** No fake-credit signals (or too weak to mention). */
    NONE,

    /** Some signals: show a gentle warning, never hide the message. */
    SUSPICIOUS,

    /** Several strong signals: warn prominently and keep the claimed money out of the ledger. */
    LIKELY_SCAM,
}

/**
 * One reason behind a [ScamVerdict]. [code] is stable (stored in index labels as `scam-reason:<code>`), so never
 * rename a code; add new entries instead. [weight] is the score this reason adds (see [FakeCreditDetector]).
 */
public enum class ScamReason(public val code: String, public val weight: Int) {
    /** A bank-style credit alert from a 10-digit mobile / long-code number: banks never send alerts from these. */
    CREDIT_ALERT_FROM_PHONE_NUMBER("phone-credit-alert", 45),

    /** A bank-style debit alert from a phone number (fake debits push victims to call a "helpline"). */
    DEBIT_ALERT_FROM_PHONE_NUMBER("phone-debit-alert", 30),

    /** A phone-number sender signs as / names a bank or wallet ("-SBI", "HDFC Bank"). */
    PHONE_NUMBER_CLAIMS_BANK("phone-claims-bank", 25),

    /** A non-DLT alphanumeric sender that looks like a bank header ("HDFC-BANK", "SBIBANK"): likely spoofed. */
    LOOKALIKE_SENDER("lookalike-sender", 60),

    /** A sender outside India's registered-header format (or an unknown header) claims to be a bank. */
    UNVERIFIED_SENDER("unverified-sender", 30),

    /** A bank's real header name, but without the DLT prefix (`HDFCBK` instead of `VM-HDFCBK-S`). */
    UNPREFIXED_BANK_HEADER("unprefixed-header", 20),

    /** A credit alert sent over the promotional route (`-P`): banks send alerts as transactional/service. */
    PROMOTIONAL_ROUTE("promotional-route", 30),

    /** The bank named in the text is not the brand the (known, non-bank) sender header belongs to. */
    BRAND_MISMATCH("brand-mismatch", 45),

    /** The masked account number matches none of the user's known accounts at that bank. */
    UNKNOWN_ACCOUNT("unknown-account", 25),

    /** The user has known accounts, but none at the bank the message claims. */
    NO_ACCOUNT_AT_BANK("no-account-at-bank", 15),

    /** "Sent by mistake", "please return", "galti se", "wapas kar do", "वापस"... with money context. */
    RETURN_REQUEST("return-request", 40),

    /** A credit/debit alert that contains a mobile number (banks never ask you to call a mobile or pay back). */
    MOBILE_NUMBER_IN_ALERT("mobile-in-alert", 30),

    /** A UPI ID or payment link next to a request to return money. */
    PAYMENT_HANDLE_WITH_RETURN("payment-handle", 15),

    /** A link in a money message from an unverified sender. */
    LINK_IN_ALERT("link-in-alert", 15),

    /** "Enter your UPI PIN to receive": a PIN is only ever needed to PAY. */
    PIN_TO_RECEIVE("pin-to-receive", 60),

    /** "You have received ₹X, tap/approve to accept": the UPI collect trick (approving pays the scammer). */
    COLLECT_REQUEST("collect-request", 40),

    /** A personal message about money shortly after an unverified credit alert (the "return it" follow-up). */
    FOLLOW_UP_AFTER_CREDIT("follow-up", 45),

    /** Asks to return money soon after a genuine-looking credit: route it through the bank, never directly. */
    RETURN_AFTER_GENUINE_CREDIT("return-after-credit", 30),
    ;

    public companion object {
        /** The reason with [code], or null (unknown codes from a newer version are ignored). */
        public fun fromCode(code: String): ScamReason? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The detector's result.
 *
 * @property claimedInstitution display name of the bank / wallet the message claims to come from ("State Bank of
 *   India"), if one is named.
 * @property score raw score (sum of reason weights, halved for saved contacts); for tests and debugging.
 */
public data class ScamVerdict(
    val level: ScamLevel,
    val reasons: List<ScamReason>,
    val claimedInstitution: String? = null,
    val score: Int = 0,
) {
    public val isFlagged: Boolean get() = level != ScamLevel.NONE

    public companion object {
        public val None: ScamVerdict = ScamVerdict(ScamLevel.NONE, emptyList())
    }
}

/** Direction of money in a message, as the caller's transaction parser saw it (kept free of :finance types). */
public enum class HintDirection { CREDIT, DEBIT }

/**
 * Transaction details the caller already parsed, if any (e.g. from `app.dak.finance.parser.TransactionParser`).
 * All optional: the detector does its own minimal wording/amount detection.
 */
public data class TransactionHint(
    val direction: HintDirection? = null,
    val amountMinor: Long? = null,
    val last4: String? = null,
)

/**
 * An account the user is known to have (from their genuine ledger).
 *
 * @property institution bank/wallet name as the ledger knows it ("HDFC Bank", "SBI").
 * @property maskedDigits the visible digits of the account/card number (e.g. `"1234"` from `XX1234`).
 */
public data class AccountHint(val institution: String, val maskedDigits: String)

/**
 * A recent incoming message (for follow-up detection). The caller passes messages from the last 48 hours: from the
 * same sender, and any already flagged as fake credit alerts from other senders.
 *
 * @property flaggedCredit the message was already flagged as a fake credit alert (e.g. its index labels say so).
 */
public data class RecentMessage(
    val address: String,
    val body: String,
    val dateMillis: Long,
    val flaggedCredit: Boolean = false,
)
