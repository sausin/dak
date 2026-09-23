# :core-index

Android library, package `app.dak.index`. The encrypted index (Room 2.6 + SQLCipher 4.6) that every smart
feature reads from: classification, sender merge groups, OTP lifecycle, recycle bin, search, finance ledger,
saved searches, automation rules, scheduled sends and an audit log. The Telephony provider stays canonical and
raw; everything here is rebuildable from it (user data such as prefs, bin, rules is kept across rebuilds but is
lost if the Keystore key is wiped, so `:backup` should cover it).

Depends on `:core-model`, `:classify`, `:finance`, `:search` (api) and `:core-telephony` (contracts only:
`ProviderReader`, `ProviderWriter`, `ProviderChanges`, `SimRepository`, `IncomingMessageHandler`).

## What the app must do

1. **Hilt**: the bindings install into `SingletonComponent` automatically (`IndexProvidesModule`,
   `IndexBindsModule`). :core-telephony must bind `ProviderReader`, `ProviderWriter`, `ProviderChanges`,
   `SimRepository`. This module contributes `IncomingIndexer` to `Set<IncomingMessageHandler>` (`@IntoSet`,
   priority 100); the receivers must inject that set as `Set<@JvmSuppressWildcards IncomingMessageHandler>`.
2. **WorkManager + Hilt workers**: the workers (`BackfillWorker`, `OtpSweepWorker`, `MaintenanceWorker`) are
   `@HiltWorker`. The `Application` must implement `androidx.work.Configuration.Provider`
   with an injected `HiltWorkerFactory`, and remove the default initializer in the app manifest:
   ```xml
   <provider android:name="androidx.startup.InitializationProvider"
       android:authorities="${applicationId}.androidx-startup" tools:node="merge">
       <meta-data android:name="androidx.work.WorkManagerInitializer"
           android:value="androidx.startup" tools:node="remove" />
   </provider>
   ```
3. **Start sync**: call `IndexSync.start()` from `Application.onCreate` (idempotent, main-safe).
4. **Onboarding**: call `IndexMaintenance.chooseSchedule(IndexSchedule.NOW | WHEN_CHARGING | TONIGHT)`.
   Until then stage 2 runs "When plugged in".
5. **Backup rules**: exclude `databases/dak_index.db*` from Auto Backup / device transfer (it is useless without
   the Keystore key, which never leaves the device). The wrapped key lives in `noBackupFilesDir` already.
6. **Optional bindings** (`@BindsOptionalOf`; bind with `@Binds` in a `SingletonComponent` module to override):

   | Seam | Default when unbound |
   |---|---|
   | `BinPolicy` — `suspend retentionMillis(category): Long?` (null = until emptied) | `DefaultBinPolicy`: OTP 1 day, others 30 days |
   | `OtpPolicy` — `otpAutoDeleteAfterMillis(): Long?`, `consumedOtpMode(): ConsumedOtpMode`, `consumedOtpDeleteAfterMillis()` | `DefaultOtpPolicy`: 24 h; `SILENT_AUTO_DELETE`; 10 min (clamped to >= 5 min) |
   | `ContactLookup` — `displayName`, `isContact`, `addressesMatching`, `namesMatching` (blocking, called off-main) | `NoContactLookup` |
   | `app.dak.index.repo.RatesSource` — `current(): RatesTable?` | bundled `RatesLoader.loadBundled()` |
   | `app.dak.classify.CloudClassifier` (premium only; must itself honour the opt-in) | `NoCloudClassifier` |

7. Injecting the database or any repository is cheap on any thread: the Keystore unwrap and SQLCipher open are
   deferred to the first real query, which Room runs on its background executor (`IndexDatabaseFactory`).
   `OpenedIndex.ensureOpen()` is the explicit suspend opener; never call a blocking DAO method on main.
8. **Periodic work**: do not enqueue your own periodic worker for housekeeping - contribute a `MaintenanceTask`
   (see "Maintenance" below). See `docs/battery.md` for the background-work budget.

## Public API

All repository functions are `suspend` or return `Flow` and are main-safe.

### Types (`app.dak.index`)

