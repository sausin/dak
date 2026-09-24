package app.dak.classify

/**
 * Labels the template bundle's generic investment rules put on a [app.dak.core.model.Classification] (stored in the
 * index's `labels` column like every label; no schema change). Notification routing reads them.
 *
 * - [ALERT] (`investment-alert`, rule `invest-security-alert`): a security alert about the user's holdings with no
 *   money moving: shares / securities debited from a demat account, a pledge created or invoked, an e-DIS
 *   authorisation. Loud (the Alerts channel); never a ledger entry (`app.dak.finance.parser.TransactionParser`).
 * - [UPDATE] (`investment-update`, rule `invest-update`): a routine folio / demat update: allotment, redemption,
 *   switch, IDCW, NAV, valuation, CAS, contract note. Quiet (the General channel): the money itself was already
 *   alerted loudly by the bank's own debit / credit SMS.
 *
 * NFO and "invest now" offers are promotions (rule `invest-nfo-promo`). A bank's own debit / credit on a masked
 * account ("debited from A/c XX1234 towards SIP, folio 1234") matches `txn-account-movement` first: an ordinary, loud
 * transaction with no investment label. Every rule is vocabulary only: no fund house, registrar, broker or depository
 * name.
 */
public object InvestmentLabels {
    public const val ALERT: String = "investment-alert"
    public const val UPDATE: String = "investment-update"
}
