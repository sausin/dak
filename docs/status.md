# Implementation status (Android)

What exists in `android/` against [the build plan](build-plan.md). "Built" means implemented and
compiling in CI; pure-Kotlin modules are also unit-tested. Nothing has been run on a device yet —
the Phase 0 exit criteria (Pixel + Xiaomi, 2 SIMs, OTP autofill, self-test with battery optimisation
on) still need a real device pass.

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
| OTP notification (bold code, copy, delete), auto-delete, consumed-OTP detection | Built |
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

## Phase 3 (premium)

Only the seams and locked UI exist: Play Billing, translation, AI search, web client relay,
webhooks and send API are not implemented.

## Known gaps / follow-ups

- Room schema JSON is generated in CI but not yet committed (`core-index/schemas`); commit it
  before the first schema change and write real migrations from then on.
- Recovery codes are per snapshot; `:backup` should accept one stable recovery secret so a single
  code unlocks the whole incremental chain.
- Bubbles show Sending / Sent / Failed; delivered state is not yet surfaced in `MessageItem`.
- Appearance settings live in the app (8th settings section) rather than the registry's 7 groups.
- User labels from automations are stored app-side (no index API yet).
- Release builds have R8 disabled until keep rules are verified on a device.
- Play `.aab` publishing and release signing (CI already signs when keystore secrets are set).

## Runtime risks to check first on a device

- Battery: see [battery.md](battery.md). The index DB open is deferred to the first (background) query, the
  settings snapshot is preloaded on IO, the classifier JSON is parsed once per process and the app-hash table is
  persisted (built once). Still open: `ConsumedOtpDetector` rebuilds its own hash table per process (see
  battery.md, "Deferred").
- Navigation args are `Uri.decode`d twice in some ViewModels (a literal `%xx` gets mangled).
