# Implementation status (Android)

What exists in `android/` against [the build plan](build-plan.md). "Built" means implemented and
compiling in CI; pure-Kotlin modules are also unit-tested. Nothing has been run on a device yet —
the Phase 0 exit criteria (Pixel + Xiaomi, 2 SIMs, OTP autofill, self-test with battery optimisation
on) still need a real device pass.

## Baseline principle: no runtime AI dependency

Every free feature runs on-device and offline: rule/template classification plus a bundled pure-Kotlin model,
local finance parsing, local scam heuristics, local search. The cloud classifier, AI search and translation are
interfaces with no-op defaults (`NoCloudClassifier`, `NoOpQueryUnderstanding`, `NoOpTranslator`) and nothing binds a
real implementation yet. `android/scripts/check-offline-baseline.sh` (run in CI) fails the build if shared/free code
references a network client or AI SDK, or binds a cloud classifier outside `src/premium`. The only network use is the
platform MMS download over the carrier APN.

## Phase 0 — spike

| Item | State |
| --- | --- |
| Default-SMS role request (RoleManager / ACTION_CHANGE_DEFAULT), RCS-loss disclosure first | Built (`app/ui/onboarding`) |
| `SMS_DELIVER` → verbatim provider write → handlers (notify 0, index 100, automations 200) | Built (`core-telephony`) |
| `WAP_PUSH_DELIVER` → notification-ind → download with backoff, per-SIM, visible failure reason | Built (`core-telephony`, `mms-pdu`) |
| Multi-SIM send/receive, reply SIM per conversation, SIM chips | Built |
| Compose thread list and thread view | Built |
| `PremiumGateway`, `Entitlements`, `Translator`, `QueryUnderstanding` no-ops per flavour | Built (`premium-api`, `app/src/{free,premium}`) |

## Phase 1 — MVP (free)

| Item | State |
| --- | --- |
| Encrypted index (Room + SQLCipher, Keystore-wrapped key), rebuildable from provider | Built (`core-index`) |
| Two-stage backfill; Now / When plugged in / Tonight choice at onboarding | Built |
| Template classifier + on-device model (pure-Kotlin Naive Bayes; TFLite/ONNX can replace it behind `MessageModel`) | Built (`classify`) |
| Personal / Transactions / OTP / Promotions / Spam tabs; sender merge groups | Built |
| Gmail-style search, chips, saved searches, preserved back stack | Built (`search`, `core-index`, `app/ui/search`) — FTS4 (Room) rather than FTS5 |
| One composer, attachments, automatic SMS→MMS, segment counter, group MMS | Built |
| Light / dark / AMOLED / high contrast, system default, live switch, semantic tokens | Built (`app/ui/theme`) |
| OTP notification (bold code, auto-copy with Copy fallback, mark read, delete), auto-delete, consumed-OTP detection | Built |
| Recycle bin with deletedBy, 1-day OTP retention (premium-adjustable) | Built |
| Blocking via shared `BlockedNumberContract` | Built |
| E.164 normalisation per SIM home country, roaming chip, "sent as" hint | Built |
| Settings registry with search, per-group Advanced, tier badging | Built (`settings-registry`, `app/ui/settings`) |
| E2E-encrypted backup to the user's own storage (SAF folder: Drive/Dropbox/local), open export | Built (`backup`, `app/backup`) |
| Importers: SMS Backup & Restore XML, Fossify, SMS Organizer | Built — SMS Organizer is heuristic until verified against a real backup |
| Reliability: self-test, restriction banner, OEM guidance, ContentObserver + periodic reconcile | Built |

## Phase 2 (pure logic pulled forward)

Finance ledger (parser, accounts/cards, honest balances, FX indicative values + settlement
reconciliation, passbook), automation engine (versioned JSON rules, on-device actions, scheduled
sends with rate spreading), link safety (lookalike/shortener checks), duplicate OTP collapse, and
the cloud-classifier seam (masked text only, off by default) are built. Not yet: OTA template
bundle fetching (signature verification exists), Safe Browsing lookups, crowd spam reports, TRAI
1909 flow beyond a forward, schedule-triggered rules.

