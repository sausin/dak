# Indexing performance

Indexing (the stage-2 backfill, re-indexing after a template update, and each incoming SMS) runs every message
through the same CPU path: classification, transaction parsing, fake-credit checks, link detection and FTS text
normalisation, then database writes. This page records how that path was measured, what was changed, the numbers
before and after, and why the results are identical.

**CPU, not GPU.** A GPU was considered and rejected. The work is provider and SQLite I/O, a few dozen regexes and a
tiny Naive Bayes model (a hash lookup per word) on a few hundred characters. There is no batched arithmetic for a GPU
to do, and waking one would cost more energy than the whole CPU path. The wins below come from doing less work per
message (skipping regexes that cannot match, doing shared work once) and from using a bounded number of cores.

## Results

The benchmark is JVM 21 on the 4-core build sandbox, over 50,000 synthetic messages. The sandbox is shared with
other builds, so each figure is the median of 5 timed passes after 2 warm-up passes.

| Path (msgs/s, higher is better) | Before | After | Speed-up |
|---|---:|---:|---:|
| Classification only (`ClassifierPipeline.classify`), 1 thread | 27,300 | 113,000 | **4.1×** |
| Full enrichment path, 1 thread | 7,000 | 14,200 | **2.0×** |
| Full enrichment path as the indexer now runs it: 3 threads, batches of 500 | 7,000 (the enricher was serialised by a mutex) | 35,800 | **5.1×** |

In time per message on one thread, the full path went from about 140 µs to 70 µs. A 50k-message backfill needs about
7 s of CPU for enrichment before and 3.5 s after, spread over at most 3 cores. The provider and database I/O come on
top of that; see "Android side" below.

Where the time goes, per call, on one thread:

| Stage | Before | After |
|---|---:|---:|
| `ClassifierPipeline.classify` (templates, model, OTP) | 37 µs | 9–11 µs |
| `FakeCreditDetector.isCandidate` | 24 µs | 4.7 µs |
| `FakeCreditDetector.evaluate` | 35 µs | 9 µs |
| `OtpExtractor.extract` (only for OTP-category messages) | 35 µs | 23 µs |
| `LinkPresence.containsLink` | 4.4 µs | 2.1 µs |
| `TransactionParser.parse` (only for transaction and unknown bank-like messages; `:finance`, not changed) | 48 µs | 47 µs |
| `TextNormalizer.normalize` (FTS text; `:search`, not changed) | 3.4 µs | 3.2 µs |

`TransactionParser` is now most of the remaining CPU. See "Next" below.

## Method

- **Corpus** (`classify/src/test/.../bench/SyntheticCorpus.kt`). 50,000 deterministic, realistic, masked Indian
  messages, with a seed so every run uses the same corpus. The mix follows a heavy inbox:
  - bank, card and UPI alerts: 33%
  - OTPs, including Hindi, Hinglish, SMS Retriever and WebOTP ones: 20%
  - promotions: 12%
  - deliveries: 7%
  - bills: 3%
  - scams and fake credit alerts: 3%
  - telecom: 2%
  - personal chat, some of it sent: 20%

  Every template gets fresh amounts, dates, masked accounts, codes, references, merchants, names and short links, so
  of the 50,000 bodies, 49,764 are distinct.
- **Path** (`bench/EnrichmentPath.kt`). This is a JVM copy of the CPU part of the `:core-index` write path. It runs
  `DefaultMessageEnricher.enrich`: the pipeline, then `TransactionParser.parse` under the same
  `shouldParseTransaction` rule and region symbol map, then the fake-credit detector (`isCandidate`,
  `needsRecentMessages`, `evaluate`) for incoming messages. It also runs the per-row derivations of
  `IndexRowMapper.build`: link flag, account id and FTS text. Database and provider I/O are not in it, because they
  cannot be measured off-device.
- **Benchmark** (`bench/EnrichmentBenchmarkTest.kt`). It is opt-in and never runs in CI's `test`:

  ```
  cd android && DAK_JVM_HARNESS_DIR=<dir> GRADLE=/opt/gradle-8.14.3/bin/gradle \
    scripts/jvm-test.sh :classify:test -Pdak.bench=true --tests '*EnrichmentBenchmarkTest*'
  ```

  Results are printed and appended to `classify/build/reports/dak-bench.txt`. Add `-Pdak.bench.jfr=<file>.jfr` to
  record a JFR CPU profile as well. The benchmark compares 3 pipeline configurations (plain, prefilter, and
  prefilter plus cache), each on 1 and 3 threads, and prints the stage breakdown and the cache hit rate.

