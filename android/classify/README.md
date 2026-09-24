# :classify

Pure-Kotlin (JVM) message classification pipeline for Dak. Package `app.dak.classify`. No
`android.*` imports; depends only on `:core-model`, `kotlinx-serialization-json` and
`kotlinx-coroutines-core`.

## Sender identity

- `SenderId.parseDltHeader(address): DltHeader?` — parses an Indian DLT header
  (`VM-HDFCBK`, `AX-HDFCBK-S`, `VK-AMAZON-P`, numeric promotional `VM-612345`) into
  `DltHeader(prefix, entityHeader, trafficType)`. The 2-letter prefix is the access provider + circle (never
  brand-identifying). `TrafficType` is `PROMOTIONAL` (`-P`), `SERVICE_IMPLICIT` (`-S`), `TRANSACTIONAL` (`-T`) or
  `GOVERNMENT` (`-G`). Numeric entity headers must be exactly 6 digits (other digit runs behind a dash are not
  headers); `DltHeader.route` is the suffix, or `PROMOTIONAL` for a numeric header without one.
- `SenderId.classify(address): SenderKind` — `DLT_HEADER`, `SHORT_CODE`, `PHONE_NUMBER` or
  `ALPHANUMERIC`.
- `SenderId.mergeKey(address): String` — collapses `VM-HDFCBK` / `JD-HDFCBK` / `AX-HDFCBK` to
  `"HDFCBK"`; numeric senders collapse to their last 10 digits.
- `SenderId.isIndianMobile(address): Boolean`.
- `SenderRegion(countryIso)` / `SenderRegion.of(iso)` / `SenderRegion.INDIA` / `SenderRegion.UNKNOWN` — the sender
  conventions of the SIM a message arrived on. `dltSenderIds` (India only: DLT parsing for labels/trust),
  `shortCodesSuspicious` (India only; short codes are normal bank senders elsewhere), `isLocalMobile(address)`
  (Indian 10-digit mobile in IN, any 8+ digit phone number elsewhere), `matches(regions)` for tagged bundle entries.
  Unknown region = generic behaviour, never India by assumption.

## Template bundle (signed OTA templates)

- `TemplateBundle.loadDefault()` — loads the bundled, unsigned-trusted default template resource
  (`default-templates.json`): a sender table (brand names for display and the fake-credit identity checks) and
  **generic** regex rules keyed on the structure and vocabulary of message types, never on a brand, bank or courier
  name (`GenericRulesTest` enforces this). Families: OTP; scams (KYC/account-block threats, UPI collect / PIN bait,
  parcel fees, lottery, job/task, investment, advance fees, electricity disconnection, reward-point phishing, and,
  from unknown Indian mobiles only, bank-alert and "sent by mistake" text); promotions dressed as transactions
  (`T&C`, "order now", "% off", "use code"); carrier call alerts (personal); money movement; logistics
  (AWB/tracking/consignment/shipment/out for delivery/delivered/reattempt), orders, invoices/bills/due dates,
  bookings/tickets/PNR/appointments, service updates (request registered, card dispatched, KYC updated, new
  login) and account notices (plan/validity expiry, data usage, recharge done); plain promotions. Order/delivery/
  invoice updates stay transactions whatever "rate us" / feedback link follows. Investments (bundle 4, see
  `InvestmentLabels`): demat security alerts (shares / securities debited, pledge, e-DIS) are transactions labelled
  `investment-alert` (routed to Alerts), fund / broker updates (folio, NAV, IDCW, units allotted / redeemed, CAS,
  contract note, qty @ price, BO / DP ID) transactions labelled `investment-update` (routed to General), NFO / "invest
  now" / "returns up to" offers promotions; a debit / credit on a masked "A/c XX1234" (`txn-account-movement`) is an
  unlabelled transaction first, so a bank's SIP debit stays a loud alert (`InvestmentClassificationTest`). Bump the
  bundle `version` when rules change so stored messages are re-classified.
- `TemplateRule.senderScope` (`ANY` default, `BUSINESS`, `PRIVATE_NUMBER`): `BUSINESS` rules (money, order,
  delivery, bill, booking, service wording that people also write to each other) are skipped for saved contacts and,
  in India, for private numbers; `PRIVATE_NUMBER` rules apply only to unknown private numbers in India (where
  businesses cannot send from one).
