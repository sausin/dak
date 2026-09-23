# :app

The Android application (package `app.dak`): Compose + Material 3 + Hilt + Navigation-Compose (string routes),
flavours `free` / `premium` (only `src/<flavour>/kotlin/app/dak/flavor/TierModule.kt` differs).

## Layout and ownership

| Path | Owner | What |
| --- | --- | --- |
| `DakApplication`, `MainActivity`, manifest, `res/` | shell | Hilt app + WorkManager `Configuration.Provider`, single activity, SENDTO/SEND/VIEW handlers, themes, icons, strings |
| `navigation/` | shell | `Routes` (pinned contract), `DakNavigator`, `DakNavHost`, `IntentRoutes`, `PendingShare` |
| `ui/theme/` | shell | `DakTheme`, `DakColors` tokens, palettes |
| `ui/common/` | shell | shared composables (below) |
| `ui/onboarding/` | shell | onboarding flow + `SmsRole`, `RuntimePermissions`, `BatteryOptimization`, `OemGuidance` |
| `ui/settings/`, `settings/` | shell | Settings UI; DataStore-backed `SettingsStore` |
| `ui/selftest/`, `notifications/` | shell | notification poster, channels, actions receiver, reliability checks, self-test |
| `di/` | shell | app bindings for :core-index seams, contacts, `IndexControl`, DataStore |
| `ui/inbox`, `ui/conversation`, `ui/search`, `ui/bin`, `ui/passbook`, `ui/automations`, `ui/backup`, `ui/blocked`, `automation/`, `backup/` | screens | currently placeholder stubs with the pinned signatures |

## Navigation (for screen authors)

Every screen is `@Composable fun XScreen(navigator: DakNavigator, modifier: Modifier = Modifier)` and gets its own
ViewModel via `hiltViewModel()`. Route arguments are read in the ViewModel from `SavedStateHandle` using the
`Routes.ARG_*` names (`conversationId`, `highlight`, `to`, `body`, `q`, `group`, `focus`, `accountId`); optional
args are `null` when absent. Arguments arrive URL-decoded.

```kotlin
navigator.navigate(Routes.conversation(conversationId, highlight = message.key.toString()))
navigator.navigate(Routes.search(q = "from:hdfc"))
navigator.openConversation(id)           // shorthand
navigator.openSetting(DakSettings.otpAutoDelete.key)  // long-press on a control -> its setting, highlighted
navigator.back()                         // never pops the last screen
navigator.toInbox(); navigator.replaceAll(route)
```

Conversation ids follow :core-index `ConversationIds` (`t:<threadId>`, `m:<mergeKey>`); notifications open
`Routes.conversation("t:<threadId>", highlight = "<kind>:<providerId>")`.

Media shared into Dak (ACTION_SEND with EXTRA_STREAM) cannot ride in the COMPOSE route: inject
`navigation.PendingShare` in the composer ViewModel and call `consume()` once to get the `Uri`s.
`Routes.COMPOSE` carries `to` (comma-separated recipients) and `body` from SENDTO/SEND/`sms:` links.

## Theme tokens

Wrap nothing yourself: `MainActivity` applies `DakTheme(prefs)` live from Settings. Never hard-code colours:

- `MaterialTheme.colorScheme.*` for standard roles (dynamic colour on 12+, designed teal palette below, high-contrast
  variants, AMOLED true-black surfaces).
- `DakTheme.colors` (`DakColors`): `bubbleIncoming/onBubbleIncoming`, `bubbleOutgoing/onBubbleOutgoing`,
  `bubbleFailed/onBubbleFailed`, `otpHighlight/onOtpHighlight`, `category(Category)`, `sim(slotIndex)`,
  `avatar(key)`, `financeCredit`, `financeDebit`, `warning`, `success`, `locked`, `focusHighlight`.
  Category/SIM/warning/success are `TonalColors(container, content, accent)`: `content` on `container` is AA text,
  `accent` is for small marks (dots, icons, chart strokes) on the plain surface.
- `DakTheme.typography.otpCode` (bold monospace, scaled by the OTP display-size setting), `.amount`.
- `DakTheme.state` (`isDark`, `isAmoled`, `isHighContrast`, `isDynamic`).

## Common composables (`ui/common`)

`DakTopAppBar(title, onBack?, scrollBehavior?, actions)`, `SimChip(sim, compact)`, `CategoryChip(category)`,
`categoryLabel(category)` / `categoryLabelRes`, `LockChip()`, `TokenChip(label, colors)`,
`Avatar(name, key, size, photoUri, isBusiness)`, `initialsOf(name)`, `EmptyState(icon, title, body, actionLabel,
onAction)`, `WarningBanner(title, body, actionLabel, onAction)`, `ReliabilityBanner(navigator)` (put it at the top of
the inbox: it renders nothing unless notifications can be delayed), `RelativeTimeFormatter` /
`rememberRelativeTimeFormatter()` / `relativeTime(epochMillis)` (auto-refreshing "5 min", "14:32", "Yesterday"...).