## What changed

All of the changes below give exactly the results the old code gave: same categories, same confidences to the last
bit, same labels, OTPs, transactions and verdicts. So none of them needs a re-index, and the enricher's
`LOGIC_REVISION` is unchanged.

1. **Single-pass keyword prefilter (Aho-Corasick).** These classes live in `app.dak.classify.text` and are pure
   Kotlin with no dependencies:
   - `AhoCorasick` finds every keyword of a set in one pass over the text.
   - `RequiredLiterals` works out, conservatively, a set of literals from a regex such that at least one of them
     occurs in every match. It returns null when it is unsure: for `\Q..\E`, `\x`, `\u`, octal escapes, named
     backreferences, `x` or `u` flags, a malformed pattern, or a pattern with no safe literal. A null means the regex
     always runs.
   - `KeywordPrefilter` scans for a whole rule list at once. `GatedRegex` is a drop-in replacement for `Regex`.

   The template rules (all 16 are gated) now take one scan instead of up to 16 backtracking alternations.
   `BankNames` takes one scan instead of 24 regexes. 12 of the detector's 16 patterns (the other 4 have no safe
   literal and still always run), `OtpExtractor`'s keyword, amount and WebOTP patterns, `LinkExtractor` and
   `LinkPresence` each run their regex only when a keyword is present. Matching folds case the same way
   `Regex(IGNORE_CASE)` does, which in Kotlin also turns on Unicode case folding: the Kelvin sign matches `k` and the
   long s matches `s`. So the prefilter can let through a text the regex then rejects, but it never skips a text the
   regex would match.
2. **Shared analysis in the detector.** The enricher calls `isCandidate`, `needsRecentMessages` and `evaluate` on the
   same body one after another. The normalised text and its amounts are now computed once. The detector keeps the
   last body per thread, compared by identity, and the work is a pure function of the body, so reusing it is exact.
3. **Naive Bayes tables.** The per-token, per-class log-probabilities are precomputed, so a token costs one lookup
   instead of one lookup and one `ln` per class. The sums use the same expressions in the same order, so the
   results are bit-identical.
4. **Rule lists memoised** per region and rule group. Previously each message filtered and sorted the rule list.
5. **Parallel enrichment with a single writer** in `:core-index` (see "Android side").

### The template-hash cache: built, proven exact, and off by default

`TemplateCache` works like this:

- It keys a message on its **template**: the body with every ASCII digit replaced by `0`, plus the region, the rule
  group, the sender's bundle entry and its DLT traffic type.
- It caches the template-dependent results, bounded by an LRU of 2,048 entries: which rule fired, with its category,
  confidence and labels, and the model's scores.
- It always recomputes the per-message parts from the actual body: the OTP code, the unknown-sender-link label, the
  contact and local-mobile boost, and the cloud stage. Transactions, links and scam verdicts are outside the cache
  entirely.

Exactness limits how much the key can mask:

- **Lengths must be kept.** Rules such as `\d{4,8}\b` and `.{0,30}` count characters, so the key cannot collapse a
  digit run.
- **Letters must be kept.** Rule keywords can occur anywhere, so merchants, names and link paths cannot be masked
  either.
- **Model tokens must be kept.** The bundled model has weights for tokens such as `500`, `1000` and `123456`, so those
  digit tokens go into the key verbatim.

The cache engages only for digit-blind bundles: no digit outside `{m,n}`, no backreference. An OTA bundle that can
tell digits apart turns it off.

With those limits, only **7% of the corpus hits the cache**. Amounts, balances, merchants and names vary too much
within a template. Once the prefilter is on, classification costs about 9 µs, and building and hashing the key costs
about as much as it saves:

| | 1 thread, classify only | 1 thread, full path | 3 threads, full path |
|---|---:|---:|---:|
| prefilter | 113,000 | 14,200 | 35,800 |
| prefilter + cache | 90,000 | 13,800 | 35,100 |