- `enum InboxTab { ALL, PERSONAL, TRANSACTION, OTP, PROMOTION, SPAM, ARCHIVED, STARRED }` — ALL excludes spam and
  archived; category tabs filter at message level (HDFC shows in both Transactions and OTP with the matching
  snippet); ARCHIVED/STARRED include conversation- and message-level flags.
- `enum SearchSort { RECENT, RELEVANCE, AMOUNT }`
- `ConversationSummary(conversationId, title, address, snippet, dateMillis, unreadCount, messageCount, category,
  subIds: Set<Int>, threadIds: Set<Long>, isMergedSender, pinned, muted, archived, starred, lastBox,
  hasAttachment, enriched)` — `enriched = false` for provider threads the backfill has not reached yet.
- `MessageItem(key, conversationId, threadId, address, body, dateMillis, box, read, subId, attachments, category,
  confidence, canonicalSender, labels, otp: OtpItem?, transaction: TransactionItem?, hasLink, starred, archived,
  enriched, repeatCount = 1, repeatOf: MessageKey? = null, channel: String? = null, deliveryStatus: DeliveryStatus =
  NONE, deliveredAtMillis: Long? = null)` — `repeatCount`: copies in its repeat group (see "Repeated messages");
  `channel`: `SenderId.mergeKey` of the address (per-bubble channel chips); `deliveryStatus` (core-model
  `DeliveryStatus`: NONE / PENDING / DELIVERED / FAILED) + `deliveredAtMillis`: the second tick of outgoing messages
  (group MMS: DELIVERED only when every recipient was).
- `ConversationSummary.snippetRepeatCount` (default 1): copies of the snippet's message ("×3" in the inbox).
- `ConversationSummary.lastDeliveryStatus` (default NONE): delivery state of the snippet's message (ticks on an
  outgoing snippet; pair it with `lastBox`).
- `OtpItem(code, consumedBy, webOtpDomain, repeatedLater)` — `repeatedLater`: the same code arrived again within
  10 min in this conversation (collapse the older copy).
- `TransactionItem(direction, amountMinor, currency, instrumentLast4, merchant, accountId)`
- `SearchHit(conversationId, conversationTitle, message: MessageItem, matchCount, highlights: List<IntRange>,
  binId: Long?)` — highlight ranges are inclusive offsets into `message.body`.
- `BinItem(id, originalKey, conversationId, threadId, address, body, dateMillis, subId, category, attachments,
  deletedBy, deletedAtMillis, purgeAtMillis)`
- `enum IndexSchedule { NOW, WHEN_CHARGING, TONIGHT }`, `enum BackfillStage { NOT_STARTED, STAGE1, STAGE2, DONE }`,
  `enum BackfillReason { INITIAL, REINDEX, RESTORE, REBUILD }`
- `BackfillProgress(stage, done, total, schedule, reason, waiting)` + `remaining`, `fraction`.
- Conversation ids: `t:<threadId>` (provider thread) or `m:<mergeKey>` (sender merge group); helpers in
  `app.dak.index.enrich.ConversationIds`. Ids can move when the user folds/unfolds senders; every repository
  function taking a conversation id resolves old ids through the alias map, and
  `ConversationRepository.resolveConversationId(id)` does it explicitly (use it for notification/search links).

### `repo.ConversationRepository`

```kotlin
fun conversations(tab: InboxTab, subId: Int? = null, pageSize: Int = 30): Flow<PagingData<ConversationSummary>>
fun messages(conversationId: String, pageSize: Int = 50, channel: String? = null): Flow<PagingData<MessageItem>>   // newest first; one bubble per repeat group; channel = filter a folded conversation
suspend fun resolveConversationId(conversationId: String): String
suspend fun repeatsOf(key: MessageKey): List<MessageItem>   // every copy of a repeat group, newest first
fun message(key: MessageKey): Flow<MessageItem?>
suspend fun conversationIdOf(key: MessageKey): String?
fun prefs(conversationId: String): Flow<ConversationPrefs?>
suspend fun markRead(conversationId: String)            // index + provider
suspend fun markMessageRead(key: MessageKey)
suspend fun setPinned / setMuted / setArchived / setStarred(conversationId, Boolean)
suspend fun setBubbleColor(conversationId, argb: Int?) / setFontScale(conversationId, Float?) / setAlwaysTranslate(conversationId, Boolean)
suspend fun setReplySim(conversationId: String, subId: Int?)     // null = default
suspend fun replySimFor(conversationId: String): Int              // choice > SIM of last incoming > system default
suspend fun addressesFor(conversationId: String): List<String>    // reply recipients
suspend fun setMessageStarred(key, Boolean) / setMessageArchived(key, Boolean)   // survive rebuilds
```