## Reading and writing settings

Inject `app.dak.settings.SettingsStore` (singleton `AppSettingsStore` over DataStore) and use the registry records:

```kotlin
val autoDelete: String = settings.get(DakSettings.otpAutoDelete)          // synchronous, cached
settings.observe(DakSettings.quickActions).collect { ... }                 // Flow
settings.set(DakSettings.roamingWarnings, false)
settings.export() / settings.import(json)                                   // for backup; includes Appearance
```

Values are served from an in-memory snapshot (loaded once from DataStore) and persisted asynchronously in order.
The registry has no Appearance group yet, so the app declares `AppearanceSettings` (`appearance.themeMode`,
`.amoled`, `.contrast`, `.dynamicColor`) with the registry's `SettingDef` type; they are exported/imported with the
rest and shown as the first Settings section. Non-setting app state (onboarding done, banner dismissals) lives in
`AppStateStore`. Tier gating: `def.isLocked(entitlements)`; the Settings screen greys locked rows and opens
`UpgradeSheet(feature)`.

## Notifications

`notifications.MessageNotifier` is contributed to :core-telephony's `Set<IncomingMessageHandler>` (priority 0, before
indexing). It classifies with `:classify`'s `ClassifierPipeline` directly (`NotificationClassifier`), then posts:
OTP (large bold code in a custom view, Copy code / Delete now / Mark read, in-call warning, silent channel for
consumed OTPs, timeout at auto-delete), personal (MessagingStyle, inline RemoteInput reply, Mark read), others by
category channel. Lock-screen visibility follows `notifications.lockScreenPrivacy`.

- When a thread is opened/read, call `MessageNotifier.cancelForThread(threadId)` (inject `MessageNotifier`; the
  conversation screen does this on resume). It also refreshes the category summaries.
- Channel ids are constants on `NotificationChannels` (`PERSONAL`, `OTP`, `TRANSACTIONS`, `PROMOTIONS`, `OTHER`,
  `SPAM`, `OTP_CONSUMED`, `FAILURES`, `AUTOMATION`, `MMS`, `SELF_TEST`, `RELIABILITY`); other modules may post to
  `FAILURES`/`MMS`/`AUTOMATION` (scheduled sends, automation results). Ids are stable: never rename one, because
  Android keys the user's sound/importance choices by id. Retired ids go in `ChannelCatalog.obsolete` (deleted once
  per `SCHEMA` bump). Defaults live in `ChannelCatalog`; after creation the system owns sound, vibration and
  importance and code never overrides them (no `setSilent`, no DND bypass; spam is created blocked).
- Multi-SIM: with more than one active SIM, message categories get per-SIM copies (`otp.sim2`, group
  "SIM 2 · Airtel", see `SimChannelIds`), created on the first notification for that SIM and seeded from the flat
  channel's current settings. Resolve a channel with `NotificationChannels.channelFor(baseId, subId, sims)`.
- Per-conversation channels: `ConversationChannels.enable(conversationId, title, addresses)` creates `conv:<id>`
  (conversation channel via `setConversationId` on API 30+) and a long-lived conversation shortcut
  (`ConversationChannels.shortcutIdFor(id)`, Person + LocusId); personal notifications always carry that shortcut
  id, so Android 11+ lists them under Conversations. `ui/notifications/CustomNotifications.open(...)` is the
  conversation-menu entry; `NotificationChannels.settingsIntent(context, channelId, shortcutId?)` opens a channel's
  system page. `Routes.NOTIFICATION_CHANNELS` lists groups/channels with live importance, custom conversation
  channels (remove), critical-blocked warnings and "Reset channels" (`NotificationChannels.resetAll`: recreates
  channels the user has not customised; customised ones keep their settings).
- Repeats (`RepeatCollapse`): identical personal text, or same-template (digits masked) informational/OTP text,
  from the same thread within 15 min updates the existing notification with "×N" (quietly, `setOnlyAlertOnce`)
  instead of stacking; a resent OTP replaces the previous one with the latest code (alerts again only if the code
  changed). Informational notifications keep up to 5 distinct lines (InboxStyle). State rides in the notification
  extras (no memory, no wakeups). `NotificationSummaries` posts an InboxStyle summary per category once more than 3
  are active.
