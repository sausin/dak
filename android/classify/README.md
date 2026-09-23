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

## Template bundle (signed OTA templates)

- `TemplateBundle.loadDefault()` — loads the bundled, unsigned-trusted default template resource
  (`default-templates.json`), covering the major Indian banks, wallets/UPI, delivery, telecom,
  travel and OTP/spam/promo regex rules.
- `TemplateBundle.parse(json, verifier: BundleVerifier): TemplateBundle?` — parses and verifies an
  OTA `SignedTemplateBundle`; returns null on any signature failure (fail closed).
- `TemplateBundle.parseUnsigned(payloadJson)` — for tests/tools only.
- `bundle.sender(mergeKey): SenderEntry?`, `bundle.rulesFor(mergeKey): List<TemplateRule>`
  (sorted by descending priority; rules with no `senderHeaders` apply to everyone).
- `BundleVerifier` is a `fun interface`; `Ed25519BundleVerifier(rawPublicKeyBytes)` verifies with
  `java.security` Ed25519 (JDK 15+/Android 33+), failing closed if the runtime lacks the algorithm.
  `RejectAllVerifier` is the safe default with no configured key.

## OTP

- `OtpExtractor.extract(body): OtpInfo?` (core-model `OtpInfo`) — finds 4-8 digit or labelled
  alphanumeric codes (English + Hindi phrasing), a trailing 11-char SMS Retriever hash, and a
  trailing WebOTP `@domain #code` line. Ignores amounts, dates, phone numbers and masked account
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
)
val result: app.dak.core.model.Classification = pipeline.classify(address, body, subId)
```

Stage 1 (deterministic templates) short-circuits stages 2/3 once its confidence clears
`threshold`; otherwise stage 2 (`MessageModel`) runs, with a bias towards `PERSONAL` for saved
contacts or plain 10-digit Indian mobile senders. If the result is still below `threshold`, stage 3
asks the opt-in `CloudClassifier` for a masked-text verdict; below threshold even after that, the
category is `Category.UNKNOWN` rather than a guess. `canonicalSender` is filled from the template
bundle's brand table; `otp` is filled via `OtpExtractor` whenever the final category is `OTP`.
Numeric, non-contact senders whose body contains a link get the `"unknown-sender-link"` label
regardless of category.

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

## Testing

`DAK_JVM_HARNESS_DIR=<unique dir> GRADLE=/opt/gradle-8.14.3/bin/gradle scripts/jvm-test.sh :classify:test`
(run from `android/`). All public pieces have unit tests, including a `ClassifierPipelineTest`
accuracy check (>= 90%) over a small corpus of real-looking Indian SMS samples (HDFC debit, ICICI
credit-card spend, SBI UPI credit, Paytm, Amazon OTP, IRCTC booking, Jio recharge promo, KYC spam
with a link, Hinglish personal chat).