So `ClassifierPipeline(cacheSize = 0)` is the default. `SUGGESTED_CACHE_SIZE` (2,048) enables it if a future model
or bundle makes templates repeat verbatim more often. Masking harder, for example collapsing digit runs, would mean
classifying a masked body instead of the real one. That changes results, so it would need its own accuracy
evaluation and a re-index.

## Correctness proof (JVM tests, run in CI with `:classify:test`)

- `PipelineEquivalenceTest`:
  - The prefiltered pipeline, with and without the cache, returns `Classification`s **equal** to the plain pipeline's
    (no prefilter, no cache) for 150,000 messages: the 50k corpus plus two copies with every digit shuffled, some with
    model-vocabulary numbers appended, so they hit the cache with different digits. It does the same on a 10k slice
    for an unknown region and for GB. There are zero differences.
  - Per-message parts never come from the cache: two OTPs of one template keep their own codes, and a contact and a
    stranger sending the same body keep their own confidence.
  - The cache turns itself off for digit-sensitive bundles.
  - Across the corpus, no rule skipped by the prefilter matches.
  - The Naive Bayes tables give bit-identical scores to the textbook loop.
- `GatedRegexEquivalenceTest`: every gated production pattern finds exactly what the bare regex finds, with the same
  match ranges, over 25k texts. These include case tricks, Kelvin sign and long s, Devanagari, upper-cased messages
  and messages with the spaces stripped. `BankNames` and the template rules never lose a match to the prefilter.
- `RequiredLiteralsTest`: soundness over more than 10,000 real matches (every match contains one of its literals),
  plus the unsupported constructs. `AhoCorasickTest` cross-checks the automaton against naive search on random input.
- The existing suites pass unchanged: `ClassifierPipelineTest` (accuracy), `FakeCreditDetectorTest`,
  `OtpExtractorTest`, `SecurityTest` (ReDoS) and the finance tests.

## Android side (`:core-index`, `:core-telephony`, `:app`; compiled by CI)

- **Parallel classification, one writer.** `IndexIngestor` classifies the messages of a chunk that need enriching
  on `Dispatchers.Default.limitedParallelism(min(3, cores − 1))`, split into up to 3 slices. Chunks of fewer than 64
  messages, such as a single incoming SMS, stay inline. Only the ingestor coroutine writes. This is possible because
  `DefaultMessageEnricher.enrich` is now lock-free: the pipeline, parser and detector are thread-safe, and the old
  mutex only guarded against a regex cache that had long been a `ConcurrentHashMap`. `installTemplates` swaps the
  bundle under the same monitor that builds the state, so a racing call cannot reinstall the old bundle.
- **One transaction per 500 rows.** `CHUNK` went from 200 to 500, matching the stage-2 batch. Each chunk's list
  `@Insert(IGNORE)`, list `@Update`, fold moves and repeat-group updates now share one `withTransaction`; repeat
  groups used to commit one transaction per match. The FTS rows are written by Room's content triggers inside that
  same transaction. The ingestor `yield()`s between chunks.
- **No REPLACE on purpose.** `@Insert(onConflict = REPLACE)` deletes and re-inserts. SQLite fires the delete
  triggers for that only with `recursive_triggers` on, so the external-content FTS4 index would keep stale entries.
  The upsert stays insert-ignore plus update, both as list calls.
- **FTS4.** `automerge` stays at its default (off), so inserts do no extra merge work. When a stage-2 pass
  (backfill, re-index or rebuild) finishes, `FtsMaintenance.optimize` runs
  `INSERT INTO message_fts(message_fts) VALUES('optimize')` once, merging the segments so every later search visits
  one segment. It runs once per pass, never per batch.
- **WAL, checked.** Room's default `JournalMode.AUTOMATIC` means WAL except on low-RAM devices, and
  `IndexDatabaseFactory`'s deferred open helper forwards `setWriteAheadLoggingEnabled` to the SQLCipher helper. So
  readers (the UI, and the scam context during parallel enrichment) never block on the writer.