- `ReliabilityChecker.check()` reports default-SMS role, POST_NOTIFICATIONS, app/channel blocking, battery
  optimisation and background restriction; `Routes.SELF_TEST` fixes them and runs a send-to-self SMS round trip.

## Index and policies

`di/AppBindingsModule` binds :core-index's `BinPolicy` (`SettingsBinPolicy`), `OtpPolicy` (`SettingsOtpPolicy`),
`ContactLookup` (`AndroidContactLookup`, also usable app-wide for names/photos). `di/IndexControl` starts
`IndexSync` once Dak is the default SMS app (app start and right after the role is granted), applies the
onboarding/Settings index schedule and runs "Rebuild index now".

## Screens

Written by app agent 2. Everything below lives in its own packages; strings are in
`res/values/strings_screens.xml` (all prefixed `scr_`), the camera FileProvider paths in `res/xml/dak_file_paths.xml`.

### `automation/` — Android side of `:automations`

- `AutomationModule` binds the executor interfaces: `SmsForwarder` → `AndroidSmsForwarder` (MessageSender, E.164 via
  NumberNormalizer when `simsSending.numberNormalization` is on, over-limit forwards are scheduled for the next free
  slot), `ReplyScheduler` → `ScheduledSendScheduler`, `Labeler` → `UserLabels`, `AuditSink` → `IndexAuditSink`
  (index audit log, actor `rule:<name>`, action `automation.<Type>`), `ActionRegistry` → `DefaultActionRegistry`,
  and contributes `AutomationRunner` to `Set<IncomingMessageHandler>` (priority 200, after notify 0 / index 100).
- `AutomationRunner` builds a `MessageEvent` from the indexed `MessageItem` (category, OTP, transaction, merge key,
  SIM slot), runs `RuleEngine.evaluate` on `RuleRepository.enabledRules()` and executes each `PlannedAction` with a
  per-rule `ActionContext` (Notifier renders templates; IntentLauncher posts a tap-to-open notification because
  background activity starts are blocked; Archiver/Binner go through `ConversationRepository.setMessageArchived` /
  `RecycleBin.moveToBin(DeletedBy.AutoRule(name))` and register an undo with `AutomationUndoCenter`, which the inbox
  shows as a snackbar). Forward/relay actions of OTP-capable rules run only when `OtpForwardConfirmations` holds a
  biometric confirmation for the rule's current recipients; otherwise they are skipped and audit-logged.
- Scheduled sends: `ScheduledSendScheduler.schedule(addresses, body, subId, atMillis, conversationId?, ruleId?)`
  records in `ScheduledSendStore` and arms an `AlarmManager` alarm (exact when allowed on 12+) to
  `ScheduledSendReceiver` plus a WorkManager `ScheduledSendWorker` safety net; `ScheduledSendExecutor.runDue()` is
  idempotent and mutex-guarded and spreads sends with `SendThrottle` (`SendRateLimiter.planSends`, 30 / 30 min).
- `RuleRepository` decodes/encodes `AutomationStore` JSON with `RuleCodec`; `disableExpired(now)`.
- Auto-forwarding (free, on-device from the user's SIM): forwarding rules are ordinary rules built from
  `:automations`' `ForwardingSpec` (`meta.kind = forwarding`). `AutomationRunner` disables expired rules lazily
  before evaluation (`DailyHousekeeping.expire`), and labels a message forwarded by a forwarding rule
  "Fwd → <recipient>" (`UserLabels`) in addition to the audit-log "Forwarded to …" marker.
  `ForwardingStatusNotifier` keeps a silent ongoing notification (own low-importance channel `forwarding_status`,
  group `app`) while any forwarding rule is active or scheduled; `refresh()` after every change.
- `DailyHousekeeping.runIfDue()` (at most once per ~20 h): expire rules, refresh the forwarding notification,
  re-scan contacts for birthdays. Runs from the daily `dak-maintenance` job (`AutomationMaintenanceTask`, order
  110), an incoming SMS, or a scheduled-send run — no wakeup of its own.
- `ScheduledSendReceiver`: runs due sends inline and enqueues the retry job only if that fails; on boot / clock
  changes it re-arms only when `ScheduledSendScheduler.mightHavePending()` and re-posts the forwarding
  notification only when `ForwardingStatusNotifier.wasShowing()` (SharedPreferences flags, no DB open otherwise).
  Also handles the birthday prompt's `ACTION_BIRTHDAY_SEND` / `ACTION_BIRTHDAY_SKIP`.

### `birthdays/` — birthday wishes

- `ContactOccasionReader.read(includeAnniversaries)` — `CommonDataKinds.Event` birthdays/anniversaries (+ numbers,
  given names, photo) with READ_CONTACTS, parsed by `:automations`' `BirthdayDates`; read on demand only (no
  contacts observer).
