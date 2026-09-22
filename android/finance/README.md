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
  symbol→ISO map (`$`→USD, `₹`/`Rs`/`Rs.`→INR, `€`→EUR, `£`→GBP, `¥`→JPY), and the set of ISO codes
  recognised as bare currency codes (AED, USD, GBP, SGD, THB, KWD, ...).
- **`MoneyParser`** — parses amounts as written in SMS text.
  - `parse(text, symbolMap = CurrencyTable.defaultSymbolToCurrency): Money?` — first amount found
    with an explicit currency symbol/code attached; a bare number is never treated as money.
  - `findAll(text, symbolMap): List<MoneyOccurrence>` — every such amount, each with its match
    range, so callers can reason about which amount in a longer message is which (see
    `TransactionParser`).
  - Handles: `Rs.1,234.50`, `INR 1234`, `₹ 12,34,567` (Indian grouping), `Rs 500/-`, `AED 120.50`,
    `USD 42.10`, `$42.10` (symbol default configurable via `symbolMap`), `€10`, `EUR 9,50`
    (European decimal comma, disambiguated from a thousands separator by matching the target
    currency's minor-unit digit count).

## `parser` — `TransactionParser`, `InstitutionTable`

- **`TransactionParser.parse(sender: String, body: String): ExtractedTransaction?`** — the main
  entry point. Returns `null` for anything that isn't a completed transaction:
  - OTPs that happen to mention an amount ("OTP for txn of Rs 500 is 123456").
  - Promotions ("cashback up to", "flat X% off", "use code", ...).
  - Bill/statement reminders ("due on", "minimum amount due", ...) — see `parseBillReminder` below.
  - Otherwise extracts: direction (debit/credit keywords — debited, spent, withdrawn, sent, paid,
    purchase, "txn of", used for/at, auto-debit vs. credited, received, deposited, refund),
    amount + currency (via `MoneyParser`, picking the non-balance occurrence as the transaction
    amount), instrument (UPI > credit card > debit card/account > wallet > unknown), last-4 (card
    or account, several header/format variants), merchant (`at X`, `to VPA x@y`, `Info: ...`, `to X`),
    UPI/RRN/txn reference, available balance (amount + currency, from an "Avl/Available Bal[ance]"
    context), and institution from the sender header.
- **`TransactionParser.parseBillReminder(sender, body): BillReminder?`** — the optional separate
  parse for due-date/minimum-due messages the main parser deliberately excludes.
- **`InstitutionTable.institutionFor(sender: String): String?`** — small local sender→institution
  table (HDFCBK, ICICIB/ICICIT, SBI family, AXISBK, KOTAKB, PAYTMB, PHONPE, AMZNPB, and more Indian
  banks); independent of `:classify`'s richer sender-identity table.

## `ledger` — `Account`, `LedgerEntry`, `Ledger`, `BalanceState`, `BillingCycle`

- **`Account`** — id derived from `institution + instrument + last4` (`Account.idFor(...)`), plus
  `type` (BANK_ACCOUNT / CREDIT_CARD / WALLET / UNKNOWN, derived from `InstrumentType`),
  `homeCurrency` (taken from a balance-bearing SMS when available, else a caller-supplied default —
  INR for Indian institutions), and an optional `statementDay` for cards.
- **`LedgerEntry`** — one posted transaction: `messageKey`, `dateMillis`, `original` (`Money`, as
  written), `indicativeHome` (`Money?`), `rate`/`rateDateMillis`, `settled: Boolean`,
  `effectiveMarkupPercent`, `balanceAfter`, `merchant`, `reference`.
- **`Ledger.apply(inputs: List<LedgerInput>, rates: RatesTable? = null, defaultHomeCurrency = { "INR" }, statementDayFor = { null }): List<AccountLedger>`**
  — pure function grouping a flat message stream into one `AccountLedger` per account. A
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
  guessed.
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