- `TemplateBundle.parse(json, verifier: BundleVerifier): TemplateBundle?` — parses and verifies an
  OTA `SignedTemplateBundle`; returns null on any signature failure (fail closed).
- `TemplateBundle.parseUnsigned(payloadJson)` — for tests/tools only.
- `bundle.brandKey(mergeKey): String?` — brand-level fold key: the first header the bundle lists for the same
  brand (`HDFC` -> `HDFCBK`); used by `:core-index` to fold one brand's headers into one conversation.
- `bundle.sender(mergeKey): SenderEntry?`, `bundle.rulesFor(mergeKey): List<TemplateRule>`
  (sorted by descending priority; rules with no `senderHeaders` apply to everyone).
- Optional `regions` (ISO alpha-2 list) on `SenderEntry` and `TemplateRule`; absent/empty = global, so older
  bundles stay valid. The bundled Indian senders and the UPI/IMPS rule carry `["IN"]`. `bundle.sender(mergeKey,
  region)` ignores entries of other regions; `bundle.rulesFor(mergeKey, region)` drops other regions' rules and, at
  equal priority, tries this region's rules before generic ones. An unknown region filters nothing.
- `BundleVerifier` is a `fun interface`; `Ed25519BundleVerifier(rawPublicKeyBytes)` verifies with
  `java.security` Ed25519 (JDK 15+/Android 33+), failing closed if the runtime lacks the algorithm.
  `RejectAllVerifier` is the safe default with no configured key.

## OTP

- `OtpExtractor.extract(body): OtpInfo?` (core-model `OtpInfo`) — finds 4-8 digit or labelled
  alphanumeric codes (English + Hindi phrasing, including "OTP for txn of Rs X at SHOP on card XX1234 is 773201" and
  "Use 5521 as your one time password"), a trailing 11-char SMS Retriever hash, and a
  trailing WebOTP `@domain #code` line. Also "G-123456 is your Google verification code" (a brand between "your"
  and the keyword) and Arabic phrasing (رمز التحقق ...); tested with US/UK/EU/UAE/SG-style messages. Ignores amounts, dates, phone numbers and masked account
  tails (`XX1234`). Non-ASCII decimal digits (Devanagari, Bengali, Arabic-Indic, full-width, ...)
  are normalized to ASCII before matching, so `OtpInfo.code` is always ASCII digits (needed for
  copy/autofill) regardless of the script the OTP arrived in.
- `AppSignatureHash.compute(packageName, signatureBytes): String` /
  `compute(packageName, hexSignature): String` — Google's `AppSignatureHelper` algorithm exactly.
- `ConsumedOtpMatcher(hashesByPackage, browserPackages).consumerOf(otp): String?` — resolves the
  package that consumed an OTP via retriever hash, or the first installed browser for WebOTP.
- `DuplicateOtpCollapser(windowMinutes).isDuplicate(address, body, now, recent): Boolean` /
  `findDuplicate(...)` — same merge key + identical body within the window.

## Masking (for the opt-in cloud classifier)

- `Masker.mask(body): String` — replaces URLs, emails, card numbers, amounts, remaining digits and
  probable names (after "Dear"/"Hi"/"to"/"from") with `<URL>`, `<EMAIL>`, `<NUM>`, `<AMT>`,
  `<NAME>`. Guarantees no digit ever survives.

## On-device model

- `Tokenizer.tokenize(text): List<String>` — Unicode letter/digit/combining-mark tokenizer;
  script-agnostic (Latin, Devanagari, Hinglish). ZWJ/ZWNJ (`U+200D`/`U+200C`) inside a word never
  split the token (they are legitimate inside Indic/Arabic conjuncts) but are dropped from the
  token text itself, so the same word tokenizes identically whether or not the source used one.
- `MessageModel` — `fun interface { fun predict(text): Map<Category, Float> }`, the seam a
  TFLite/ONNX model can implement later.
- `NaiveBayesModel.loadDefault(): NaiveBayesModel` — small multinomial Naive Bayes with Laplace
  smoothing, trained from the hand-written seed corpus in
  `src/test/kotlin/app/dak/classify/gen/SeedCorpus.kt`. Re-run
  `GenerateModelWeightsTest` after editing the corpus to regenerate
  `src/main/resources/app/dak/classify/model-weights.json`.

## Cloud classifier seam

- `CloudClassifier` — `suspend fun classify(senderHeader, maskedBody): CloudVerdict?`.
  `NoCloudClassifier` (default) always declines. Callers must only ever pass `Masker`-masked text.

