# :finance

Pure-Kotlin JVM module (`app.dak.finance`, no `android.*` imports) implementing the parsing,
ledger, reconciliation and passbook logic behind "Finance ledger and foreign transactions" and
"Passbook" in the build plan. Depends on `:core-model` for `ExtractedTransaction`,
`TransactionDirection` and `InstrumentType`; has no dependency on `:classify`.

Every amount is kept as written in the source SMS (minor units + ISO 4217 code) until a rate
table or a settlement message is used to derive an indicative or actual home-currency value.
Nothing is ever silently converted or invented — see `BalanceState` and `Reconciler` below.

## `money` — `Money`, `MoneyParser`, `CurrencyTable`

- **`Money(amountMinor: Long, currency: String)`** — an exact amount, no `Double` anywhere.
  - `format(withSymbol = true, grouping = true): String` — Indian (lakh/crore) grouping for INR
    (`₹1,23,456.78`), Western grouping otherwise (`$1,234.50`); respects each currency's minor-unit
    exponent (`CurrencyTable.minorUnitExponent`, e.g. 0 for JPY, 3 for KWD/BHD/OMR).
  - `formatIndicative(): String` — `"≈…"`, for estimated/unsettled values.
  - `+`, `-`, `unaryMinus`, `abs()`, `compareTo` — all require matching currencies (throw otherwise).
  - `Money.ofMajor(BigDecimal, currency)`, `Money.convert(amount, toCurrency, rate)`, `Money.zero(currency)`.
- **`CurrencyTable`** — minor-unit exponents, default display symbols, the default
  symbol/word→ISO map (`$`/`US$`→USD, `₹`/`Rs`/`Rs.`/`रु`/`रू`/`रुपये`/`ரூ`→INR, `€`→EUR, `£`→GBP,
  `¥`/`円`→JPY, `元`/`RMB`→CNY, `₩`→KRW, `৳`/`Tk`→BDT, `₨`→PKR, `රු`→LKR, `د.إ`→AED, `ر.س`/`﷼`→SAR,
  `฿`→THB, `RM`→MYR, `Rp`→IDR, `₱`→PHP, `₫`→VND, `₺`→TRY, `C$`→CAD, `A$`→AUD, `S$`→SGD, `HK$`→HKD),
  and the set of ISO codes recognised as bare currency codes (AED, USD, GBP, SGD, THB, KWD, CHF, ...).
  `symbolMapFor(homeCurrency: String?)` adapts that map to the reader's region: a bare `$` means the home currency
  where it is written `$` (CAD, AUD, NZD, SGD, HKD, MXN...), else USD; `₨` means NPR/LKR/... at home. Unknown home
  currency = the defaults.
- **`DigitNormalizer`** (internal) — folds any Unicode `Nd` decimal digit (Devanagari, Bengali,
  Gujarati, Gurmukhi, Tamil, Telugu, Kannada, Malayalam, Arabic-Indic, Extended Arabic-Indic,
  full-width, ...) to ASCII `0`-`9` before any numeric regex/`BigDecimal` parsing runs. Used by
  `MoneyParser` and `TransactionParser`; not part of the public API.
