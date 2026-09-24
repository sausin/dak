# Adversarial SMS corpus

Hostile and tricky text messages that Dak must handle correctly, kept as plain text so anyone can add one, not
only Kotlin developers. There are two kinds:

1. **Scams and their benign look-alikes**, one file per region: fake credit alerts, "sent by mistake, please return"
   follow-ups, KYC and PAN scares, parcel fees, toll and fine smishing, job and loan scams. Each scam file also
   holds the real messages they imitate (bank alerts from verified senders, genuine OTPs, delivery updates). Those
   must **not** be flagged. A false alarm on a real bank alert costs as much trust as a missed scam.
2. **"Pwn" payloads** in `pwn/`, aimed at the app itself: bidi overrides, zero-width characters, homograph and
   punycode hosts, userinfo and backslash URLs, `intent:` and `javascript:` schemes, regex-backtracking (ReDoS)
   bait written against Dak's own regexes, huge bodies, overflowing amounts, format strings, SQL and FTS
   injection, and spoofed senders or "Dak system" text.

A JVM test runs every line through Dak's defences and checks the expectations below:
`android/classify/src/test/kotlin/app/dak/classify/adversarial/AdversarialCorpusTest.kt`.

## Files

| File | Region (`#! region=`) | What is in it |
| --- | --- | --- |
| `global.tsv` | `none` (region unknown) | Scams and benign messages that look the same in every country |
| `in.tsv` | `IN` | India, the richest file. DLT headers (`VM-HDFCBK-S`), UPI, Hinglish and Hindi |
| `us.tsv` | `US` | Toll, USPS, IRS, bank and Zelle smishing; short-code bank alerts |
| `gb.tsv` | `GB` | Royal Mail, Evri, HMRC, DVLA, "Hi Mum"; UK banks |
| `ae.tsv` | `AE` | UAE, in English and Arabic: bank card suspension, Emirates Post, Salik and RTA fines, ICP |
| `sg.tsv` | `SG` | Singapore: DBS, OCBC, SingPost, Singpass, government impersonation |
| `eu.tsv` | `DE` (lines may override) | DHL, La Poste, PostNL, Correos, ANTAI fines; German, French, Dutch, Spanish, Italian |
| `pwn/unicode.tsv` | `IN` | Bidi controls, zero-width, homographs, zalgo, emoji ZWJ, RTL scripts, digits in other scripts |
| `pwn/links.tsv` | `IN` | Userinfo, backslash, punycode, IP hosts, non-http schemes, link floods |
| `pwn/redos.tsv` | `IN` | Inputs built to make specific regexes backtrack |
| `pwn/injection.tsv` | `IN` | Format strings, SQL and FTS syntax, HTML and markdown, template placeholders, fake Dak text, spoofed senders |
| `pwn/size.tsv` | `IN` | Tiny, huge and overflowing messages and amounts |

The region is the ISO 3166 country of the SIM that receives the message. It matters: India's DLT rules (a
registered `XX-BRAND-S` header is trusted, and a bank alert from a mobile number is not) apply only to `IN`.
Elsewhere only generic signals are used. A single line can override its file's region with a `region:XX` tag.

## Format

Each file is UTF-8 text with **one message per line** and five **tab-separated** columns:

```
#! region=IN
# id	sender	expect	tags	body
in-kyc-03	+919876512320	scam,link-warning	kyc,hinglish	Aapka SBI account aaj block ho jayega. KYC update karein: https://sbi-kyc-update.xyz
```

- A line starting with `#` is a comment. Blank lines are ignored. Group related lines under a comment header.
- The first non-comment line must be the region directive `#! region=XX`, or `#! region=none` for "unknown".
- Use real tab characters between the columns. Most editors have a "show whitespace" mode. Inside a body, write
  a tab as `\t`.