- `BirthdayStore` — global `BirthdaySettings` (opt-in, off by default; `ASK` / `AUTO`; send time, default 09:00;
  SIM; templates), per-contact `OccasionConfig` (auto-send toggle, number, own template, the one pending send),
  and the "wished" ledger (dedupe key contactId + kind + year).
- `BirthdayScheduler.reconcile(occasions?)` / `syncFromContacts()` — keeps exactly one pending scheduled send (the
  next occurrence) per enabled contact via `ScheduledSendScheduler`, tagged with a `WishTag` in `ruleId`.
- `BirthdaySendGate` — hook in `ScheduledSendExecutor`: dedupe, "Ask me first" (posts `BirthdayNotifications`
  Send / Edit / Skip on the automation channel instead of sending), next-year rescheduling after a send.

### `backup/`

- `SafBackupTarget(context, treeUri)` — `BackupTarget` over an `ACTION_OPEN_DOCUMENT_TREE` folder (local, SD, or the
  Google Drive / Dropbox apps' document providers — the user's own cloud, no credentials held by Dak).
- `BackupManager` — `backupNow()` (encrypted with the Keystore-wrapped passphrase from `BackupPassphraseVault`,
  incremental with a full snapshot every 15th run; returns the recovery code once after each full backup),
  `restore(RestoreKey)` (additive `ProviderWriter.restore`, settings re-imported, then
  `IndexMaintenance.requestReindex(RESTORE)`), `export(uri, DAK | SMS_BACKUP_RESTORE_XML)`, `import(uri)` (SMS Backup &
  Restore XML, Fossify, SMS Organizer via `ImportDetector`, deduped by kind+address+date+body hash). `start*` variants
  run on the application scope; `operation: StateFlow<BackupOperation>` reports progress.
- `ProviderSnapshot` reads the provider (never the index) into `MessageRecord`s; `BackupStateStore` keeps the folder,
  previous manifest/digest and last outcome; `BackupWorker` (`@HiltWorker`, daily, enqueued by
  `BackupScheduler.ensureScheduled()` when a folder is chosen) honours `backupData.schedule` (daily/weekly/manual).

### UI packages

All screens keep the pinned signatures and get their ViewModel via `hiltViewModel()`.

- `ui/inbox` — tabs (All, Personal, Transactions, OTP, Promotions, Spam, Starred, Archived), SIM filter, pinned saved
  searches as virtual folders, `ReliabilityBanner`, index progress row, swipe start→end archive / end→start delete to
  bin with undo (user-configurable: `DakSettings.swipeRight/swipeLeft`, read via `ui/ux/UxPrefsViewModel`), long-press
  multi-select with a bottom action bar (archive, delete, read, pin, mute, fold together, select all), inline "Copy
  code" chip on fresh (< 10 min) OTP rows, and a bottom bar (places sheet, search pill, compose FAB) instead of top
  search/overflow. See `docs/ux-review.md`.
- `ui/conversation` — `ConversationScreen` (Paging, token-themed bubbles, SIM chip per bubble, OTP highlight + tap to
  copy (sensitive clip) + delete now, group sender names, Coil images, MMS download failed → tap to retry, send status
  with retry, link safety dialog via `LookalikeDomainChecker`, highlight + scroll-to from search with "Back to results",
  thread menu incl. reply SIM, bubble colour, text size, block, 1909 report via the composer, "Forwarded to …" from the
  audit log), `NewConversationScreen` (contacts search + raw numbers, shared media from `PendingShare`), and the shared
  composer (`Composer`, `ComposerDelegate`, `MessageSendController` for the SMS/MMS decision and E.164,
  `MmsMediaCompressor` to the carrier MMS limit, default 300 KB, off the main thread). `annotateMessage` and
  `copyToClipboard` are reused by search/backup. Gestures: swipe a bubble to reply (quote, OTP masked), double-tap to
  copy its code/amount (`QuickCopy`), long-press opens `MessageActionsSheet` (+ `MessageInfoDialog`), `JumpToLatest`
  FAB; the composer takes `enterToSend` (`DakSettings.enterToSend`) and a `FocusRequester`.
- `ui/search` — `SearchViewModel` keeps query text, sort and scroll in `SavedStateHandle`; chips from
  `SearchQuery.chips()`, filter sheet (edits the text via `withFilter`/`withoutFilter`), suggestions, saved searches
  (pin to inbox), AI search entry locked unless `Feature.AI_SEARCH`.