- **Provider reads.**
  - Paging reads (`messagesBefore`, `recentMessages`, `messagesInThread`, `messagesAfter` and MMS parts) now request
    only the columns they map, not `null`. They also no longer make the provider's `ORDER BY date` sorter carry every
    column of every row.
  - OEM tolerance is kept: the column list is intersected, once per process, with the columns an empty `_id < 0`
    probe reports. A schema without `sub_id` still gets its `sim_id` or slot column. A failed probe falls back to
    `null` and is retried later. `ProviderProjectionsTest` checks the mapping source against the lists, so a column
    added to the mapping cannot silently read as its default.
  - MMS parts were already loaded per page (`mid IN (...)`).
  - MMS addresses cannot be batched. The platform's `MmsProvider` exposes the `addr` table only as
    `content://mms/<id>/addr`, which filters on `msg_id = <id>`, and there is no bulk URI. They stay per message, now
    with a two-column projection and a fallback to `null` if an OEM rejects it.
  - Cursors are walked and closed at the page size. No read materialises a whole table except `allKeys()` (ids only,
    for the deletion scan).
- **Baseline profile.** `app/src/main/baseline-prof.txt` holds hand-written wildcard rules for the hot packages:
  `app.dak.classify.**`, `app.dak.finance.parser.**`, `app.dak.finance.money.**`, `TextNormalizer`,
  `app.dak.index.enrich.**`, `app.dak.index.sync.**` and `TelephonyProviderReader`. AGP packages the file, and Play
  installs compile those methods ahead of time. Sideloaded installs apply it only with `androidx.profileinstaller`,
  which is not in the version catalog yet. Adding it needs this catalog line:
  `androidx-profileinstaller = { module = "androidx.profileinstaller:profileinstaller", version = "1.4.1" }`, then
  `implementation(libs.androidx.profileinstaller)` in `app/build.gradle.kts`.

## Battery

This follows the rules in docs/battery.md:

- No new jobs, alarms or wake locks, and no change to when indexing runs.
- Work per message dropped by half on one core. The backfill therefore finishes in about a fifth of the wall time,
  using at most 3 cores and always leaving one free. It runs in CPU bursts of one 500-row chunk (about 12 ms of CPU
  per slice on the benchmark machine, a few times that on a phone core) with a `yield()` between chunks, then returns to idle
  sooner.
- An incoming SMS runs inline, with no thread hop, and saves about 60 µs of CPU.

## Adversarial-corpus pass (September 2026)

Same benchmark (JVM 21, 4-core sandbox, 50,000 synthetic messages, median of 5 passes). "Before" is commit
`15c1dfd`. "After" includes the parser and link-checker changes below, plus the new scam rules of template bundle 5.

| Path / stage | Before | After |
|---|---:|---:|
| Full enrichment path, 1 thread (prefilter) | 5,229 msgs/s | 8,569 msgs/s (**1.6×**) |
| Full enrichment path, 3 threads (prefilter) | 9,223 msgs/s | 16,250 msgs/s (**1.8×**) |
| Full enrichment path, 1 thread (per message) | 199 µs | 120 µs |
| `TransactionParser.parse` (every message) | 174 µs | 59 µs (**2.9×**) |
| `TransactionParser.parseBillReminder` | 16.3 µs | 3.7 µs |
| `LookalikeDomainChecker` on a message's links | 5.0 µs | 3.4 µs |
| Classification only, 1 thread (prefilter) | 24,447 msgs/s | 24,665 msgs/s (9 more rules, unchanged) |
| A 3,000-mark "zalgo" body, all defences (`unicode-zalgo-02`) | 1,580-1,700 ms | a few ms |

What changed:

- **Literal gates in `:finance`** (`parser/LiteralGate.kt`, `GatedPattern`). This is a small local version of
  `GatedRegex`, because `:finance` does not depend on `:classify`. The direction cues, the OTP word, the promotion,
  request and statement vetoes and the bill-reminder patterns now run only when one of their keywords is in the
  case-folded body. The body is folded once per message. `InvestmentParser` checks one vocabulary pattern first and
  skips its ~20 passes for messages that are not about investments. `LiteralGateTest` checks, on the whole parser
  corpus in upper-case, lower-case and Unicode-case variants, that the gates never skip a match and that gated and
  ungated results are identical. The bill reminder's due-date regex is compiled once.
- **`LookalikeDomainChecker`** splits brand names into words once, not on every check. It skips the edit distance
  when the lengths differ by more than 2, and computes it with two rows.
- **Analysis text** (`AnalysisText`, in `:classify` and copied in `:finance`) caps runs of combining marks at 8
  before any regex runs. Before that cap, every `\b` rescanned the whole run.