## Pipeline

```kotlin
val pipeline = ClassifierPipeline(
    templates = TemplateBundle.loadDefault(),
    model = NaiveBayesModel.loadDefault(),
    cloud = NoCloudClassifier,             // or an opt-in CloudClassifier
    contactLookup = { address -> ... },    // saved-contact lookup
    threshold = 0.55f,
    regionFor = { subId -> SenderRegion.of(simCountry(subId)) }, // default: SenderRegion.UNKNOWN
)
val result: app.dak.core.model.Classification = pipeline.classify(address, body, subId)
```

Stage 1 (deterministic templates) short-circuits stages 2/3 once its confidence clears
`threshold`; otherwise stage 2 (`MessageModel`) runs, with a bias towards `PERSONAL` for local mobile numbers
(`SenderRegion.isLocalMobile`). A saved contact's message that no rule flagged is `PERSONAL`. An OTP verdict needs a
code (`OtpExtractor`, or a standalone 4-8 digit number): "the OTP will be shared by the agent" is not an OTP. DLT
routes (India only) bound what a message can be: a template spam verdict on a registered route (`-P/-S/-T/-G`) stands
only with a risky link (look-alike, suspicious TLD, shortener, IDN, userinfo), otherwise it becomes a promotion on
`-P` and a transaction elsewhere; a transaction-looking message on `-P` (or a numeric header) is a promotion; with no
rule hit `-P` means promotion and `-T`/`-G` transaction (a sender-table category hint is never used). In the model
stage `-T`/`-G` damp promotion and spam, `-S` spam, `-P` transaction and OTP, any DLT header personal, and (India) a
private number transaction and promotion. An unknown private number sending a risky link is spam
(`fraud-risk`). `dlt-*` traffic labels are only added for an Indian SIM, and region-tagged template entries follow `regionFor(subId)`. If the result is still below `threshold`, stage 3
asks the opt-in `CloudClassifier` for a masked-text verdict; below threshold even after that, the
category is `Category.UNKNOWN` rather than a guess. `canonicalSender` is filled from the template
bundle's brand table; `otp` is filled via `OtpExtractor` whenever the final category is `OTP`.
Phone-number senders (and, in India only, short codes) that are not contacts and send a link (with or without a
scheme) get the `"unknown-sender-link"` label regardless of category.

## Fake credit alerts (`app.dak.classify.scam`)

Threat model: `docs/security/fake-credit-scams.md`. Pure on-device rules, thread-safe, bounded to the first 4,000
characters.

- `FakeCreditDetector(templates).evaluate(address, body, hint: TransactionHint? = null, knownAccounts:
  Set<AccountHint> = emptySet(), isSavedContact = false, recentMessages: List<RecentMessage> = emptyList(),
  dateMillis, region: String? = "IN"): ScamVerdict` returns `ScamVerdict(level: NONE|SUSPICIOUS|LIKELY_SCAM,
  reasons: List<ScamReason>, claimedInstitution: String?, score)`.
  - Verified bank or wallet DLT headers are never flagged.
  - India's DLT rules apply only for region `IN`.
  - A saved contact halves the score.
- `isCandidate(address, body, region)` and `needsRecentMessages(address, body, region)` are cheap pre-checks. Use
  them to skip loading contacts, accounts or history for most messages.
- `ScamLabels` stores a verdict in a label set and reads it back:
  - `scam:likely-fake-credit` or `scam:suspicious`
  - `scam-reason:<code>` and `scam-claims:<bank>`
  - `scam:user-dismissed`
  - `excludedFromLedger(labels)`, `isFlaggedCredit(labels)` and `likePattern(label)` (for SQL `LIKE` over the
    JSON label column).
- `TransactionHint(direction: HintDirection?, amountMinor, last4)` keeps `:classify` independent of `:finance`.

## Links and lookalikes

