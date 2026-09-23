package app.dak.finance.ledger

import app.dak.core.model.InvestmentAction
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.Money
import java.math.BigDecimal

/**
 * One transaction posted to an [Account]'s ledger, built from a single SMS.
 *
 * The original amount and currency are exactly as written in the SMS. [indicativeHome] is only
 * populated for a foreign-currency entry (via [app.dak.finance.rates.RatesTable]) or is equal to
 * [original] when the entry is already in the account's home currency. [settled] becomes true
 * once [app.dak.finance.reconcile.Reconciler] matches a foreign estimate to its actual bank
 * settlement, at which point [indicativeHome] is replaced by the real settled amount and
 * [effectiveMarkupPercent] records the forex markup the bank actually charged.
 */
data class LedgerEntry(
    /** Identifies the source SMS (a `Message` key elsewhere in the app); never re-derived from content. */
    val messageKey: String,
    val dateMillis: Long,
    val direction: TransactionDirection,
    val original: Money,
    val indicativeHome: Money? = null,
    /** Units of home currency per 1 unit of [original]'s currency, if a conversion was applied. */
    val rate: BigDecimal? = null,
    val rateDateMillis: Long? = null,
    val settled: Boolean = true,
    /** (settlement - estimate) / estimate * 100, only meaningful once [settled] via reconciliation. */
    val effectiveMarkupPercent: BigDecimal? = null,
    /** The available balance stated in the same SMS, if any (bank/wallet accounts only). */
    val balanceAfter: Money? = null,
    val merchant: String? = null,
    val reference: String? = null,
    /**
     * Set when this entry was posted to a bank account through another account whose SMS named it: the id of that
     * debit card or loan (e.g. a card spend "debited from A/c XX1234 using Debit Card XX5678" appears on the card and,
     * with this set to the card's id, on the account). Null for the account's own SMS.
     */
    val viaAccountId: String? = null,
    /**
     * Money moved between the user's own accounts or into / out of their own investments: a bank's SIP / mutual-fund
     * debit, a fund's allotment, a redemption payout, a trade. It moves the balance like any entry but is never
     * counted as spending or income ([app.dak.finance.passbook.AccountGroups.spentSince],
     * [app.dak.finance.passbook.Passbook.monthlyTotals]). Every entry of an investment account except a dividend is
     * one.
     */
    val transfer: Boolean = false,
    /** For an investment account: what the entry records (purchase, redemption, switch, dividend, buy, sell, valuation). */
    val investmentAction: InvestmentAction? = null,
    /** Units / shares moved, a plain decimal string, when the SMS stated them. */
    val units: String? = null,
    /** NAV / price per unit, a plain decimal string in [original]'s currency, when the SMS stated it. */
    val unitPrice: String? = null,
    /**
     * Units / shares held after the entry, when the SMS stated them. Not persisted per entry (the index keeps the
     * account's latest, `AccountLedger.unitsHeld`), so it is null on entries read back from storage.
     */
    val unitsHeld: String? = null,
) {
    /** A statement of value only (no money moved): shown as a valuation line, never summed. */
    val isValuation: Boolean get() = investmentAction == InvestmentAction.VALUATION

    val isForeign: Boolean get() = original.currencyUpper != (indicativeHome?.currencyUpper ?: original.currencyUpper)

    /** The best-known value of this entry in home currency: the settled/indicative value, or the original if neither exists. */
    val homeValue: Money get() = indicativeHome ?: original
}