Pinned conversations sort first. While the backfill runs, the ALL tab appends provider threads with no indexed
rows, and `messages("t:<id>")` continues past the indexed part straight from the provider.

### `repo.SearchRepository`

```kotlin
fun search(query: SearchQuery, sort: SearchSort = RECENT, pageSize: Int = 30): Flow<PagingData<SearchHit>>
suspend fun suggestions(prefix: String, limit: Int = 8): List<app.dak.search.Suggestion>
suspend fun recordQuery(queryText: String)
suspend fun clearHistory()
```

Parse input with `app.dak.search.QueryParser.parse(text, ZonedDateTime.now())`. Text runs through FTS4
(`unicode61`, `TextNormalizer`-normalized body + sender) using only standard query syntax; OR-of-AND groups and
negation are composed in SQL, so any `TextExpr` works regardless of SQLCipher's FTS compile options. (This is why
`:search`'s `FtsMatch.build` is not used: it flattens such expressions.) Terms of 2+ chars are prefix-matched. Every amount in a body is also indexed as canonical tokens (`app.dak.search.AmountTokens`, FTS text
column only), so "500000", "5,00,000" and "Rs.5,00,000/-" find each other; hits highlight the matching amount.
Filters are column lookups: `from:` (address / brand / merge-group name / contact numbers), `category:`, `sim:`
(1-based slot, sub id, display or carrier name), `has:attachment|link|otp`, `amount:`, dates, `in:inbox|archive`,
`is:starred|unread|read`, negation. `in:bin` searches the recycle bin instead (LIKE; from/category/sim/date/
has:otp/has:attachment only). RELEVANCE = most matching messages per conversation, then recency.

### `bin.RecycleBin`

```kotlin
suspend fun moveToBin(keys: Collection<MessageKey>, deletedBy: DeletedBy): BinReceipt   // copy, then provider delete
suspend fun undo(receipt: BinReceipt): Int
suspend fun restore(binId: Long): MessageKey?          // provider re-insert + re-index
fun observe(): Flow<List<BinItem>>;  fun count(): Flow<Int>
suspend fun deleteForever(binId: Long);  suspend fun empty(): Int
suspend fun purgeExpired(nowMillis: Long = now): Int  // also run by the daily maintenance job (BinPurgeTask)
suspend fun message(binId: Long): Message?
```

`DeletedBy`: `Manual`, `AutoRule(name)`, `AutoConsumed(pkg)`, `AutoOtp`; stored as `manual`, `auto-rule:<name>`,
`auto-consumed:<pkg>`, `auto-otp` (`DeletedBy.decode`). A failed provider delete (not default SMS app) leaves the
message in place and reports it in `BinReceipt.failed`.

### `otp.OtpLifecycle`

```kotlin
suspend fun onIndexed(row: IndexedMessage)            // called by IncomingIndexer / reconcile
suspend fun shouldNotifySilently(message: Message): Boolean   // for the notification handler (runs before indexing)
suspend fun deleteNow(key: MessageKey): Boolean       // "delete now" quick action
fun cancel(key: MessageKey)
```

Never deletes retroactively (an OTP discovered after its lifetime, e.g. from a restore, is left alone); starred
messages are skipped. Battery: pending deletes are queued (`OtpDeleteQueue`, a small SharedPreferences file) and
ONE unique job (`dak-otp-sweep`, inexact, no alarm) is armed for the most urgent deadline; it deletes everything
due in one batch and re-arms (`OtpSweepPlan`: consumed OTPs never early and at most 3 min late; 24 h deletes may
run 45 min early / 15 min late so OTPs of the same hour share one wakeup). A policy switched off after queuing
wins (the message stays).

### `signature.AppSignatureRegistry`

```kotlin
suspend fun refresh(force: Boolean = false): Int
suspend fun consumerOf(otp: OtpInfo, refreshOnMiss: Boolean = true): String?
suspend fun hashesOf(packageName: String): List<String>
```