- `LinkExtractor.extract(body): List<ExtractedLink>`: finds `http(s)://`, bare `www.` and scheme-less
  `name.tld[/path]` links (`bit.ly/x`, `fb.example.in/ffb63`), never `javascript:`, `intent:`, `content:`, `file:`,
  `tel:`…, in the first `MAX_SCAN_CHARS` (20k) characters, in body order. A link stops at bidi and zero-width
  characters. Scheme-less links come from a linear scan (no regex): ASCII labels ending in a known TLD (English-word
  TLDs such as `.me`/`.to`/`.id` need a path, a third label or a hyphen/digit), starting at a word boundary and not
  inside an e-mail address or another token, no Title-case label after a dot ("Thank you.In case…"), so "Rs.500",
  "A/c.No", "1.5GB", "B.Com", "Pvt.Ltd" and "name@mail.com" are never links. `ExtractedLink(raw, host, asciiHost,
  hasUserInfo)` + `isIdn`, `hasScheme` and `url` (what to open: `https://` added to `www.` and scheme-less links):
  `host` is the host as written with userinfo and port stripped, `asciiHost` the host a browser resolves (UTS #46
  nontransitional ToASCII with the WHATWG URL flags; null when a browser would refuse it) and `unicodeHost` its
  ToUnicode form. Show `HostDisplay.displayHost(asciiHost, readerScripts)` in warnings.
- `LookalikeDomainChecker().check(link): LinkVerdict` — `OFFICIAL`, `SHORTENED`, `LOOKALIKE`,
  `SUSPICIOUS_TLD` or `UNKNOWN`, against a bundled list of official Indian bank/government/courier
  domains, a URL-shortener list, and a suspicious-TLD list. Lookalikes are flagged by brand name
  appearing in a subdomain label (`hdfc-bank-kyc.xyz`) or by edit distance (1-2) against an
  official domain's label. IDN hosts (and hosts that fail UTS #46) are reduced to their UTS #39 skeleton and
  compared with the official domains' skeletons to name the brand they imitate, and are always `LOOKALIKE` unless
  official. Links with userinfo (`https://bank.com@evil.xyz`) are `LOOKALIKE`.
- `ClassifierPipeline.MAX_CLASSIFY_CHARS` (4000): only the head of a body is classified. This bounds the cost of
  every regex run on attacker text. `SecurityTest` is the ReDoS harness for all body regexes.

## Text safety (`app.dak.classify.unicode`; docs/standards-compliance.md §14)