Locale: every `lowercase()` / `uppercase()` in `:classify` and `:finance` is Kotlin's locale-invariant form, so no
code change was needed. `LocaleIndependenceTest` pins identical results under Turkish, Azerbaijani and Lithuanian
default locales.

## Ledger and inbox (September 2026)

This pass covers the database side of `:core-index` and the ledger code in `:finance`. The tests came first: they were
committed and passed on CI against the old code before anything changed, and they pass unchanged now (only the cost
assertions were tightened).

### Before and after

| Path | Before | After |
|---|---:|---:|
| Stage-2 backfill, 5k msgs (8 batches): account rebuilds / index rows read | 112 / 11.8k | 14 / 2.3k (one full pass) |
| Stage-2 backfill, 20k msgs (38 batches) | 532 / 188k | 28 / 14k |
| Stage-2 backfill, 50k msgs (98 batches) | 1,372 / 1.14M | 70 / 70k (**16×** fewer rows) |
| Stage 1 (~1,000 msgs, 5 chunks) | ~65 rebuilds | one per touched account (14) |
| Read / seen / delivery-report re-ingest | every touched account rebuilt | nothing rebuilt |
| Ledger rows read per rebuild | whole index rows (body, FTS text, JSON) | 5 columns |
| Ledger entries written per rebuild | every entry of the account, deleted and re-inserted | only new or changed entries |
| Inbox first page, All tab, 20k rows (SQLite 3.45, in memory) | 24 ms | 6.5 ms (**3.7×**) |
| Inbox first page, Archived tab, 20k rows | 13.8 ms | 2.1 ms |
| Inbox, category tabs (they already used the category index) | 4.5-6.8 ms | 3.6-5.1 ms |
| `Reconciler.reconcile`, 1,000 foreign spends x 10,000 settlements | 900 ms | 18 ms (**50×**) |
| `AccountLedger.cardOutstanding`, 20k entries, 24 calls | 273 ms | 49 ms |
| `Account.idFor`, 200k calls | 261 ms | 116 ms |

