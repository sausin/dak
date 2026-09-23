# Privacy compliance: DPDP Act 2023 (India) and GDPR (EU/UK)

How Dak meets the Digital Personal Data Protection Act 2023 (and the DPDP Rules, 2025, which phase in over time:
check current commencement dates) and the GDPR, and what is still owed before premium servers exist. This is
engineering groundwork, not legal advice: have counsel review it before launch.

Related: [privacy policy](privacy-policy.md) (the user-facing notice), [Play submission](play-submission.md),
[standards compliance §13](standards-compliance.md#13-dpdp-act-2023-india-and-gdpr-eu).

**Legend.** ✅ done in the app · 🟡 partly done · 🔜 needed only once premium servers or accounts exist.

## 1. Where the law bites

Dak is local-first. Processing that happens only on the user's phone, by software the user controls, for the user's
own purposes, is largely outside what we as a company "process" (under GDPR Art. 2(2)(c) household processing by the
user; under DPDP we are not deciding purpose and means for data we never receive). We still build the user-facing
rights in, because (a) the Play User Data policy requires disclosure and consent anyway, (b) the moment a server
feature ships we become a Data Fiduciary / controller for that data, and (c) it is the product's promise.

Off-device flows where Dak is (or will be) the fiduciary/controller, all off by default:

| Flow (`DataFlow`) | Personal data | Recipient | Status |
|---|---|---|---|
| `CLOUD_CLASSIFICATION` (Jev) | Sender ID (can be a person's phone number) + masked message text | Dak classification service (processor possible) | 🔜 not implemented; consent gate ready |
| `WEBHOOKS` | Sender, message key, message text per rule template | User's own endpoint (Dak gateway possibly in between) | 🔜 |
| `WEB_RELAY` | E2E-encrypted messages, pairing id, sizes/times | Dak relay | 🔜 |
| `AI_SEARCH` | Typed search query | LLM provider (processor) | 🔜 |
| Play Billing entitlements | Purchase token / entitlement | Google (independent controller) + Dak if verified server-side | 🔜 |

## 2. Lawful basis and consent

| Requirement | Implementation | Where enforced | Status |
|---|---|---|---|
| DPDP s.6 / GDPR Art. 6(1)(a), 7: free, specific, informed, unconditional, unambiguous consent by clear affirmative action, per purpose | One consent per `DataFlow`; full-screen disclosure with "Allow" / "Not now"; nothing pre-ticked; the app works fully without any of them | `premium-api/.../consent/Disclosures.kt`, `ui/privacy/DisclosureDialog.kt`, `ui/settings/SettingsViewModel.kt` (`pendingDisclosure`) | ✅ |
| Notice before or with the request (DPDP s.5: data, purpose, how to exercise rights, how to complain to the Board) | Disclosure fields: what is sent / not sent, recipient, purpose, when, retention, how to turn off; link to the full policy, which covers rights and complaints | `Disclosures.kt`, `docs/privacy-policy.md` | ✅ (🔜 add the Board complaint route and grievance officer name to the policy once appointed) |
| Proof of consent (DPDP s.6(10); GDPR Art. 7(1)) | Append-only `ConsentRecord` per decision: flow, granted, timestamp, disclosure version, SHA-256 of the exact disclosure text, source screen; kept on the phone; exported with "Export my data" | `ConsentLedger` (`no_backup/consent-records.json`), `PrivacyViewModel`, `PersonalDataExporter` | ✅ locally; 🔜 servers must also log consent receipts (flow, version, hash, time) when a request first arrives |
| Changed purpose or text requires new consent | A consent counts only for the current disclosure version **and** text hash; any edit silently invalidates older consents and the feature stops until the user agrees again | `ConsentLedger.isGranted` (tested in `ConsentLedgerTest`) | ✅ |
| Withdrawal as easy as giving (DPDP s.6(4); GDPR Art. 7(3)) | Same place, one switch + confirm; effect is immediate because every gate reads the ledger per call; the Jev switch is also turned off | Settings → Privacy → Data that leaves your phone; `ConsentGatedPremiumGateway`, `ConsentGatedQueryUnderstanding`, `ConsentGatedCloudClassifier` | ✅ client; 🔜 server-side deletion on withdrawal |
| Consent is never restored or imported | Ledger lives in no-backup storage and is excluded from Dak backups; a restored settings file cannot turn Jev on without consent | `FileConsentStorage`, `BackupManager.restoreSettings`, `SettingsViewModel.init` | ✅ |
| Consent Managers (DPDP s.6(7)–(9)) | Not used | – | 🔜 evaluate once the registration framework is operational |
| Legitimate uses without consent (DPDP s.7) / other GDPR bases | None relied on for server processing | – | – |

## 3. Data principal / data subject rights

| Right | How | Status |
|---|---|---|
| Access / summary of data (DPDP s.11; GDPR Art. 15) and portability (GDPR Art. 20) | Settings → Privacy → **Export my Dak data**: unencrypted ZIP (`dak-personal-data` v1, documented in `android/backup/FORMAT.md`) with settings, sender groups, user labels, consent records, automation rules, automation run history, activity log, scheduled messages, saved searches and search history, accounts, ledger, account links and types, conversation settings, sender names/addresses, message flags, per-message categories and labels (no text), recycle bin. Messages themselves: Backup → Export (open Dak format or SMS Backup & Restore XML). Auth gate before export. | ✅ local; 🔜 server-side access request process for any server-held data |
| Correction and completion (DPDP s.12; GDPR Art. 16) | Everything user-created is editable in the app (rules, labels, accounts, settings, sender names) | ✅ |
| Erasure (DPDP s.12(3); GDPR Art. 17) | Settings → Privacy → **Delete my Dak data**: confirmation + app auth gate, cancels work and notifications, deletes Dak's Android Keystore keys, then `ActivityManager.clearApplicationUserData()` (manual wipe fallback). Does **not** delete the shared SMS/MMS store (other apps' data too; the screen says how to delete messages), backups in user folders, or the default-SMS role. | ✅ local (`DakDataEraser`); 🔜 server deletion endpoint + "delete my server data" |
| Grievance redressal (DPDP s.13) and nomination (s.14) | Contact placeholder in the policy | 🔜 appoint and publish grievance officer; response SLA (DPDP Rules); nomination process if accounts exist |
| Complaint to authority | Named in the policy (Data Protection Board of India; EU/UK DPAs) | ✅ text |
| Objection / restriction (GDPR Art. 18, 21) | Withdrawal switches cover every consent-based flow | ✅ |
| Automated decisions (GDPR Art. 22) | Classification only files messages; no legal or similarly significant effect. Scam warnings are advisory | ✅ note in DPIA |

## 4. Retention schedule

| Data | Where | Retention | Enforced by |
|---|---|---|---|
| SMS/MMS messages | System provider (shared) | Until the user deletes them | User; Dak never auto-deletes except the OTP lifecycle the user configures |
| Recycle bin | Index `bin_entry` | OTPs 1 day (premium: adjustable), others 30 days | `core-index` `BinPurgeTask` / `RecycleBin` (`DeletedBy.kt` `purgeAt`) |
| Activity log | Index `audit_log` | 90 days | `AuditLogRepository.trim` via daily maintenance (`BuiltInMaintenanceTasks.kt`) |
| Automation run history | Index `automation_run` | 366 days, and at least the newest 5,000 rows | `AutomationRunStore.trim` via `DailyHousekeeping` |
| Search history | Index `search_history` | Newest 50 | `SearchRepository` (`HISTORY_KEEP`) |
| Index, ledger, rules, settings, labels, scheduled sends | App-private storage (SQLCipher / DataStore / prefs) | Until the user deletes them, uses "Delete my Dak data", clears storage or uninstalls | `DakDataEraser`; uninstall |
| Consent records | `no_backup/consent-records.json` | Until "Delete my Dak data"; newest 500 (never the deciding record of a flow) | `ConsentLedger.MAX_RECORDS` |
| Incoming-SMS journal | App-private | Until the message is written to the provider | `core-telephony` `SmsJournal` |
| Encrypted backups | User-chosen folder | Until the user deletes them | User |
| Jev payloads (server) | – | Not stored: delete after scoring | 🔜 backend requirement + test |
| Relay blobs (server) | – | Delete on delivery; TTL ≤ 7 days | 🔜 backend requirement + test |
| AI search queries (server/processor) | – | Not stored; zero-retention processor terms | 🔜 contract + config |
| Webhook payloads through a Dak gateway (if any) | – | Not stored; delivery logs without bodies ≤ 30 days | 🔜 |
| Server logs | – | No message content or queries; IPs ≤ 30 days | 🔜 |

## 5. Security safeguards (DPDP s.8(5); GDPR Art. 32)

On device: SQLCipher index with a Keystore-wrapped key; Keystore-encrypted backup passphrase; AES-256-GCM backups
with PBKDF2 and a recovery code; app lock and sensitive-screen gate; `FLAG_SECURE` options; `allowBackup=false`;
no analytics/crash SDKs (CI check `scripts/check-offline-baseline.sh`); masked payload for Jev (`Masker`); consent
gates in front of every server seam. 🔜 Servers: TLS 1.2+, E2E for relay, least-privilege access, encryption at rest,
audit logging, key rotation, vendor due diligence.

**Finding to fix before the relay ships:** `ActionRegistry` passes the rendered plaintext
(`text.toByteArray()`) to `PremiumGateway.relayCiphertext` for `RelayToWebClient` and the relay-rule `WEBHOOK`
channel. The method name promises ciphertext; either the premium gateway implementation must encrypt with the
pairing key before anything leaves the phone, or the automations module must. Add a test that the bytes handed to
the network layer are not the plaintext.

**Finding for Jev:** `ClassifierPipeline.cloudStage` sends `dltHeader?.raw() ?: mergeKey`; for a person the merge key
is their phone number. The disclosure says so. Consider never calling the cloud stage for non-DLT senders (personal
messages are rarely ambiguous in a useful way), which would remove a third party's number from the flow.

## 6. Personal data breach runbook (outline)

Applies to any server component and to a vulnerability that exposes on-device data at scale (for example a bug that
leaks the index key).

1. **Detect and triage (hour 0).** Page the on-call owner; open an incident record (time detected, reporter, systems,
   data types, users/regions affected, whether data was encrypted and keys safe).
2. **Contain (hours 0–4).** Revoke credentials, disable the affected server feature (a kill switch that makes the
   gateway return false), rotate keys, preserve logs and evidence.
3. **Assess (hours 4–24).** Which `DataFlow`s, how many data principals, India/EU/UK split, likelihood of harm.
   E2E-encrypted relay data with uncompromised keys lowers the risk rating; masked Jev text is still personal data.
4. **Notify.**
   - DPDP: intimate the Data Protection Board and each affected Data Principal "without delay"; the DPDP Rules set a
     detailed report within 72 hours of becoming aware (**verify** against the notified Rules).
   - GDPR Art. 33: supervisory authority within 72 hours unless unlikely to result in a risk; Art. 34: data subjects
     without undue delay if high risk. UK GDPR: ICO within 72 hours.
   - CERT-In (India): cyber incidents within 6 hours of noticing (CERT-In Directions, April 2022).
   - Google Play: follow the Developer Program Policy for user data incidents if applicable.
   - Templates to prepare: Board intimation, DPA notification, user email/in-app notice (what happened, data, what we
     did, what users should do, contact).
5. **Remediate and review (within 30 days).** Root cause, fix, tests, update this document, the DPIA and the
   disclosures (bump the disclosure version if a flow changes).

Tabletop exercise before premium launch: 🔜.

## 7. DPIA outline (GDPR Art. 35; good practice under DPDP, mandatory if Dak is notified as a Significant Data Fiduciary)

Required before Jev or the relay launch, because message content (even masked) at scale, financial signals and
possibly vulnerable users are involved.

1. **Description:** each `DataFlow`: data items, sources, recipients/processors, regions, retention, volumes.
2. **Necessity and proportionality:** why on-device is not enough (Jev: low-confidence cases only; relay: user asked
   for desktop access); data minimisation (masking, query-only AI search, E2E relay); monthly caps; opt-in only.
3. **Risks to people:** re-identification from sender headers + masked templates; exposure of OTPs or bank data;
   third-party numbers (the sender is not our user); profiling of financial behaviour; children using the app;
   cross-border transfer; processor misuse.
4. **Measures:** masking, no storage, zero-retention processor contracts (GDPR Art. 28), E2E encryption, consent per
   purpose with versioned text, 18+ confirmation, regional hosting choice, access controls, breach runbook.
5. **Residual risk and sign-off;** consult the authority if high residual risk remains (GDPR Art. 36).
6. **Review** at every disclosure version bump.

## 8. Children

- DPDP s.9: no processing of a child's (under 18) data without verifiable parental consent; no tracking, behavioural
  monitoring or targeted advertising of children. GDPR Art. 8: 13–16 depending on the member state.
- Dak has no accounts and no age data. Every server-feature disclosure requires ticking **"I am 18 or older"**
  before "Allow" is enabled (`DisclosureDialog.kt`); on-device features need no age check. ✅ self-declaration.
- 🔜 If accounts are introduced, or if the Board's rules require more than self-declaration for these features,
  add verifiable parental consent or keep server features adult-only with stronger checks.
- Store listing: target audience 18+; not designed for children.

## 9. Cross-border transfer and processors 🔜

DPDP s.16 allows transfer except to countries the government restricts; GDPR Chapter V needs adequacy or SCCs. Pick
hosting regions per flow, list processors (LLM provider, cloud host, push provider) in the policy, and sign Art. 28 /
DPDP-compliant processor agreements with zero-retention terms.

## 10. What changed in the app for this document

- Consent ledger, disclosures and gates: `android/premium-api/src/main/kotlin/app/dak/premium/consent/`.
- Privacy area (policy, data sharing, consent history, export, delete): `android/app/src/main/kotlin/app/dak/ui/privacy/`.
- Export and erase use cases: `android/app/src/main/kotlin/app/dak/backup/PersonalDataExporter.kt`, `DakDataEraser.kt`.
- Automation run history in encrypted backups and restores: `android/backup` (`automation_runs.jsonl`,
  `AutomationRunRestore`), `app/.../backup/BackupManager.kt`.
