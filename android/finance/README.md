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
- **`DigitNormalizer`** (internal) — folds any Unicode `Nd` decimal digit (Devanagari, Bengali,
  Gujarati, Gurmukhi, Tamil, Telugu, Kannada, Malayalam, Arabic-Indic, Extended Arabic-Indic,
  full-width, ...) to ASCII `0`-`9` before any numeric regex/`BigDecimal` parsing runs. Used by
  `MoneyParser` and `TransactionParser`; not part of the public API.
- **`MoneyParser`** — parses amounts as written in SMS text.
  - `parse(text, symbolMap = CurrencyTable.defaultSymbolToCurrency): Money?` — first amount found
    with an explicit currency symbol/code attached; a bare number is never treated as money.
  - `findAll(text, symbolMap): List<MoneyOccurrence>` — every such amount, each with its match
    range, so callers can reason about which amount in a longer message is which (see
    `TransactionParser`).
  - Handles: `Rs.1,234.50`, `INR 1234`, `₹ 12,34,567` (Indian grouping, also with an NBSP/narrow-NBSP
    gap or thousands separator), `Rs 500/-`, `AED 120.50`, `USD 42.10`, `$42.10` (symbol default
    configurable via `symbolMap`), `€10`, `EUR 9,50` (European decimal comma, disambiguated from a
    thousands separator by matching the target currency's minor-unit digit count), `1.234,56`
    (European thousands-dot), `1'234.50` (Swiss apostrophe thousands), and amounts written with any
    non-ASCII decimal-digit script (see `DigitNormalizer`).

## `parser` — `TransactionParser`, `InstitutionTable`

- **`TransactionParser.parse(sender: String, body: String): ExtractedTransaction?`** — the main
  entry point. Returns `null` for anything that isn't a completed transaction:
  - OTPs that happen to mention an amount ("OTP for txn of Rs 500 is 123456").
  - Promotions ("cashback up to", "flat X% off", "use code", ...).
  - Bill/statement reminders ("due on", "minimum amount due", ...) — see `parseBillReminder` below.
  - Otherwise extracts: direction (debit/credit keywords — debited, spent, withdrawn, sent, paid,
    purchase, "txn of", used for/at, auto-debit vs. credited, received, deposited, refund),
    amount + currency (via `MoneyParser`, picking the non-balance occurrence as the transaction
    amount), instrument (see "Instruments" below), last-4 (card
    or account, several header/format variants), merchant (`at X`, `to VPA x@y`, `Info: ...`, `to X`),
    UPI/RRN/txn reference, available balance (amount + currency, from an "Avl/Available Bal[ance]"
    context), and institution from the sender header.
- **Instruments** (`InstrumentDetector`, internal): `InstrumentType` = `BANK_ACCOUNT`, `CREDIT_CARD`,
  `DEBIT_CARD`, `PREPAID_CARD` (prepaid/forex/travel/multi-currency/gift cards, Wise/Revolut), `WALLET` (wallet,
  Amazon Pay balance, Airtel Money, MobiKwik...), `UPI` (VPA/UPI only, no account named), `LOAN`, `UNKNOWN`. Decided
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
  banks); independent of `:classify`'s richer sender-identity table.

## `ledger` — `Account`, `LedgerEntry`, `Ledger`, `BalanceState`, `BillingCycle`

- **`Account`** — id derived from `institution + instrument + visible digits` (`Account.idOf(txn)` /
  `Account.idFor(...)`), plus
  `type` (`AccountType.of(instrument)`: BANK_ACCOUNT / CREDIT_CARD / DEBIT_CARD / WALLET / UPI / PREPAID_CARD / LOAN /
  UNKNOWN, declaration order = Passbook group order; `aliasFamily` treats UPI as BANK_ACCOUNT for alias matching),
  `linkedAccountId` (debit card / loan -> the bank account an SMS named; `Account.linkedIdOf(txn)`),
  `homeCurrency` (taken from a balance-bearing SMS when available, else a caller-supplied default —
  INR for Indian institutions), and an optional `statementDay` for cards.
- **`LedgerEntry`** — one posted transaction: `messageKey`, `dateMillis`, `original` (`Money`, as
  written), `indicativeHome` (`Money?`), `rate`/`rateDateMillis`, `settled: Boolean`,
  `effectiveMarkupPercent`, `balanceAfter`, `merchant`, `reference`.
- **`Ledger.apply(inputs: List<LedgerInput>, rates: RatesTable? = null, defaultHomeCurrency = { "INR" }, statementDayFor = { null }): List<AccountLedger>`**
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
  named together in one message (transfers) — distinct accounts. `MaskedNumbers.findAll(body)`.
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
  (debit cards, UPI, other).
- `AccountGroups.spentSince(entries, sinceMillis)`, `monthStartUtc(nowMillis)`, `sum(amounts)`.

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