**Broadcast lists** (built): one message to up to 50 people as individual SMS (replies come back 1:1), with hard caps
(50 per broadcast, 100 broadcast messages per rolling 24 h), batched pacing (10 per 10 min within Android's 30/30 min),
dedupe / blocked / own-number / premium-short-code-alphanumeric exclusion, `{firstName}` / `{name}` placeholders, an
on-device spam-risk check (English, Hindi, Hinglish) with an extra "they expect this message" confirmation, a
versioned first-use "Use broadcasts with care" sheet ([acceptable use](terms-acceptable-use.md), TRAI TCCCPR / 1909),
per-recipient ticks, retry and replies. One-shot only (optional single scheduled time); automations cannot start one.
Pure logic in `:automations` (`broadcast`), app side in `app/broadcast` + `ui/broadcast`; stored in SharedPreferences
(no schema change).

## Phase 3 (premium)

Only the seams and locked UI exist: Play Billing, translation, AI search, web client relay,
webhooks and send API are not implemented.

## Works worldwide (India-first launch)

Dak launches India-first; elsewhere it works with generic behaviour, and other regions get curated data later.
Nothing assumes India: a `RegionProfile` (`core-telephony/region`) is resolved from the default SMS SIM's home
country (per message: the SIM it arrived on), then the network country, then the device locale, and is
`UNKNOWN` (generic) when none is usable. India is simply the richest profile.

| Area | India (`IN`) | Everywhere else |
| --- | --- | --- |
| Sender rules | DLT headers parsed (`dlt-*` labels, registered-header trust in the scam detector); short codes from "banks" suspicious | No DLT handling; short codes are normal bank senders; generic signals only (unknown sender + credit wording, return/refund urgency, links, payment handles, PIN/collect bait) |
| Template bundle | Indian senders and the UPI/IMPS rule are tagged `"regions": ["IN"]` | Generic (untagged) rules only; region-matching rules win ties. Old bundles without `regions` stay valid (untagged = global) |
| OTPs | English + Hindi | English (US/UK/EU/UAE/SG-style messages tested), Arabic phrasing, any Unicode digits → ASCII code |
| Money | INR home currency, lakh/crore grouping | Home currency from the bank SMS (balance currency), else the SIM region's currency (`Currency.getInstance`), else the account's dominant currency; Western grouping; bare `$` = the region's dollar (USD/CAD/AUD/SGD/...), foreign amounts always show their ISO code |
| Report fraud | 1930, cybercrime.gov.in, Chakshu, TRAI 1909 complaint, RBI, 112 (verified bundle) | "Call your bank's fraud line" card, the region's general emergency numbers from libphonenumber data (e.g. 911, 999 + 112, 000, 112), and the user's own saved bank number. No invented national fraud lines; the 1909 menu entry is hidden |
| Dates / time zones | Search accepts `dd/MM/yyyy`; TRAI complaint uses its `dd/MM/yy` format | Search reads numeric dates in the locale's order (`MM/dd/yyyy` in the US, falling back when only the other order is valid); birthdays and report details use locale formats; schedules use `ZoneId.systemDefault()` |

Still India-specific (by design, pending data for other regions): the bundled sender/brand table and official-domain
list for link lookalikes, `InstitutionTable`, the scam detector's bank-name list and Hinglish wording, the helplines
bundle, and `SenderId.mergeKey`'s DLT-prefix collapse (applied everywhere; harmless outside India except for
header-shaped names such as `BT-MOBILE`).

## Conversation and Passbook extras

| Item | State |
| --- | --- |
| Tap the thread header: participant details, call / copy, view contact, save an unsaved number (new or existing contact) | Built (`ui/conversation/ContactDetailsSheet`) |
| Send later from the composer: "Schedule" in the tray or long-press Send (in an hour, tomorrow, any date and time); pending ones show above the composer with cancel | Built |
| Incognito chats per conversation (from when they are turned on): sent messages deleted once radio-confirmed, received ones after a 10 s reading window in the thread or on leaving it; notification and inbox previews never show the text; no recycle bin; dissolve animation. Only this phone's copy vanishes | Built (`app/incognito`, `OutgoingSentListener` in `core-telephony`, index v7 `incognitoSince`) |
| Birthdays & occasions: anniversaries and other contact dates ("Other" / custom label, one per contact) on one screen, one chip row; "Add a date" opens the contact in Contacts | Built |
| Remove an account from the Passbook (display only; ledger and scam detection keep it), "Hidden accounts" to restore | Built (index v7 `account_hidden`) |

## Known gaps / follow-ups

- Room schema JSON is generated in CI but not yet committed (`core-index/schemas`); commit it
  before the first schema change and write real migrations from then on.
- Recovery codes are per snapshot; `:backup` should accept one stable recovery secret so a single
  code unlocks the whole incremental chain.
- Appearance settings live in the app (8th settings section) rather than the registry's 7 groups.
- User labels from automations are stored app-side (no index API yet).
- Release builds have R8 disabled until keep rules are verified on a device.
- Play `.aab` publishing and release signing (CI already signs when keystore secrets are set).

## Runtime risks to check first on a device

- Battery: see [battery.md](battery.md). The index DB open is deferred to the first (background) query, the
  settings snapshot is preloaded on IO, the classifier JSON is parsed once per process and the app-hash table is
  persisted (built once). The notifier reads consumed-OTP
  attribution from that persisted table and posts muted conversations silently.
- Incognito: automations (forwarding rules) still see incoming messages of an incognito chat; decide whether an
  incognito chat should be exempt from forwarding.
- Navigation args are `Uri.decode`d twice in some ViewModels (a literal `%xx` gets mangled).
