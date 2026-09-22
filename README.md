# Dak

The messages that matter — OTPs, bank alerts, tickets — organised, backed up, and never uploaded
without your say-so. An Android default-SMS app for people SMS Organizer left behind.

Product spec and roadmap: [`docs/build-plan.md`](docs/build-plan.md).

## Repository layout

| Path | What | Status |
| --- | --- | --- |
| [`android/`](android/) | The app: Kotlin + Jetpack Compose, Gradle multi-module, `free` / `premium` flavours | Active |
| [`ios/`](ios/) | Future `ILMessageFilterExtension` + companion (no SMS API on iOS; no parity promise) | Placeholder |
| [`web/`](web/) | Future premium web/desktop client (TypeScript) over the ciphertext relay | Placeholder |
| [`shared/formats/`](shared/formats/) | Platform-neutral formats: open export format, template bundle, sender identity | Specs |
| [`docs/`](docs/) | Build plan, architecture notes, decisions | |

The relay server for premium lives in a separate repository (it only ever sees ciphertext).

## Android

```
android/
  core-model/        shared types (Message, Category, SimInfo, ExtractedTransaction…)   [JVM]
  premium-api/       tier seams: Entitlements, PremiumGateway, Translator, QueryUnderstanding [JVM]
  classify/          templates, DLT sender parsing, OTP extraction, on-device model, pipeline [JVM]
  finance/           transaction parser, ledger, balances, FX + reconciliation              [JVM]
  automations/       rule AST (versioned JSON), pure engine, Action registry, send limiter  [JVM]
  search/            Gmail-style query language → AST → FTS                                [JVM]
  backup/            open export format, E2E encryption, SMS Backup & Restore / Fossify    [JVM]
  settings-registry/ declarative settings registry + search                                 [JVM]
  mms-pdu/           clean-room MMS PDU codec                                               [JVM]
  core-telephony/    receivers, provider I/O, SMS/MMS send + download, SIMs                 [Android]
  core-index/        encrypted index (Room + SQLCipher), backfill, sync, recycle bin        [Android]
  app/               Compose UI; flavours `free` and `premium` differ only in Hilt bindings  [Android]
```

JVM modules have no Android dependency, so their logic is unit-tested fast and can be reused by
future platforms.

### Build

Requires JDK 17 and the Android SDK (compileSdk 35).

```sh
cd android
./gradlew test                                  # all unit tests
./gradlew assembleFreeDebug assemblePremiumDebug
```

Without the Android SDK, the JVM modules can still be tested: `android/scripts/jvm-test.sh`.

### CI

`.github/workflows/android.yml` runs tests and builds debug APKs for both flavours on every push
and pull request; the APKs are uploaded as workflow artifacts (`dak-debug-apks-<run>`). Tags `v*`
attach APKs to a GitHub release. Signed release APKs are built when these repository secrets exist:
`DAK_KEYSTORE_BASE64`, `DAK_KEYSTORE_PASSWORD`, `DAK_KEY_ALIAS`, `DAK_KEY_PASSWORD`.
Play Store `.aab` publishing is a later step.

## Open decisions (from the spec)

The package id `app.dak` and the name "Dak" (डाक, "post") are working choices; change them before
the first Play upload if needed. Other open decisions are listed at the end of the build plan.