- `ui/bin` — bin list with `deletedBy`, restore, delete forever, empty; `AuthGate`/`rememberAuthGate()` (platform
  BiometricPrompt on 10+, keyguard confirm below; works from `ComponentActivity`) gates it when
  `backupData.binBiometricLock` is on.
- `ui/passbook` — accounts/cards/wallets with honest `BalanceState` ("unknown since …"), card outstanding + statement
  day, monthly totals, entries with ≈ indicative FX + rate/date, settled markup, raw SMS inline and one tap to thread.
- `ui/automations` — rules with toggles, simple editor (`RuleDraft` ↔ `Rule`), presets, premium actions locked,
  biometric confirmation for OTP forwarding, scheduled sends with cancel, exact-alarm prompt; shortcut rows to
  Auto-forwarding and Birthday wishes (forwarding rules open the Forwarding screen instead of the simple editor).
- `ui/forwarding` (`Routes.FORWARDING`) — "Forwarding rules": list with Active / Scheduled / Paused / Ended, editor
  (name, source senders picked from conversations incl. folded sender groups, optional categories + keyword,
  recipients from the contact picker or typed, SIM, inclusive start/end dates or "until I stop", template, OTPs
  excluded by default — including them needs the biometric check and shows a persistent warning).
- `ui/birthdays` (`Routes.BIRTHDAYS`) — READ_CONTACTS request, global opt-in, Ask first / Send automatically, send
  time, SIM, anniversaries toggle, default + per-contact templates (English/Hindi presets), next 60 days with
  per-contact "Auto-send" and number choice.
- `ui/backup` — folder picker, passphrase + recovery code dialog, backup now/schedule, exports, import, restore.
- `ui/blocked` — shared system block list via `BlockedNumbers`.

### Manifest entries these packages need (shell-owned manifest)

```xml
<!-- User-granted on 13+ (Automations shows the prompt); USE_EXACT_ALARM is reserved for alarm apps by Play policy. -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<!-- inside <application> -->
<receiver android:name=".automation.ScheduledSendReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.TIME_SET" />
        <action android:name="android.intent.action.TIMEZONE_CHANGED" />
    </intent-filter>
</receiver>
<provider android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.dakfiles" android:exported="false" android:grantUriPermissions="true">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/dak_file_paths" />
</provider>
```

Without them everything still compiles and degrades: scheduled sends fall back to WorkManager timing, the camera
falls back to `TakePicturePreview`, and location sharing reports "unavailable".

## Safety: fraud help and SMS cost warnings (`safety/`, `ui/fraud/`)

Strings in `res/values/strings_safety.xml` (prefix `safe_`).

- `safety.helplines.HelplineRepository.current(): HelplinePayload` — official helplines from
  `assets/helplines-v1.json` (identical to `shared/formats/helplines-v1.json`, enforced by `HelplineBundleTest`);
  `applyUpdate(json)` accepts only a signed copy with a higher `revision` (verifier is `RejectAllVerifier` until a
  signing key is provisioned). `HelplineBundle.parseBundled/parseSigned/canonicalPayload` are pure.
- `safety.helplines.UserHelplines` — user-entered numbers ("My bank's card-block number"), on device, always
  shown as unverified. Dak ships no bank numbers.
- `safety.FraudReport` — pure: `traiComplaintBody(text, sender, dateMillis, format)` (1909 complaint,
  `<text>,<sender>,<dd/MM/yy>` by default) and `detailsText(...)` for pasting into Chakshu / cybercrime.gov.in.
- `ui.fraud.FraudHelpScreen` (`Routes.fraudHelp(messageKey?)`): 1930 first, report flows for the message (1909 via
  the composer, Chakshu / cybercrime portal with details copied, block sender, copy details), quick-dial tiles
  (`ACTION_DIAL` only — no CALL_PHONE), source links, user bank numbers. Entry points: thread menu and bubble
  long-press "Report fraud", the link-warning dialog's "Report", and the static launcher shortcut
  (`res/xml/shortcuts.xml` per variant, action `IntentRoutes.ACTION_REPORT_FRAUD`).
- `safety.SendCostGuard` — Android wrapper over `:core-telephony`'s `DestinationCostClassifier`:
  `toConfirm(addresses, subId)` for interactive sends (the composer shows `CostWarningDialog` via
  `ComposerUi.costPrompt`, for send now and send later; "Don't ask again" is stored per number + SIM in
  `CostApprovals`), `allowUnattended(address(es), subId)` for automation forwards, rule-driven scheduled sends and
  notification quick replies (premium-rate refused unless approved). Governed by `simsSending.costWarnings`
  (default on); roaming prompts also need `simsSending.roamingWarnings`.
