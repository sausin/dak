# Google Play submission checklist

What to file in the Play Console for Dak, and the evidence for each answer in the code. Policy text changes often:
re-read the current Play Console Help Center pages (SMS and Call Log permissions, User Data, Data safety, Permissions
and APIs that access sensitive information, Foreground service types, Target API level) before each submission and
update this file. Items marked **verify** are our reading and must be checked against the live text.

Related: [privacy policy](privacy-policy.md), [privacy compliance (DPDP / GDPR)](privacy-compliance.md),
[standards compliance §11](standards-compliance.md#11-google-play-sms-and-call-log-permissions-policy-data-safety).

## 1. Before you start

- [ ] **Hosted privacy policy URL.** Publish `docs/privacy-policy.md` and put the URL in the store listing *and* in
      `HOSTED_PRIVACY_POLICY_URL` (`android/app/src/main/kotlin/app/dak/ui/privacy/PrivacyScreens.kt`), replacing the
      `https://dak.example/privacy` placeholder. Replace the `privacy@dak.example` contact in the policy too.
- [ ] Keep `android/app/src/main/assets/privacy-policy.md` identical to `docs/privacy-policy.md` (a unit test,
      `PrivacyPolicyTest`, fails when they differ).
- [ ] In-app policy reachable in two taps: Settings → Privacy → Privacy policy (also from the first onboarding
      screen, and as a searchable row under Settings → Backup, data and privacy). It is bundled, so it works offline.
- [x] **Target API level.** `app/build.gradle.kts` sets `targetSdk = 36` (Android 16), meeting Play's 31 August 2026
      requirement. Predictive back was already opted in (`enableOnBackInvokedCallback`) and no activity locks
      orientation or resizability, so Android 16's large-screen and back changes need no code change. Before each
      submission re-test on an Android 16 device: edge-to-edge insets, predictive back on every screen, foreground-
      service start (MMS/journal replay), exact alarms for scheduled sends, and the flash-SMS notification.

## 2. SMS and Call Log permissions declaration

Dak is a **default SMS handler**; that is the core functionality and the only reason it asks for SMS permissions.

| Question | Answer | Evidence |
|---|---|---|
| Core functionality | Default SMS handler (read, send, receive and store SMS/MMS as the user's messaging app) | Role requested in onboarding (`ui/onboarding/SystemHelpers.kt` `SmsRole.requestIntent`, `OnboardingScreen.kt` DEFAULT_APP step) |
| Permissions declared | `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` (from `core-telephony/src/main/AndroidManifest.xml`). No Call Log permissions. | Merged manifest |
| Requested only after the role | Yes: the runtime permission step runs only after the role dialog returns | Onboarding order (`OnboardingScreen.kt` KDoc steps 1–3); standards-compliance §11 row 1 |
| Works as the default handler | `SMS_DELIVER`, `WAP_PUSH_DELIVER`, `RESPOND_VIA_MESSAGE` components and the `SENDTO` `sms:`/`smsto:`/`mms:`/`mmsto:` filters | `core-telephony` manifest, `app` manifest |
| SMS data used for anything else? | No ads, no analytics, no sale. Content leaves the phone only through the user's own sends, or through the opt-in features in §4, each behind a prominent disclosure | `docs/privacy-policy.md`; `premium-api/.../consent/` |

Evidence to attach:

- [ ] Video (≤ 2 min): fresh install → welcome screen (policy link visible) → "Set as default" system dialog →
      permissions step → inbox receiving an SMS; then Settings → Privacy.
- [ ] Reviewer instructions: "Open the app and accept the default SMS app prompt. No account or login is needed."

## 3. Battery optimisation (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)

**Decision: removed.** The permission is Play-restricted to apps whose core function breaks under Doze and that
cannot use FCM or other mechanisms. A default SMS app does not qualify: the system delivers `SMS_DELIVER` and
`WAP_PUSH_DELIVER` to it as high-priority broadcasts even in Doze, and scheduled sends use exact alarms
(`SCHEDULE_EXACT_ALARM`), which are also allowed to fire in Doze.

- The manifest no longer requests it, and marks it `tools:node="remove"` so a library cannot merge it back
  (`app/src/main/AndroidManifest.xml`).
- "Open battery settings" (onboarding reliability step and the self-test fix) now opens
  `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the list), with an explanation that the user should find
  Dak and choose "Don't optimise" / "Unrestricted"; app details is the fallback when an OEM removed the list
  (`ui/onboarding/SystemHelpers.kt` `BatteryOptimization.settingsIntent`, `OnboardingScreen.kt`,
  `ui/selftest/ReliabilityFixer.kt`). `isIgnoringBatteryOptimizations` still drives the "Done" state and the
  reliability banner; reading it needs no permission.
- OEM background-killer guidance (Xiaomi, Oppo/Realme/OnePlus, Vivo, Samsung, Huawei/Honor) is unchanged.
- Revisit only if device testing shows missed or late SMS that the settings path cannot fix; the justification would
  then have to be filed in the Permissions Declaration Form.

## 4. Prominent disclosure and consent (User Data policy)

Every path by which Dak itself could move message content or metadata off the phone was traced in the code:

| Path | Data | Where | State in this build | Consent gate |
|---|---|---|---|---|
| Jev cloud classification (`classify/.../CloudClassifier`, `ClassifierPipeline.cloudStage`) | Sender ID (DLT header, or the merge key, which can be a person's number) + `Masker`-masked body | Dak classification service | No implementation bound anywhere (`@BindsOptionalOf`, falls back to `NoCloudClassifier`) | `DataFlow.CLOUD_CLASSIFICATION`: disclosure before the Jev switch turns on (`SettingsViewModel`, Settings → Privacy); `ConsentGatedCloudClassifier` must wrap any real binding |
| Webhooks (`automations/.../ActionRegistry` `ActionSpec.Webhook` → `PremiumGateway.sendWebhook`) | messageKey, sender, rendered template (default: full text) | User's URL (possibly via Dak's gateway) | Gateway is `NoOpPremiumGateway` in both flavours | `DataFlow.WEBHOOKS`; premium `TierModule` wraps the gateway in `ConsentGatedPremiumGateway` |
| Web relay / relay-rule webhook channel (`relayCiphertext`) | Rendered text, pairing id | Dak relay | NoOp | `DataFlow.WEB_RELAY`, same gateway wrapper |
| AI search (`QueryUnderstanding`) | Typed query only | LLM service | NoOp | `DataFlow.AI_SEARCH`; premium wraps in `ConsentGatedQueryUnderstanding` |
| SMS forward / relay-rule SMS / auto-reply / scheduled reply | Message text | Recipient chosen by the user, via carrier | Active | User-directed; OTP forwarding needs biometric confirmation, unattended sends need app lock and raise a "Was this you?" alert, every send is in the run history (existing). Not a Dak data transfer |
| WhatsApp one-tap, share, launch-intent rules | Message text | The app the user picked; user completes the action | Active | User-initiated |
| 1909 complaint | Message, sender, date | TRAI via SMS, reviewed in the composer | Active | User-initiated |
| Encrypted backup to a SAF folder (Drive, Dropbox, local) | Messages, settings, run history (AES-256-GCM, passphrase-derived key) | User-chosen storage | Active | User-initiated; the storage provider only sees ciphertext and Dak receives nothing. No extra disclosure; the Backup screen explains the destination |
| Open export / "Export my Dak data" | Plain data | User-chosen file | Active | User-initiated; the export dialog warns it is unencrypted |
| MMS send/download | MMS PDUs | Carrier MMSC | Active | Core function |
| Crash reporting / analytics | – | – | None in the dependency graph | – |

The disclosure (`ui/privacy/DisclosureDialog.kt`, text in `premium-api/.../consent/Disclosures.kt`) is full screen,
separate from the policy, and states what is sent, what is not, who receives it, why, when, retention and how to turn
it off; "Allow" requires ticking "I am 18 or older"; "Not now" keeps it off. Each decision is stored with timestamp,
disclosure version and a SHA-256 of the exact text (`ConsentLedger`, file `no_backup/consent-records.json`).
Withdrawal is in Settings → Privacy → Data that leaves your phone and takes effect on the next call. Defaults are off.

Screenshots to capture for the declaration / review (light theme, English):

- [ ] Onboarding welcome with "Read the privacy policy".
- [ ] Settings root with the "Privacy" row; Settings → Privacy hub.
- [ ] Privacy policy screen (top and the "What leaves your phone" section).
- [ ] Jev disclosure: Settings → Categories and spam → Advanced → Jev cloud classification (shows the disclosure).
- [ ] Data that leaves your phone: all four flows off; then Jev allowed; the withdraw confirmation; consent history.
- [ ] Webhooks / relay / AI search disclosures (premium build with entitlements).
- [ ] Export my Dak data warning dialog; Delete my Dak data screen.
- [ ] Battery step in onboarding showing the settings-list explanation.

## 5. Data safety form

Definitions (verify): *collected* = transmitted off the device by the app to the developer or anyone else;
*shared* = transferred to a third party. Exempt from "shared": transfers the user initiates and reasonably expects
(for example sending an SMS or sharing to another app), and transfers to service providers processing on our behalf.
On-device processing alone is not collection.

### Free flavour (this build)

| Question | Answer | Why |
|---|---|---|
| Does the app collect or share any required user data types? | **No** | No networking code outside MMS (`scripts/check-offline-baseline.sh`); every server seam binds a no-op; SMS/MMS sends are user-initiated carrier transfers; backups/exports go only where the user chooses and Dak never receives them |
| Is all user data encrypted in transit? | Not applicable (nothing collected) | |
| Do you provide a way to request deletion? | Yes: Settings → Privacy → Delete my Dak data (local); nothing is held by us | `DakDataEraser` |
| Independent security review | No | |

If Play requires an answer per type even when nothing is collected, every type is "Not collected".

### Premium flavour (once server features ship; answers depend on the final implementation)

| Data type (Play category) | Collected? | Shared? | Ephemeral? | Required or optional | Purpose | Encrypted in transit | Deletable |
|---|---|---|---|---|---|---|---|
| Messages → SMS or MMS (Jev: masked body + sender ID) | Yes | No (service provider only) | Yes | Optional (consent) | App functionality | Yes (TLS) | Nothing stored; withdrawal stops it |
| Messages → SMS or MMS (web relay) | **verify**: E2E-encrypted ciphertext through our relay. Declare as collected unless Play's E2E guidance says otherwise | No | Yes (≤ 7 days if undelivered) | Optional | App functionality | Yes (TLS + E2E) | Deleted on delivery / TTL |
| Messages → SMS or MMS (webhooks) | Yes if posted through our gateway; no if the phone posts directly to the user's URL | No (user-directed to their own endpoint) | Yes | Optional | App functionality | Yes (TLS required for webhook URLs, **enforce**) | Nothing stored by us |
| App activity → In-app search history (AI search query) | Yes | No (service provider) | Yes | Optional | App functionality | Yes | Nothing stored |
| Device or other IDs (push token for relay, if FCM is used) | Yes | No | No | Optional | App functionality | Yes | Deleted on unpairing |
| Financial info → Purchase history (Play Billing) | **verify**: only if purchase tokens are sent to our server for verification | No | No | Optional | App functionality, fraud prevention | Yes | On request |
| Contacts, location, photos, files, audio, calendar, health, personal info | No | No | – | – | – | – | – |
| Crash logs, diagnostics, analytics | No | No | – | – | – | – | – |

Before submitting premium: re-derive this table from the real gateway, relay and billing code, and record in the
consent disclosures (`Disclosures.kt`) any difference (bump the version: users are asked again).

## 6. Other declarations

- [ ] **Foreground service types (targetSdk ≥ 34).** `FOREGROUND_SERVICE_DATA_SYNC` is declared by `core-telephony`
      (MMS transfer and broadcasts). File the foreground-service declaration with a short video; verify `dataSync` is
      still the right type (and its runtime limits on Android 15+).
- [ ] **Exact alarms.** `SCHEDULE_EXACT_ALARM` (user-granted special access, used for scheduled sends). Do not
      declare `USE_EXACT_ALARM` (restricted to alarm-clock and calendar apps).
- [ ] **Package visibility.** `<queries>` for launcher apps and https browsers (consumed-OTP detection); not
      `QUERY_ALL_PACKAGES`. Be ready to justify it (standards-compliance §11).
- [ ] **Location.** `ACCESS_COARSE_LOCATION`, requested only when the user taps "share location" in the composer;
      foreground only.
- [ ] **Content rating, target audience.** Not designed for children; target audience 18+ is the simplest answer for
      a banking-OTP-centric app with optional server features (the disclosures ask for 18+).
- [ ] **Account deletion requirement.** Dak has no accounts in the free build. If premium introduces accounts, add
      in-app account deletion and a web deletion link to the listing.