Also `suspend fun refreshIfNeverBuilt(): Int` (called by `IndexSync.start()`).
Hashes via `app.dak.classify.AppSignatureHash` for every signing certificate (P+ `GET_SIGNING_CERTIFICATES`,
`GET_SIGNATURES` below). Package broadcasts are not receivable on 8+; the table is persisted, built once on first
start, refreshed incrementally (by `lastUpdateTime`) by the daily maintenance job, and on an unknown hash
(throttled to once a minute; a hash still unknown after a refresh is not retried for 6 h, persisted). Visibility comes from the
`<queries>` in this module's manifest (launcher apps, https browsers).

### `repo.LedgerRepository` (on `:finance`)

```kotlin
fun accounts(): Flow<List<AccountSummary>>            // AccountSummary(account: Account, balance: BalanceState, entryCount, lastActivityMillis)
fun account(accountId: String): Flow<AccountSummary?>
fun entries(accountId: String): Flow<List<LedgerEntry>>   // newest first
fun ledger(accountId: String): Flow<AccountLedger?>
fun monthlyTotals(accountId: String): Flow<List<MonthlyTotal>>
fun spendByMerchant(accountId: String): Flow<Map<String, Money>>
fun cardOutstanding(accountId: String, asOfMillis: Long): Flow<Money?>
fun messageKeyOf(entry: LedgerEntry): MessageKey?
suspend fun setStatementDay(accountId: String, statementDay: Int?)
fun accountGroups(nowMillis: Long = now): Flow<List<AccountGroup<AccountGroupItem>>>
    // Passbook sections (Bank accounts, Credit cards, Debit cards, Wallets, UPI, Prepaid & forex, Loans, Investments,
    // Other) with per-currency header totals; AccountGroupItem(summary, spentThisMonth, outstanding, linked:
    // AccountSummary?). spentThisMonth leaves out own-account / investment transfers (ledger_entry.transfer).
suspend fun setAccountType(accountId: String, instrument: InstrumentType?)   // manual type; null = detected
// AccountSummary.typeOverridden / unitsHeld; Account.linkedAccountId; LedgerEntry.viaAccountId / transfer /
// investmentAction / units / unitPrice
suspend fun recompute(accountIds: Collection<String>);  suspend fun recomputeAll()
```

Accounts are recomputed (`Ledger.apply` + `Reconciler.reconcile`) whenever one of their messages is indexed.

Account aliases (a bank that switched `XX440065` -> `XX40065`): account ids use every visible digit
(`Account.idOf`), `AccountMatcher` suggests probable same-account pairs, and the user decides:

```kotlin
fun aliasSuggestions(): Flow<List<AccountAliasSuggestion>>   // (a, b, reason, sampleA, sampleB), undecided pairs only
suspend fun confirmSameAccount(a: String, b: String): String  // merges; the id showing more digits stays canonical
suspend fun confirmDifferentAccounts(a: String, b: String)    // never asked again
suspend fun mergeAccounts(aliasId, intoAccountId);  suspend fun unmergeAccount(aliasId): Boolean
fun mergedInto(accountId): Flow<List<String>>;  fun mergeCandidates(accountId): Flow<List<AccountSummary>>
fun canonicalId(accountId): Flow<String>   // account()/entries()/ledger() already resolve merged ids
```

Decisions live in `account_alias` (user data); ledgers post alias messages to the canonical account.

### `repo.SenderMergeRepository` (sender groups / folding)

```kotlin
fun groups(): Flow<List<SenderMergeGroup>>;  fun group(mergeKey): Flow<SenderMergeGroup?>
fun foldGroups(): Flow<List<FoldGroup>>                  // folded conversations + channels (addresses, last seen, SIMs, count)
fun channelsOf(conversationId): Flow<List<FoldChannel>>
fun foldSuggestions(titleOf = ...): Flow<List<FoldProposal>>   // same brand / similar headers, local heuristics only
fun foldTargets(limit = 200): Flow<List<FoldTarget>>
suspend fun foldTogether(conversationIds: List<String>, name: String? = null): FoldReceipt
suspend fun foldInto(conversationId, targetConversationId): FoldReceipt
suspend fun unfoldChannel(channel: String): FoldReceipt;  suspend fun dissolve(conversationId): FoldReceipt
suspend fun undo(receipt: FoldReceipt): String;  suspend fun dismissSuggestion(proposal)
suspend fun exportRules(): String;  suspend fun importRules(json): Boolean   // carried in the backup settings JSON
suspend fun rename(mergeKey: String, displayName: String);  suspend fun undoRename(mergeKey): Boolean
suspend fun splitSender(address: String): String                    // legacy per-address edits
suspend fun mergeSender(address: String, intoMergeKey: String): String
suspend fun undoSenderEdit(address: String): String?
```

