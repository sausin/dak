# :classify

Pure-Kotlin (JVM) message classification pipeline for Dak. Package `app.dak.classify`. No
`android.*` imports; depends only on `:core-model`, `kotlinx-serialization-json` and
`kotlinx-coroutines-core`.

## Sender identity

- `SenderId.parseDltHeader(address): DltHeader?` — parses an Indian DLT header
  (`VM-HDFCBK`, `AX-HDFCBK-S`, `VK-AMAZON-P`) into `DltHeader(prefix, entityHeader, trafficType)`.
  `TrafficType` is `PROMOTIONAL` (`-P`), `SERVICE_IMPLICIT` (`-S`), `TRANSACTIONAL` (`-T`) or
  `GOVERNMENT` (`-G`).
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
  (`default-templates.json`), covering the major Indian banks, wallets/UPI, delivery, telecom,
  travel and OTP/spam/promo regex rules. Courier, order-status and invoice/bill wording is a transaction whatever
  "rate us" / feedback link follows it; parcel "on hold, pay a fee / update your address" wording is spam (and
  outranks the courier rules); carrier call alerts ("98XXXXX210 is now available to take calls", missed-call
  alerts) are personal. Bump the bundle `version` when rules change so stored messages are re-classified.
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
  alphanumeric codes (English + Hindi phrasing), a trailing 11-char SMS Retriever hash, and a
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
`threshold`; otherwise stage 2 (`MessageModel`) runs, with a bias towards `PERSONAL` for saved
contacts or local mobile numbers (`SenderRegion.isLocalMobile`). On an Indian DLT header the model's promotion and
spam scores are damped for `-T` routes (no marketing allowed), and its spam score for `-S` routes (registered service
templates). `dlt-*` traffic labels are only added for an Indian SIM, and region-tagged template entries follow `regionFor(subId)`. If the result is still below `threshold`, stage 3
asks the opt-in `CloudClassifier` for a masked-text verdict; below threshold even after that, the
category is `Category.UNKNOWN` rather than a guess. `canonicalSender` is filled from the template
bundle's brand table; `otp` is filled via `OtpExtractor` whenever the final category is `OTP`.
Phone-number senders (and, in India only, short codes) that are not contacts and send a link get the
`"unknown-sender-link"` label regardless of category.

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

- `LinkExtractor.extract(body): List<ExtractedLink>`: finds `http(s)://` and bare `www.` URLs only (never
  `javascript:`, `intent:`, `content:`, `file:`, `tel:`…) in the first `MAX_SCAN_CHARS` (20k) characters. A link
  stops at bidi and zero-width characters. `ExtractedLink(raw, host, asciiHost, hasUserInfo)` + `isIdn`: `host`
  is the Unicode host with userinfo and port stripped, and `asciiHost` its punycode form (show that one in
  warnings).
- `LookalikeDomainChecker().check(link): LinkVerdict` — `OFFICIAL`, `SHORTENED`, `LOOKALIKE`,
  `SUSPICIOUS_TLD` or `UNKNOWN`, against a bundled list of official Indian bank/government/courier
  domains, a URL-shortener list, and a suspicious-TLD list. Lookalikes are flagged by brand name
  appearing in a subdomain label (`hdfc-bank-kyc.xyz`) or by edit distance (1-2) against an
  official domain's label. IDN hosts are folded to a Latin skeleton (Cyrillic/Greek confusables) to name the
  brand they imitate, and are always `LOOKALIKE` unless official. Links with userinfo (`https://bank.com@evil.xyz`)
  are `LOOKALIKE`.
- `ClassifierPipeline.MAX_CLASSIFY_CHARS` (4000): only the head of a body is classified. This bounds the cost of
  every regex run on attacker text. `SecurityTest` is the ReDoS harness for all body regexes.

## Smart entities (`app.dak.classify.entities`)

```kotlin
EntityExtractor.extract(body: String, regionIso: String?, hints: List<EntityHint> = emptyList(), otpCode: String? = null): List<EntitySpan>
data class EntitySpan(type: EntityType, start: Int, end: Int /* exclusive */, text: String, value: String, courier: String?)
enum class EntityType { OTP, URL, EMAIL, MASKED_ACCOUNT, AMOUNT, UPI_ID, PNR, TRACKING, REFERENCE, PHONE }  // = overlap priority
Couriers.find(text): String?   // "bluedart", "delhivery", "indiapost", ...
```

Typed, non-overlapping spans instead of "every digit run is a phone": OTP (OtpExtractor, or the index's code),
URL (LinkExtractor: http(s)/www only), EMAIL, MASKED_ACCOUNT (`XX1234`, `****1234`, "A/c ending 1234"), AMOUNT
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
  benchmark covers it; `app.dak.index.enrich.LinkDetector` delegates to it).
- Every speed-up is proven result-identical by `PipelineEquivalenceTest` and `text/*Test` over the 50k-message
  synthetic corpus (`bench/SyntheticCorpus`). The throughput benchmark is opt-in:
  `scripts/jvm-test.sh :classify:test -Pdak.bench=true --tests '*EnrichmentBenchmarkTest*'`
  (`-Pdak.bench.jfr=<file>` also records a JFR CPU profile). Plain `test` excludes it.

## Testing

`DAK_JVM_HARNESS_DIR=<unique dir> GRADLE=/opt/gradle-8.14.3/bin/gradle scripts/jvm-test.sh :classify:test`
(run from `android/`). All public pieces have unit tests, including a `ClassifierPipelineTest`
accuracy check (>= 90%) over a small corpus of real-looking Indian SMS samples (HDFC debit, ICICI
credit-card spend, SBI UPI credit, Paytm, Amazon OTP, IRCTC booking, Jio recharge promo, KYC spam
with a link, Hinglish personal chat).