| Column | Meaning |
| --- | --- |
| `id` | Unique across the corpus. It starts with the file name (`in-`, `gb-`, `unicode-`), then lower-case words and digits joined by `-`. Never reuse or renumber an id: test reports and bug reports refer to it. |
| `sender` | The address as the phone reports it: `VM-HDFCBK-S`, `+919876512345`, `56070`, `Chase`. Escapes work here too. Write `<empty>` for an empty sender. |
| `expect` | Comma-separated expectations; see below. At least one. |
| `tags` | Comma-separated lower-case tags, or `-` for none. Free-form tags (`kyc`, `hinglish`, `toll`) help with searching. The special tags are listed below. |
| `body` | The message text, with escapes and generators (below). |

### Expectations

| Expectation | Holds when |
| --- | --- |
| `scam` | Dak warns about the message: the fake-credit detector flags it (either level), or the classifier files it as spam, or it carries the `fraud-risk` label. |
| `not-scam` | None of the above. Use this on every benign look-alike. |
| `likely-scam` | The fake-credit detector says `LIKELY_SCAM` (strong warning; the amount stays out of the passbook). |
| `suspicious` | The fake-credit detector says exactly `SUSPICIOUS` (gentle warning). |
| `fake-credit` | The fake-credit detector flags it at either level. |
| `no-fake-credit` | The fake-credit detector does not flag it. |
| `spam` / `not-spam` | The classifier's category is (or is not) spam. |
| `category:<name>` | The classifier's category is `otp`, `transaction`, `promotion`, `personal`, `spam` or `unknown`. |
| `reason:<code>` | The detector gives this reason code (`phone-credit-alert`, `return-request`, `pin-to-receive`, `collect-request`, `follow-up`, `lookalike-sender`, ... see `ScamReason`). |
| `label:<label>` / `no-label:<label>` | The message's labels include (or do not include) this one, e.g. `label:unknown-sender-link`, `label:scam:likely-fake-credit`. |
| `link-warning` | Tapping a link would warn: some link is a look-alike, has a suspicious TLD, is shortened or uses userinfo, or the message has the `unknown-sender-link` label. |
| `no-link-warning` | None of that. |
| `lookalike` | Some link is flagged as a look-alike of a known brand (homograph, typo-squat, brand inside another domain, userinfo). |
| `links:<n>` | Exactly `n` links are extracted, which means `n` tappable links. Use `links:0` to say "this must not become a link" (`javascript:`, `intent:`, `tel:`...). |
| `link-host:<host>` | Some extracted link resolves to this host (the ASCII/punycode form a browser opens), e.g. `link-host:evil.example` for `https://bank.com@evil.example/`. |
| `otp:<code>` | The OTP extractor returns exactly this code (always ASCII, even when the SMS uses Devanagari or Arabic digits). |
| `no-otp` | The OTP extractor finds no code. Use it for numbers that only look like OTPs. |
| `txn:credit` / `txn:debit` | The transaction parser reads a credit (or a debit). |
| `amount:<value>` | The transaction parser reads this amount in major units, e.g. `amount:2450.00` or `amount:15000`. |
| `no-txn` | The transaction parser returns nothing: not a completed transaction. |
| `sender-spoof` | The sender name is flagged as a mixed-script or look-alike imitation (UTS #39). |
| `no-crash` | Nothing beyond the checks below, which run on **every** line anyway. Use it for pure robustness payloads. |

Every line, whatever it expects, must also pass these checks. Nothing may throw (not even a `StackOverflowError`),
and the whole message must be handled within the time budget (500 ms by default), which is how backtracking
regexes are caught. A set of invariants must also hold:

- Links never carry a non-http scheme, whitespace or invisible or bidi characters.
- A userinfo, IDN or browser-refused host is always flagged.
- Entity spans stay inside the body and do not split surrogate pairs.
- Isolated text cannot escape its isolate.
- Sender names lose bidi and invisible characters.
- The cloud masker never throws or leaks a digit.
- Search MATCH strings stay in FTS4's safe grammar.
- Parsed amounts are positive and below 10^13.
- `isCandidate` agrees with `evaluate`.

### Special tags

| Tag | Meaning |
| --- | --- |
| `known-gap` | A **real** scam (or a real bug) that today's defences miss. The test prints the line's failures instead of failing. Once the line passes, the test fails until you remove the tag. Never use this to hide a wrong expectation. |
| `contact` | The sender is a saved contact, for the whole run, so use a number no other line uses. |
| `after:<id>` | This message arrives an hour after the earlier line `<id>` in the same file. That line is passed as recent history, to test "sent by mistake, please return it" follow-ups. |
| `region:XX` | Evaluate this line as if received on a SIM from `XX` (or `region:none`). |

### Escapes

Invisible and control characters must be written as escapes, so that a reviewer can see them in a pull request.
The parser rejects raw control characters (other than the column tabs), format characters (bidi controls,
zero-width characters, BOM) and line or paragraph separators. Escaping look-alike letters (Cyrillic, Greek,
fullwidth, mathematical) is also recommended.

| Escape | Character |
| --- | --- |
| `\n` `\r` `\t` | newline, carriage return, tab |
| `\0` | NUL (U+0000) |
| `\\` | a backslash |
| `\{` | a literal `{` where a generator would otherwise start |
| `\uXXXX` | the UTF-16 code unit `XXXX` (exactly 4 hex digits; a lone surrogate such as `\uD800` is allowed on purpose) |
| `\u{X...}` | any code point, 1-6 hex digits, e.g. `\u{1F600}` |

For example, write RLO as `\u202E`, a zero-width space as `\u200B` and a Cyrillic "a" as `\u0430`, or type the
character itself where it is visible. Any other backslash sequence is an error. Braces are literal (`{firstName}`
and `{0}` need no escaping). The one exception is a generator.

### Generators

A huge payload is written as a generator, not as a giant line:

```
{repeat:"<text>":<count>}
```

This repeats `<text>` `<count>` times. Inside the quotes, the escapes above work, and `\"` is a quote. For example,
`{repeat:"otp ":1000}` or `Rs 500 credited{repeat:"\u0301":5000}`. A decoded field may be at most 2,000,000
characters long.

## Running the tests

From `android/`:

```
./gradlew :classify:test --tests '*Adversarial*'
# without the Android SDK (pure-JVM modules only):
scripts/jvm-test.sh :classify:test --tests '*Adversarial*'
# a different time budget per message:
./gradlew :classify:test --tests '*Adversarial*' -Ddak.adversarial.budgetMs=1000
```

The report lists every failing line as `file:line [id]` with what was observed. Known gaps are printed as well.

## Safety rules

- **No real personal data.** Never paste a message as you received it. Change every name, account number, UPI ID,
  phone number and reference number. Use made-up numbers: Indian `98765xxxxx`, US `555-01xx`, UK `07700 900xxx`
  (Ofcom's drama range).
- **No live phishing URLs.** Defang real ones by replacing the domain with a made-up one of the same shape. Where the
  top-level domain does not matter, use a reserved one: `.example`, `.invalid` or `.test`. Where the TLD is the
  signal (`.xyz`, `.top`, `.info`), invent an obviously fake name.
- Real brand names (banks, couriers, tax offices) are fine, because the scams imitate them. Do not copy logos or
  long verbatim templates.
- Nothing here is ever fetched or opened. The tests only parse text.

## Pull request checklist

- [ ] One message per line, 5 tab-separated columns, and the id starts with the file name and is new.
- [ ] Invisible characters are written as escapes, and huge payloads as generators.
- [ ] No real names, numbers, account digits or live URLs (see the safety rules).
- [ ] Each new scam has a benign look-alike next to it, if a real one exists (the genuine bank alert, OTP or
      delivery update).
- [ ] `known-gap` is only on lines that really are missed today, and the PR says why.
- [ ] `./gradlew :classify:test --tests '*Adversarial*'` passes.