Folding is display-layer only. A **channel** is `SenderId.mergeKey(address)` (`VM-`/`JD-HDFCBK` -> `HDFCBK`). Channels
fold by (1) legacy per-address alias, (2) a user rule in `sender_fold` (fold into a group, or a self-mapping =
"unfolded"), (3) the template bundle brand table (`TemplateBundle.brandKey`: every header of "HDFC Bank" -> `HDFCBK`,
so the brand's main conversation keeps `m:HDFCBK`), else alphanumeric -> `m:<channel>`, others -> `t:<threadId>`
(`enrich.SenderGrouping.resolve` + `GroupingRules`). Manual groups get keys `+<channel>`. `repo.FoldEngine` holds the
rules, re-groups rows after an edit and records `conversation_alias` (old id -> new id, prefs carried over).

### Stores (`repo.Stores.kt`)

- `SavedSearchRepository`: `all()`, `pinned()`: `Flow<List<SavedSearchItem>>`; `save(name, query, sort, pinned)`,
  `update(item)`, `setPinned(id, Boolean)`, `reorder(ids)`, `delete(id)`.
- `AutomationStore` (rule AST JSON is opaque here): `observe()`, `all()`, `enabled()`, `get(id)`,
  `put(name, json, enabled = true, id = null): String`, `setEnabled`, `reorder`, `delete`.
- `ScheduledSendStore`: `pending()`, `pendingFor(conversationId)`, `due(now)`, `get(id)`,
  `schedule(addresses, body, subId, sendAtMillis, conversationId?, ruleId?): Long`, `edit`, `markStatus`, `delete`.
  Alarms and the actual send are the caller's job.
- `AuditLogRepository`: `log(actor, action, target?, detail?)`, `recent(limit)`, `trim()`.
- `AutomationRunStore`: the automation run log (`automation_run`: what each rule sent or skipped, per message, by
  stable rule id with a name snapshot). `add(row)`, `forRule(ruleId)`, `all()`, `count(ruleId, outcome, since)`,
  `trim(now)`: keeps a year (`RETENTION_MILLIS`) and at least the newest `RETENTION_MIN_ROWS` (5,000), whichever
  keeps more. Never trimmed by the audit log's 90 days.

### Sync (`sync`)

- `IndexSync.start()`, `IndexSync.requestReconcile()` (coalesced with provider changes, 3 s window).
- `sync.BackgroundActivityLog`: debug-build-only per-day counters (`record(source)`, `days()`), shown on the
  self-test screen; no-op in release builds.
- `IndexMaintenance`: `progress: Flow<BackfillProgress>`, `schedule`, `chooseSchedule(IndexSchedule)`,
  `requestReindex(reason, schedule? = null)` (after restore use `BackfillReason.RESTORE`), `rebuild()`,
  `installTemplates(bundle: TemplateBundle): Boolean` (verified OTA bundle; re-indexes if the version changed).
- `IndexIngestor.ingest(messages, allowCloud = false, refreshSignaturesOnMiss = false, force = false)` /
  `remove(keys)` — the single write path (used by backup restore if it wants to index directly).
- `enrich.MessageEnricher` — the narrow seam over `:classify` (`ClassifierPipeline`) and `:finance`
  (`TransactionParser`); `DefaultMessageEnricher` is bound. `enrich` must be safe to call concurrently (the default
  one is lock-free), because the ingestor classifies a batch on several threads.
- `db.FtsMaintenance.optimize(db)` — merges the FTS4 segments into one (run once when a stage-2 pass finishes).

### Repeated messages

Incoming, non-personal messages of one conversation that repeat (identical body within 24 h, or the same OTP
code within 10 min; `enrich.RepeatRules`) share `IndexedMessage.repeatGroup` (the oldest copy's key), set at ingest
in both time directions. Template look-alikes with different data are never collapsed. Thread pages return only
the newest copy with `repeatCount`; `repeatsOf(key)` lists every copy. (The old `OtpItem.repeatedLater` flag is
still filled.)

### Fake credit alerts (`app.dak.index.scam`)

- `DefaultMessageEnricher` runs `FakeCreditDetector` on incoming messages. The context comes from
  `ScamContextSource`, implemented by `IndexScamContext`: ledger accounts cached for 5 minutes, incoming rows from
  the last 48 h via `MessageDao.recentForScamCheck`, and "Not a scam" overrides. The verdict is stored as
  `ScamLabels` in `labels`, with no schema change. `LOGIC_REVISION` was 3 for this (now 4, see instruments below).
- `LedgerRepository` skips rows where `ScamLabels.excludedFromLedger(labels)` is true.
- `ScamRepository`:
  - `flaggedConversations(): Flow<Set<String>>` returns the conversations flagged in the last 30 days.
  - `dismiss(key)` records "Not a scam" in `ScamOverrides` (SharedPreferences, message keys only), then force
    re-ingests the message.

## How it works

- **Encryption**: 32 random bytes, wrapped with AES-256-GCM key `dak_index_key` in AndroidKeyStore, stored in
  `noBackupFilesDir/dak_index_key.bin` (a file there rather than SharedPreferences: same no-backup guarantee,
  atomic writes). Passed to SQLCipher as a raw key (`x'<hex>'`, no PBKDF2). If unwrap or open fails, the
  database is deleted, a new key made, and the index rebuilt from the provider.
- **Backfill**: stage 1 in-process (`recentMessages(now - 30 d, 1000)`), stage 2 unique work
  `dak-index-backfill` in batches of 500 via `messagesBefore(cursor)`, cursor persisted in `backfill_state`.
  NOW = no constraints (the user's explicit choice; not expedited: a long job would exceed the expedited quota and needs a foreground
  notification below API 31); WHEN_CHARGING = `setRequiresCharging`; TONIGHT = initial delay to 01:00,
  `setRequiresDeviceIdle` + `setRequiresBatteryNotLow`, stops at 05:00 and re-enqueues for the next night.
  Re-index after template updates / restore reuses the same worker (rows are re-enriched when their
  `templateVersion` differs from `MessageEnricher.version`).
- **Reconcile**: `ProviderChanges` emissions are coalesced (3 s) into one incremental run while the process is
  alive -> `messagesAfter(maxSmsId, maxMmsId)`, refresh of the 20 most recent messages (sent/failed/read changes),
  a refresh of the ticks of unsettled outgoing rows from the last 7 days (sending / queued / failed, or sent with a
  report pending; at most 200, one indexed query and, only when some exist, one narrow `ProviderReader.outgoingStates`
  query without bodies, applied in place with `MessageDao.setOutgoingState`), and at most every 30 min a provider
  count check (deletion check when it dropped). The daily maintenance job
  runs a full reconcile whose deletion check is skipped when provider and index counts match. An empty provider
  key set never empties the index.
- **Maintenance** (`app.dak.index.maintenance`): ONE unique periodic job `dak-maintenance` (daily, 6 h flex,
  device idle + battery not low; a catch-up run with battery-not-low only if it has not run for 3 days). It runs
  every `MaintenanceTask` in the multibound `Set<MaintenanceTask>`:
  ```kotlin
  interface MaintenanceTask { val name: String; val order: Int get() = 100; suspend fun run() }
  // contribute from any SingletonComponent module:
  @Binds @IntoSet abstract fun myTask(impl: MyTask): MaintenanceTask
  ```
  Built-in: `BinPurgeTask` (10: bin purge + audit trim), `ProviderReconcileTask` (20), `SignatureRefreshTask` (30).
  Features use order 100+, keep tasks short and idempotent, and only *re-arm* precise alarms here (birthday
  messages, template refresh when OTA fetching lands). `MaintenanceScheduler.runSoon()` runs a pass on demand.
- **FTS**: external-content FTS4 over `searchText`/`searchSender`; rows are written with insert-ignore + update
  (never REPLACE) so Room's content-sync triggers keep it consistent. `automerge` stays at FTS4's default (off: no
  extra merge work per insert); when a stage-2 pass (backfill, re-index, rebuild) finishes, `FtsMaintenance`
  runs `INSERT INTO message_fts(message_fts) VALUES('optimize')` once, so searches visit one segment.
- **Write throughput** (docs/performance.md): `IndexIngestor` works in chunks of 500 (the stage-2 batch size).
  Messages that need enriching are classified on `Dispatchers.Default.limitedParallelism(min(3, cores - 1))` in a
  few slices; the ingestor coroutine alone writes, and each chunk's rows, fold moves and repeat groups go into ONE
  `withTransaction` (list `@Insert(IGNORE)` + list `@Update`; FTS rows follow via the content triggers in the same
  transaction). It `yield()`s between chunks. Room's default journal mode (`AUTOMATIC` = WAL except on low-RAM
  devices) applies: `IndexDatabaseFactory`'s deferred helper forwards `setWriteAheadLoggingEnabled` to SQLCipher.
- **Schema**: version 6, exported to `core-index/schemas`. The DB holds user data, so every version ships a real
  migration in `db.IndexMigrations` (1 -> 2 adds `sender_fold`, `conversation_alias`, `account_alias`,
  `indexed_message.repeatGroup` + index, `ledger_account.maskedNumber`); only downgrades are destructive.
  `IndexMigrationSchemaTest` (Robolectric) checks the migrated schema equals Room's own. Enricher
  `LOGIC_REVISION` 2 re-indexes everything once after the upgrade (brand folds, repeat groups, account ids).
  2 -> 3 (`MIGRATION_2_3`): `ledger_account.linkedAccountId`, user-data table `account_type_override`
  (`AccountTypeOverrideRow`: manual account types), and the derived `ledger_entry` recreated with key
  `(accountId, messageKey)` + `viaAccountId` (a debit-card spend posts to the card and to the bank account it names).
  `LOGIC_REVISION` 4 re-parses instruments and refills the ledger.
  3 -> 4 (`MIGRATION_3_4`, additive): `indexed_message.deliveryStatus INTEGER NOT NULL DEFAULT -1`
  (`DeliveryStatus.code`) and `deliveredAtMillis INTEGER`. No re-index: old rows read "no report"; the reconcile
  refreshes recent outgoing rows and any re-ingest fills the columns. Recompute order: debit cards/loans first, then
  the bank accounts they name (current and previous link).
  4 -> 5 (`MIGRATION_4_5`, additive): user-data table `automation_run` (`AutomationRunRow`) with indices on
  `(ruleId, atMillis)` and `atMillis`. Starts empty; no re-index (`LOGIC_REVISION` unchanged).
  5 -> 6 (`MIGRATION_5_6`, additive): investments in the Passbook. Derived `ledger_entry` gains `transfer INTEGER NOT
  NULL DEFAULT 0` (own-account / investment money, never counted by `observeDebitsSince`), `investmentAction`,
  `units`, `unitPrice` (TEXT); `ledger_account` gains `unitsHeld` (TEXT). Old rows read "not a transfer" until the
  ledger is recomputed by the re-index that ships with the parser change (the coordinator bumps `LOGIC_REVISION`).
  A valuation message (`InvestmentAction.VALUATION`, amount 0) keeps its `transactionJson` and `accountId` for the
  ledger but no `amountMinor` / `currency` / `direction` / `merchant` on its row (no amount chip, search or automation).

## Tests

`src/test` holds JVM unit tests for the pure parts (SQL builders, FTS rendering, highlighting, grouping,
timing, retention, enricher). They need no Android runtime; CI runs them with `:core-index:testDebugUnitTest`.

## Known limits

- Hinglish transliteration is not handled by the FTS tokenizer (`unicode61`); Indic scripts match via
  normalized tokens.
- Contacts-based `from:` matching compares merge keys (last 10 digits for numbers).
- Message-level stars/archives survive `rebuild()` (kept in `message_flag`), but per-message state keyed by
  provider id cannot survive a backup restore, which assigns new provider ids.