The backfill figures count work, not time. They come from a model of the rebuild policy using the test corpus's mix
(40% transactions over 13 accounts). `LedgerBackfillCostTest` measures the same counters on the real code with
SQLite triggers and prints them as `LEDGER-COST` lines. The inbox figures are from the same SQL on a
same-schema SQLite database (Python's `sqlite3`, 3.45). `InboxQuerySnapshotTest` prints the Android numbers as
`INBOX-BENCH` and the plans as `INBOX-PLAN`. The finance figures are JVM 21 (`LedgerHotPathEquivalenceTest`,
`ReconcilerEquivalenceTest`, printed as `*-BENCH`).

### What changed

1. **Backfill rebuilds the ledger once, not after every batch** (`IndexIngestor.ingest(deferLedger = ...)`,
   `IndexMaintenance`). Every ingest ended with `ledger.recompute(affected)`, which rebuilds each touched account from
   all of its rows. A 500-message backfill batch touches every busy account, so a 50k backfill rebuilt each of them
   about 100 times over a growing history, with roughly N/1,000 times the work of one pass. Stage 2 now collects the touched
   accounts and rebuilds them every 20 batches (10,000 messages), so the Passbook still fills in during a long
   pass. It also rebuilds them when the night window closes, and as a best effort when the pass stops. Every pass ends with one
   `recomputeAll()`. That final rebuild also repairs a pass that was stopped before it could rebuild. Stage 1 rebuilds once for the
   whole stage. Live callers (an incoming SMS, the reconcile, restore, scam overrides) still rebuild before
   `ingest` returns.
   *Why it is exact.* An account's ledger is a pure function of the indexed rows, the user's merges, types and
   statement days. It does not depend on the order or batching in which those rows arrived. `LedgerEquivalenceTest`
   checks this: after a staged backfill, one big ingest, message-by-message ingests, odd-sized batches, a backfill
   stopped and resumed, a re-index, live ingests and user actions, the tables must equal `ReferenceLedger`, an
   independent from-scratch rebuild.
2. **Only ledger inputs trigger a rebuild** (`IndexIngestor.ledgerInputsChanged`). `LedgerRepository` reads a row's
   account id, transaction JSON, labels (the fake-credit exclusion), date and key. A refresh that changes only
   read, seen, box, SIM, thread or delivery state leaves all of these alone, so it no longer rebuilds the account.
   A date change still does, and the tests check that.
3. **Narrow ledger reads** (`MessageDao.byAccount` / `byAccounts` now return `LedgerSourceRow`). They read five columns instead
   of whole rows, with the same WHERE and ORDER BY.
4. **Diff-written entries** (`LedgerRepository.recomputeCanonical`). The rebuilt entries are compared with the stored
   ones. Only the entries that disappeared are deleted, and only new or changed ones are written. An incoming SMS
   on an account with 3,000 entries used to delete and re-insert all 3,000.
5. **Passbook sections read one billing cycle** (`LedgerDao.entriesBetween`). A card's outstanding sums only the
   current cycle, so only that cycle's entries are loaded.
6. **Inbox list: covering index plus a two-step query** (`ConversationSqlBuilder.page`, `MIGRATION_7_8`). The
   filter-and-group step reads only the new index
   `(conversationId, dateMillis, category, subId, archived, starred, read, box, threadId)`. It carries the rowid of
   each conversation's newest matching message, and SQLite's bare-column rule for a single `MAX()` still picks that
   message. Only the page's 30 newest messages are then read, by rowid, for their snippet and other display
   columns. Before, every message row (body, FTS text, JSON) was read for every page. The rows returned are
   identical, which `InboxQuerySnapshotTest` checks page by page for every tab and SIM filter. When two messages tie for newest, SQLite
   picks one of them, as it did before, and the test checks only that. The migration is additive. The index costs
   about 5% on bulk inserts.
7. **Sender suggestions per search session** (`SearchRepository.senderNames()`, `suggestions(..., senders)`). The
   sender list is one GROUP BY over the whole index, and it ran on every debounced keystroke. The search screen now loads it
   once per session.
8. **Reconciler** (`:finance`). Each estimate binary-searches the first settlement on or after its date and scans
   only up to its date window. The date-sorted list keeps the full scan's order, so ties still resolve to the same
   entry. `ReconcilerEquivalenceTest` compares it with a verbatim copy of the old code over thousands of seeded
   ledgers, including the edge cases of the window, the tolerance and duplicate keys.
9. **`AccountLedger` sorts once** (a lazy sorted list instead of a getter that re-sorted on every
   `cardOutstanding` call), and **`Account.idFor` compiles its whitespace regex once**. Both are pinned against
   copies of the old code.

### Considered and not done: an FTS4 prefix index

`@Fts4(prefix = "2,3")` was measured on 50,000 messages with a realistic (Zipf) vocabulary:

- The FTS lookup for a two-letter prefix got 3× faster, but it was already about 0.15 ms.
- A whole prefix search, including loading the matching rows, got only about 15% faster.
- The FTS index grew 2.6× (3.3 MB to 8.8 MB), and bulk inserts slowed by about 55%.

The FTS lookup is not what search spends its time on, so the index was not worth it. `SearchEquivalenceTest` stays as the guard if it is revisited.

## Next

- **`TransactionParser` / `MoneyParser` (`:finance`)**: the parser's gates are done (see above). `MoneyParser`'s
  pattern remains. The notes below predate that pass. **`TransactionParser` / `MoneyParser` (`:finance`)** is now the largest cost: about 47 µs per parsed message,
  roughly half of what remains. Its negative checks (OTP, promotion and bill wording), direction words and
  `MoneyParser`'s pattern are all literal-keyed alternations that `GatedRegex` would gate. `MoneyParser`'s currency
  prefix is optional, so a 100-way alternation is tried at every digit. Two things stop this for now. `:finance`
  deliberately does not depend on `:classify`. The fix is to move `app.dak.classify.text` (about 350 lines, no
  dependencies) into a small shared module both can use, which is a lead decision because it means editing
  `settings.gradle.kts`. And `:finance` is being edited concurrently.
- `OtpExtractor`'s trailing-hash regex (`[A-Za-z0-9+/]{11}\s*$`) is tried at every position. A right-to-left scan
  would make it linear, but it needs care to keep `$`'s final-line-terminator semantics.
- Device numbers for the provider and database side: backfill time for a 50k-message phone, before and after. They
  need a real device (see docs/device-test-plan.md).