- **`MoneyParser`** — parses amounts as written in SMS text.
  - `parse(text, symbolMap = CurrencyTable.defaultSymbolToCurrency): Money?` — first amount found
    with an explicit currency symbol/code attached; a bare number is never treated as money.
  - `findAllForSearch(text, symbolMap): List<AmountMention>` — every amount in a body for search
    indexing: the currency-bearing ones plus bare numbers unmistakably formatted as amounts
    ("5,00,000", "500,000.00", "500000.00"; never plain digit runs such as phones/OTPs). Each
    `AmountMention(major, currency?, range)` exposes `hundredths` (value × 100, currency-independent).
    Scans the first 4000 chars.
  - Same amount, any spelling: "500,000. 00" / "500,000 .00" (stray blank around the point),
    "5,00,000.00", "500000", "Rs.5,00,000/-", "INR 500000", "Rs 5 lakh", "₹0.05 crore" all parse to
    ₹5,00,000.00. "Cr" alone is *not* crore (it means credited in bank SMS).
  - `findAll(text, symbolMap): List<MoneyOccurrence>` — every such amount, each with its match
    range, so callers can reason about which amount in a longer message is which (see
    `TransactionParser`).
  - Handles: `Rs.1,234.50`, `INR 1234`, `₹ 12,34,567` (Indian grouping, also with an NBSP/narrow-NBSP
    gap or thousands separator), `Rs 500/-`, `AED 120.50`, `USD 42.10`, `$42.10` (symbol default
    configurable via `symbolMap`), `€10`, `EUR 9,50` (European decimal comma, disambiguated from a
    thousands separator by matching the target currency's minor-unit digit count), `1.234,56`
    (European thousands-dot), `1'234.50` (Swiss apostrophe thousands), `Rs:500.00` (colon after the
    currency), and amounts written with any non-ASCII decimal-digit script (see `DigitNormalizer`).
  - Never money: a number glued to a letter, digit or mask with no currency before it (`XX1234 INR 500` is
    ₹500, not ₹1,234), and a suffix currency followed by another number (`Ref 6248123 INR 500`, `12-09-2026
    INR 750`: the code belongs to the next amount).

## `parser` — `TransactionParser`, `InstitutionTable`

- **`TransactionParser.parse(sender: String, body: String, symbolMap = CurrencyTable.defaultSymbolToCurrency): ExtractedTransaction?`**
  (pass `CurrencyTable.symbolMapFor(regionHomeCurrency)` so `$` follows the SIM's region) — the main
  entry point. Every rule is about the structure and vocabulary of transaction SMS in general — never one bank's
  template or brand. Returns `null` for anything that isn't a completed transaction:
  - OTPs that happen to mention an amount ("OTP for txn of Rs 500 is 123456", "Use 482913 to authorise..."); a
    safety footer ("Never share your OTP", "Bank never asks for OTP") does not make a transaction alert an OTP.
  - Promotions and offers ("cashback up to", "flat X% off", "up to Rs 5,00,000", "pre-approved", "apply now", ...).
  - Payment / collect requests ("has requested Rs 500", "payment request"), statements and mini-statements.
  - Failed, declined, cancelled, bounced, "could not be processed" movements — unless the message also states a
    completed refund / reversal / credit, which is then the user's credit.
  - Future, conditional or set-up movements ("will be debited", "to be credited", "if debited", "once it is
    credited", "AutoPay set up", "e-mandate registered", "scheduled"), bill/due reminders — see `parseBillReminder`.
  - Balance-only messages (every amount is a balance / limit / due) and service messages (only fees mentioned).
  - Otherwise extracts, in this order (`DirectionCues`, `InstrumentDetector`, `AmountRoles`, internal; `SmsWords`
    reads the few words next to a position within one clause):
    - **Direction**: from the cues that state a movement as done. A completed refund/reversal wins; else the first
      finite verb (debited, spent, withdrawn, charged, used for/at, sent/paid/transferred vs. credited, received,
      deposited, disbursed, added to wallet/account); else the first transaction noun ("txn of", "payment of",
      "purchase", "debit of", "withdrawal", "transfer", "Dr."/"Cr.", "deposit of"). Transfer verbs are the user's
      credit when the money went "to you" / "to your" account (not card).
    - **Whose numbers**: every masked number (A/c, Ac, a/c no., acct, account, card, DC/CC, loan; `XX1234`,
      `X1234`, `**1234`, `...1234`, `4375XXXX1234` (leading digits dropped), `ending [with|in] 1234`, 3+ visible
      digits, or exactly four unmasked digits right after the keyword) gets a role from its neighbouring words:
      the other party's (beneficiary / payee / recipient / receiver / remitter / sender, before or right after it),
      the user's ("your", "ur", "own", "linked to"), and its side (source: "from", "by", "debited [to]", "A/c XX1
      debited"; destination: "to", "into", "towards", "in", "credited", "A/c XX2 credited"; means: "on", "using",
      "via"). The user's numbers are: never the other party's; always "your"; cards and loans; otherwise any account
      not on the far side of the movement (a debit's destination, a credit's source). So "debited from A/c XX1234 and
      credited to A/c XX5632" is XX1234's debit and XX5632 is never recorded as the user's. A credit whose only named
      account is the other party's destination ("credited to beneficiary A/c XX5632 for your NEFT") confirms the
      user's outgoing transfer: the user's debit when the SMS also names "from your A/c ...", else `null`. A payment
      *to* the user's card ("paid to Credit Card XX9876") is that card's credit, or the paying account's debit when
      the account is named.
    - **Amount**: each amount gets a role from its neighbouring words — balance, limit, due/outstanding, fee/charges/
      markup, cashback/reward, or a converted equivalent (in brackets right after another amount, "approx", "INR
      equivalent"). The transaction amount is the non-role amount nearest the deciding cue (a cashback only for a
      credit); a number with no currency is only read right after "debited by / credited with" and before the end of
      that phrase, in the currency the SMS uses elsewhere or INR for an Indian DLT sender ("debited by 250.0 on").
    - **Balance**: the first amount with a balance role (Avl/Avail/Available Bal[ance], Bal, Clr Bal, "balance is",
      "Balance:", "AvlBal"); for a loan SMS naming no account, the outstanding amount.
    - Instrument (see "Instruments" below), last-4, merchant (`to [VPA] x@y` for debits, `from/by [VPA] x@y` for
      credits, `Info: ...`, `at X`, `to X` when X is a name), UPI/RRN/UTR/ref/txn reference, and institution from
      the sender header.
- **Instruments** (`InstrumentDetector`, internal): `InstrumentType` = `BANK_ACCOUNT`, `CREDIT_CARD`,
  `DEBIT_CARD`, `PREPAID_CARD` (prepaid/forex/travel/multi-currency/gift cards, Wise/Revolut), `WALLET` (wallet,
  Amazon Pay balance, Airtel Money, MobiKwik...), `UPI` (VPA/UPI only, no account named), `LOAN`, `UNKNOWN`, and the
  investment accounts `MUTUAL_FUND` / `DEMAT` (read by `InvestmentParser` before any of this; see "Investments"). Decided
  from the words right before each masked number ("Debit Card XX1234", "DC XX..", "CC XX..", "Forex Card XX..",
  "Loan A/c XX..", "A/c XX..") first, then body wording (credit-card issuers like SBI Card/Amex/Sapphire, "EMI ... loan",
  wallets, UPI/VPA handles, "account"). A UPI SMS that names "A/c XX1234" is `BANK_ACCOUNT`. A bare "Card XX1234" is a
  debit card when the SMS names the account debited or states an available *balance*, a credit card when it states a
  limit or names a card issuer, else a credit card (legacy; keeps ids stable; the user can change the type).
  `ExtractedTransaction.linkedMaskedNumber` (core-model) = the bank account a debit-card or loan SMS also names
  ("debited from A/c XX1234 using Debit Card XX5678"); never inferred across messages.
- **`TransactionParser.parseBillReminder(sender, body): BillReminder?`** — the optional separate
  parse for due-date/minimum-due messages the main parser deliberately excludes.
- **`InstitutionTable.institutionFor(sender: String): String?`** — small local sender→institution
  table (HDFCBK, ICICIB/ICICIT, SBI family, AXISBK, KOTAKB, PAYTMB, PHONPE, AMZNPB, and more Indian
  banks); independent of `:classify`'s richer sender-identity table. Display names only: a DLT header missing from
  the table resolves to the header itself. `isDltSender(sender)` — whether a sender has the Indian DLT shape.
- **Corpus** (`src/test/.../parser/corpus`): ~260 made-up messages in the many styles banks, cards, UPI apps,
  wallets, lenders and a few foreign banks use, each with its expected outcome (null, or direction, amount, currency,
  instrument, own number, linked account, balance, merchant, and account digits that must never become the user's).
  Add a case there whenever a real-device message is misread; fix it with a general rule, not a template.

## Investments — `InvestmentParser` (internal), `InvestmentAction`

`TransactionParser.parse` first asks `InvestmentParser` whether the message is a mutual-fund folio's or a demat
account's own message; if so its answer (a transaction, or null) is final, otherwise the bank-side rules above run.
Generic by rule, like everything here: vocabulary and structure only (folio, units, NAV, SIP, IDCW / dividend,
allotted, redemption, switch, ISIN `[A-Z]{2}[A-Z0-9]{9}\d`, demat, BO ID, DP ID, client ID / code, contract note,
trade confirmation, bought / sold qty @ price, pledge, e-DIS, CAS / consolidated account statement, current value /
valuation). No fund house, registrar, broker or depository is named anywhere; the institution is the DLT sender
header (`InstitutionTable` resolves unknown headers to the header itself, as for new banks).

- **Which side a message is on.** The investment side when it names a folio ("Folio No. XXXX1234", "Folio
  12345678/90": the "/90" check suffix is dropped) or a demat id ("BO ID 1234567800012345", "DP ID IN300123 Client ID
  12345678", "Client Code AB1234", "Demat a/c XXXX1234"), or carries fund / trade detail (units with NAV / allotment /
  redemption / switch / IDCW, a trade line with a securities word, a contract note, a CAS). A bank's own debit or
  credit that only mentions a SIP / folio next to the user's bank account ("Rs 5,000 debited from A/c XX1234 towards
  SIP, Folio 12345678") stays the bank's. A fund naming the account it paid ("credited to your bank a/c XX4321") is
  still the fund's and that account is never recorded, so the bank account is never debited twice.
- **Ids.** `InstrumentType.MUTUAL_FUND` / `DEMAT` (both `AccountType.INVESTMENT`), `maskedNumber` = `XXXX` + the last
  four digits however much the SMS shows, so one folio keeps one account id across styles and the full folio / BO id is
  never stored (demat: BO ID > client id > demat a/c; a BO ID ends in the client id, so both give the same id).
- **What is recorded** (`ExtractedTransaction.investmentAction`, `units`, `unitPrice`, `unitsHeld`, `isin`):
  `PURCHASE` (SIP / lumpsum / allotment / IDCW reinvestment) and `SWITCH` and `BUY` (contract note, IPO allotment) are
  credits to the investment account ("invested"); `REDEMPTION` and `SELL` debits; `DIVIDEND` / IDCW a credit (income).
  The amount is the money figure nearest the action word that is not a NAV / price ("NAV Rs 45.6789", "@ Rs 2,445.12
  per unit"), a fee (stamp duty, charges, brokerage, STT, exit load, TDS) or a value; a trade prefers the net amount
  ("net amount payable", "trade value", "total") and falls back to qty x price. A contract note with both buys and
  sells records the net obligation (payable = buy, receivable = sell) without units.
- **Current value.** "Current value / market value / valuation / holdings value / value of your investments" is the
  account's balance (`balanceMinor`), like a bank's Avl Bal; a pure valuation / holdings / CAS message becomes a
  `VALUATION` entry of amount 0 with that balance (the index keeps no amount / direction for it on the message row,
  so it shows no amount chip and matches no amount search or automation). Units held ("Balance units", "Units held",
  "Total units") become `AccountLedger.unitsHeld`.
- **Not transactions** (null): security alerts (shares / securities / qty debited or transferred from a demat, pledge /
  re-pledge / unpledge / margin pledge / lien, e-DIS) unless they are a trade; NFO and "invest now / returns up to /
  start your SIP" offers; future, pending, registered or due instalments ("will be allotted", "SIP registered",
  "reminder", "due on") unless a completed step is also stated ("processed ... will be credited"); failed / rejected
  instalments; NAV-only notes; CAS or KYC notices with no value; OTPs (checked first, as for banks).
- **Own transfers** (`ExtractedTransaction.ownTransfer`, `LedgerEntry.transfer`). A SIP is one movement of the user's
  own money seen from both sides: the bank's debit (`TransactionParser` marks a debit that names a SIP, mutual fund, MF,
  systematic investment, folio or demat / trading / broking account, and a credit of redemption proceeds or from a
  trading account, but not a dividend or interest) and the fund's allotment (every investment entry except a dividend).
  Both are posted (the bank's balance moves, the folio shows the purchase with units and NAV), and neither is spending
  or income: `AccountGroups.spentSince`, `Passbook.monthlyTotals` (transfers are totalled separately in
  `transfersOutHome` / `transfersInHome`; valuations and switches in neither), `Passbook.spendByMerchant` and the
  index's `LedgerDao.observeDebitsSince` (Passbook "this month" totals) all leave transfers out. An account the user
  marks as a fund / demat by hand has all its non-dividend entries treated as transfers.
- **Passbook.** `AccountType.INVESTMENT` is one section after Loans; its header totals `TotalKind.CURRENT_VALUE` (the
  stated current values per currency, unknown ones counted, never guessed).
- **Classification and routing** (`:classify` template bundle 4): rule `invest-security-alert` (TRANSACTION, label
  `investment-alert`) → notified on the loud Alerts channel; `invest-update` (TRANSACTION, label
  `investment-update`) → the quieter General channel, since the bank's own debit / credit already alerted the money;
  `invest-nfo-promo` → PROMOTION. OTP rules outrank all three (an OTP for a redemption or e-DIS stays an OTP), and
  `txn-account-movement` (a debit / credit on the user's own masked "A/c XX1234", no label) outranks the update rule,
  so a bank's SIP debit that mentions a folio is still a loud transaction alert.
- **Corpus** (`src/test/.../parser/corpus/InvestmentCorpus.kt`): SIP confirmations, allotments, redemptions,
  switches, IDCW, valuations, CAS, contract notes, trades, dividends, the bank side of the same money, demat security
  alerts, pledges, e-DIS, NFO promotions, OTPs and notices, each with its expected outcome; plus a check that no bank
  corpus message and no look-alike ("bought 2 items at Rs 499", "250 units" of electricity, "reward points redeemed")
  becomes an investment.

## `ledger` — `Account`, `LedgerEntry`, `Ledger`, `BalanceState`, `BillingCycle`

- **`Account`** — id derived from `institution + instrument + visible digits` (`Account.idOf(txn)` /
  `Account.idFor(...)`), plus
  `type` (`AccountType.of(instrument)`: BANK_ACCOUNT / CREDIT_CARD / DEBIT_CARD / WALLET / UPI / PREPAID_CARD / LOAN /
  INVESTMENT (mutual fund and demat) / UNKNOWN, declaration order = Passbook group order; `aliasFamily` treats UPI as BANK_ACCOUNT for alias matching),
  `linkedAccountId` (debit card / loan -> the bank account an SMS named; `Account.linkedIdOf(txn)`),
  `homeCurrency` (taken from a balance-bearing SMS when available, else a caller-supplied default —
  INR for Indian institutions, the SIM region's currency otherwise), and an optional `statementDay` for cards.
- **`LedgerEntry`** — one posted transaction: `messageKey`, `dateMillis`, `original` (`Money`, as
  written), `indicativeHome` (`Money?`), `rate`/`rateDateMillis`, `settled: Boolean`,
  `effectiveMarkupPercent`, `balanceAfter`, `merchant`, `reference`, `transfer` (own-account / investment money:
  never spending), `investmentAction`, `units`, `unitPrice`, `unitsHeld`.
- **`Ledger.apply(inputs: List<LedgerInput>, rates: RatesTable? = null, defaultHomeCurrency = Ledger::institutionHomeCurrency, statementDayFor = { null }): List<AccountLedger>`**
  Home currency: a balance-bearing SMS's currency, else `defaultHomeCurrency(institution)` (the default gives INR
  only for institutions `InstitutionTable.countryOf` knows as Indian; `:core-index` adds the SIM region's currency),
  else the currency most of the account's transactions are in. Never INR by assumption.
  — pure function grouping a flat message stream into one `AccountLedger` per account. Extra params:
  `instrumentOverride: (accountId) -> InstrumentType?` (the user's manual type; changes type, never the id). A
  debit-card/loan input with `linkedMaskedNumber` is posted to the card/loan (balance stripped) **and** to the linked
  bank account (with the balance, `LedgerEntry.viaAccountId` = card/loan id), so card spends move the right balance;
  without a named account nothing is linked and no balance is invented. A
  home-currency entry is posted `settled = true` immediately; a foreign-currency entry is posted
  `settled = false` with an indicative `Money` computed from `rates` (or `null` if unavailable).
- **`AccountLedger.balanceState: BalanceState`** — `NoInfo` / `Known(balance, asOfMillis)` /
  `Unknown(sinceMillis, lastKnown)`. Never invents a number: it is always the value from the latest
  balance-bearing SMS, and flips to `Unknown` the moment an unsettled transaction postdates it.
- **`AccountLedger.cardOutstanding(asOfMillis, billingCycle): Money?`** — for `CREDIT_CARD`
  accounts only (`null` otherwise): debits minus credits within the billing cycle, using each
  entry's best-known home-currency value; entries with no resolvable home value are excluded, not
  guessed. Never touches a bank account's balance.
- **`BillingCycle(statementDay: Int)`** — `cycleRange(asOfMillis): LongRange`, UTC, clamped to the
  shortest month when `statementDay` exceeds it.

### Account aliases — `AccountAliases`, `AccountMatcher`, `MaskedNumbers`

- `ExtractedTransaction.maskedNumber` (core-model, optional) keeps the number as far as the SMS shows it
  (`XX440065`); `last4` is its last four digits. `Account.idOf(txn)` uses every visible digit when there are more
  than 4 (so `XX440065` and `XX120065` never collide), else last-4 (old ids unchanged). `Account.maskedNumber` /
  `visibleDigits`, `Account.partsOf(id)`.
- **`AccountMatcher.suggest(accounts: List<AccountObservation>, decidedPairs, coOccurringPairs, aliases): List<AliasSuggestion>`**
  — probable same-account pairs within one institution and `AccountType` whose visible digits are compatible
  (the shorter a suffix of the longer, >= 4 digits: `40065`/`440065`, `0065`/`440065`, or identical digits under two
  instruments). Scored by suffix length, non-overlapping timelines (format switch) and recency. Never merges;
  `AliasSuggestion(accountA, accountB, reason: AliasReason, score)`. `coOccurringPairs(accounts, bodies)` finds pairs
  named together in one message (transfers) — distinct accounts. `MaskedNumbers.findAll(body)` (the same number formats the parser reads).
- **`AccountAliases(aliasToCanonical)`** — user-confirmed merges; `resolve(id)` (chains, cycle-safe),
  `membersOf(id)`, `canonicalOf(a, b)` (more digits wins). `Ledger.apply(..., aliases = ...)` posts alias inputs to
  the canonical account.

### Passbook groups — `AccountGroups`

- **`AccountGroups.group(items: List<T>, facts: (T) -> AccountFacts, lastActivity: (T) -> Long): List<AccountGroup<T>>`**
  — sections in `AccountType` order, empty ones dropped, items newest first. `AccountFacts(account, balance,
  spentThisMonth: List<Money>, outstanding: Money?)`.
- **`GroupTotals(kind: TotalKind, amounts: List<Money>, missingCount, spentThisMonth)`** — per currency, never
  converted. `TotalKind.BALANCE` (bank, wallet, prepaid, loan: sum of `BalanceState.Known` only; Unknown/NoInfo counted
  in `missingCount`), `OUTSTANDING` (credit cards: cycle outstanding when a statement day is set), `SPENT_THIS_MONTH`
  (debit cards, UPI, other), `CURRENT_VALUE` (investments: stated current values, counted like balances).
- `AccountGroups.spentSince(entries, sinceMillis)` (debits that are not transfers), `monthStartUtc(nowMillis)`,
  `sum(amounts)`.

## `rates` — `RatesTable`, `RatesLoader`

- **`RatesTable(base, date, rates: Map<String, String>)`** (`kotlinx.serialization`) —
  `rate(from, to): BigDecimal?`, triangulating through `base` when neither side is the base
  currency; `null` if either currency is missing. `RatesTable.fallback()` is a tiny static table.
- **`RatesLoader.loadBundled(): RatesTable`** loads
  `src/main/resources/app/dak/finance/sample-rates.json` (the OTA-bundle placeholder);
  `RatesLoader.parse(json)` parses a freshly fetched payload of the same shape.

## `reconcile` — `Reconciler`

- **`Reconciler.reconcile(entries, toleranceFraction = 0.06, maxDateDeltaMillis = 5 days): ReconciliationResult`**
  — matches each unsettled foreign-currency estimate to a later home-currency settlement entry in
  the same list, by: same direction, same currency as the estimate's indicative value, settlement
  date on/after the estimate within `maxDateDeltaMillis`, and settlement amount within
  `toleranceFraction` of the indicative estimate (default ±6%, to cover typical forex markup). When
  several settlements qualify, the closest by amount wins; ties break by closest date. A matched
  settlement is merged into the estimate (marked `settled = true`, `indicativeHome` replaced by the
  real amount, `effectiveMarkupPercent` computed) and removed from the result as its own entry, so
  the transaction never double-counts. Deterministic; no settlement is ever matched twice.
  `ReconciliationResult(entries, matches)` — `matches` lists every `(estimateMessageKey,
  settlementMessageKey, markupPercent)` found, for an audit trail.

## `passbook` — `Passbook`

- **`Passbook.monthlyTotals(ledger: AccountLedger): List<MonthlyTotal>`** — one entry per calendar
  month (UTC), each with debits/credits broken out **by original currency** (a month can mix INR
  and, say, AED) plus a best-effort home-currency total (`debitsHome`, `creditsHome`) using each
  entry's settled/indicative value; a foreign amount with no resolvable home value is skipped, not
  guessed. Debits and credits are spending and income only: own-account / investment transfers are in
  `transfersOutHome` / `transfersInHome` (an investment account's "redeemed" / "invested"), and valuations and
  switches in neither.
- **`Passbook.spendByMerchant(ledger, unknownMerchantLabel = "Unknown"): Map<String, Money>`** —
  total debit spend (home currency) per merchant, descending by amount; entries with no merchant
  are bucketed under `unknownMerchantLabel`. Credits are excluded — this is a ledger, not a budget.

## Notes for other modules

- Nothing here reads or writes Android APIs, Room, or the Telephony provider; callers (`:app`,
  automations, UI) own persistence and feed `Ledger.apply`/`Reconciler.reconcile` with entries
  built from stored `ExtractedTransaction`s plus a message key and date.
- `TransactionParser` is deterministic/regex-based by design (matches the "signed JSON bundle of
  ... bank/OTP regexes" tier of the classification pipeline in the build plan); it does not call
  the on-device model or Jev, and does not know about sender merge groups.
- Not implemented here (out of `:finance` scope per the brief): OTA fetch/signing of the rates
  bundle (only bundled-sample loading + a `parse(json)` entry point are provided), and any Room/
  persistence layer for `Account`/`LedgerEntry` (these are plain data classes for callers to store).