- `UntrustedText` (UAX #9): `isolate` (FSI…PDI), `isolateLtr` (LRI…PDI), `isolateName`, `sanitizeName`,
  `neutralize` and `neutralizeKeepingOffsets` (explicit embeddings/overrides/isolates removed or replaced by U+2060,
  so text cannot close an isolate early or reverse what follows). The app's `BidiText` delegates here.
- `Punycode` (RFC 3492) and `Uts46` (`toAscii` / `toUnicode`, nontransitional; `BROWSER` and `STRICT` option sets):
  mapping table, NFC, Punycode, validity criteria, CONTEXTJ and the RFC 5893 Bidi rule. Passes Unicode's IdnaTestV2
  (the Unicode-13-compatible subset in the test resources).
- `Confusables.skeleton` / `looseSkeleton` / `caseFoldedSkeleton` / `isAsciiLookalike` (UTS #39 §4),
  `ScriptCheck.restrictionLevel` / `hasMixedNumbers` / `hasMixedScriptWord` / `scriptsForLanguages` (UTS #39 §5),
  `HostDisplay.displayHost(asciiHost, readerScripts)` (Unicode only for single-script labels the reader reads that do
  not pass for Latin; punycode otherwise).
- `SenderNameCheck.check(name)`: mixed-script and ASCII-look-alike sender names (`НDFCBK` with a Cyrillic Н). Feeds
  `FakeCreditDetector` (`LOOKALIKE_SENDER`, `MIXED_SCRIPT_SENDER`) and the pipeline (such senders' links are treated
  like an unknown number's).
- Data: `src/main/resources/app/dak/classify/unicode/` (`idna-mapping.txt`, `idna-context.txt`, `confusables.txt`),
  generated from Unicode 17.0.0 files by `tools/gen_unicode_tables.py`; each file's header records its sources and
  their SHA-256. Unicode data is under the Unicode License v3 (https://www.unicode.org/license.txt). Tables load
  lazily, so ASCII-only hosts and senders never parse them.

## Smart entities (`app.dak.classify.entities`)

```kotlin
EntityExtractor.extract(body: String, regionIso: String?, hints: List<EntityHint> = emptyList(), otpCode: String? = null): List<EntitySpan>
data class EntitySpan(type: EntityType, start: Int, end: Int /* exclusive */, text: String, value: String, courier: String?)
enum class EntityType { OTP, URL, EMAIL, MASKED_ACCOUNT, AMOUNT, UPI_ID, PNR, TRACKING, REFERENCE, PHONE }  // = overlap priority
Couriers.find(text): String?   // "bluedart", "delhivery", "indiapost", ...
```

Typed, non-overlapping spans instead of "every digit run is a phone": OTP (OtpExtractor, or the index's code),
URL (LinkExtractor: http(s), www and scheme-less links), EMAIL, MASKED_ACCOUNT (`XX1234`, `****1234`, "A/c ending 1234"), AMOUNT
(pass `:finance` `MoneyParser` spans as `EntityHint`s; a minimal rupee matcher runs otherwise), UPI_ID (known PSP
handle or "UPI/VPA" just before; a dot after `@` makes it an email), PNR, TRACKING (+ courier), REFERENCE
(Ref/Txn/UTR/RRN/Order ID + an id with a digit), PHONE (libphonenumber `findNumbers`, `Leniency.VALID`, for the
SIM's region; `null` region → only `+`-prefixed numbers; numbers right after account/ref/customer-id words are
skipped). `value` is canonical: E.164 phone, ASCII OTP, lower-case UPI/email, mask digits. First 4000 chars only;
patterns (`EntityPatterns`) are covered by the ReDoS harness in `SecurityTest`.

## Performance (`app.dak.classify.text`, see docs/performance.md)

- `AhoCorasick(patterns)` — one-pass multi-literal search (`matches(text): BitSet`, `containsAny(text)`), folding
  case the way a Unicode case-insensitive regex does (`AhoCorasick.fold`).
- `RequiredLiterals.of(pattern): Set<String>?` — conservative "one of these literals is in every match" set for a
  Java regex, or null (unsupported construct / no safe literal: always run the regex).
- `GatedRegex(pattern, options)` — drop-in for `Regex` (`containsMatchIn`, `find`, `findAll`, `mayMatch`) that only
  runs the regex when a required literal occurs. Used by `FakeCreditDetector`, `OtpExtractor`, `LinkExtractor`,
  `LinkPresence`.
- `KeywordPrefilter(patterns)` — one scan for a whole list of patterns (`scan(text)`, `mayMatch(i, hits)`). Used for
  the template rules (`ClassifierPipeline(prefilter = true)`, the default) and `BankNames`.
- `ClassifierPipeline(..., cacheSize = 0, prefilter = true)`: `cacheSize > 0` enables the exact template-hash result
  cache (`TemplateCache`; `SUGGESTED_CACHE_SIZE` = 2048). Off by default: see docs/performance.md for why.
- `NaiveBayesModel.knows(token)`; predictions use precomputed log tables (bit-identical to the plain loop).
- `LinkPresence.containsLink(body)` — the index's `has:link` test (moved here from `:core-index` so the JVM
  benchmark covers it; `app.dak.index.enrich.LinkDetector` delegates to it). Same notion of a link as
  `LinkExtractor` (shares its scheme-less scanner), over the whole body.
- Every speed-up is proven result-identical by `PipelineEquivalenceTest` and `text/*Test` over the 50k-message
  synthetic corpus (`bench/SyntheticCorpus`). The throughput benchmark is opt-in:
  `scripts/jvm-test.sh :classify:test -Pdak.bench=true --tests '*EnrichmentBenchmarkTest*'`
  (`-Pdak.bench.jfr=<file>` also records a JFR CPU profile). Plain `test` excludes it.

## Testing

`DAK_JVM_HARNESS_DIR=<unique dir> GRADLE=/opt/gradle-8.14.3/bin/gradle scripts/jvm-test.sh :classify:test`
(run from `android/`). `corpus/LabelledCorpusTest` checks that every message of a ~250-message hand-labelled corpus
(`corpus/LabelledCorpus`: fictional brands, every category and DLT route, English/Hinglish/Hindi, the hard cases)
lands in its category; `corpus/GenericRulesTest` checks that no rule names a brand and that a courier or merchant
name never changes the category. After editing `SeedCorpus`, run the tests twice: the first run regenerates
`model-weights.json`, the second tests with it. All public pieces have unit tests, including a `ClassifierPipelineTest`
accuracy check (>= 90%) over a small corpus of real-looking Indian SMS samples (HDFC debit, ICICI
credit-card spend, SBI UPI credit, Paytm, Amazon OTP, IRCTC booking, Jio recharge promo, KYC spam
with a link, Hinglish personal chat).
