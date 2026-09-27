# Release builds

How Dak's release APKs are built and hardened, and what to check before publishing one. Store-listing and policy
steps are in [play-submission.md](play-submission.md).

## Build types

| | Debug | Release |
|---|---|---|
| R8 (shrink, optimise, obfuscate) | Off | On (`isMinifyEnabled = true`) |
| Resource shrinking | Off | On (`isShrinkResources = true`) |
| Application id | `app.dak.debug` / `app.dak.premium.debug` | `app.dak` / `app.dak.premium` |
| Signing | Shared debug key (`android/app/debug.keystore`, committed on purpose so every CI/local debug APK installs over the previous one) | CI secrets (`DAK_KEYSTORE_*`); unsigned when they are absent |

Both are set in `android/app/build.gradle.kts`. Rules: `proguard-android-optimize.txt` (AGP default),
`android/app/proguard-rules.pro`, and each library's consumer rules.

## Versioning

`versionCode` and `versionName` are literals in `android/app/build.gradle.kts`: F-Droid builds from source and reads
them to detect releases (`UpdateCheckMode: Tags`), and the F-Droid and Play builds of one release must carry the same
`versionCode`. To release:

1. Bump both (`versionCode` by one; `versionName` in semver) in one commit, and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (≤ 500 characters; shown by F-Droid and pasted into
   Play's release notes).
2. Tag that commit `v<versionName>` and push the tag. CI fails the tag if it does not match `versionName` or the
   changelog is missing, and otherwise attaches `dak-<versionName>-free-release.apk` (and the premium and debug
   APKs) to the GitHub release; the Play bundle is the `dak-play-bundle-<run>` artifact.
3. Upload the AAB to Play (same `versionCode`). F-Droid picks the tag up by itself.

Debug and untagged builds keep the same version; their APK file names carry the CI run number instead.

## CI checks (`.github/workflows/android.yml`)

1. **Unsigned release builds of both flavours** (`assembleFreeRelease assemblePremiumRelease`) on every push and
   pull request. R8 runs here, so a missing class, a broken keep rule or a resource-shrinker error fails the job.
2. **`android/scripts/check-release-mapping.sh freeRelease premiumRelease`**: fails when there is no R8 mapping
   (minify switched off), when no `app.dak` class was renamed (obfuscation did not run), or when a class Dak
   persists by name was renamed (see "Names that must survive R8").
3. **R8 mapping upload**: `app/build/outputs/mapping/*/mapping.txt` goes into the `dak-r8-mapping-<run>` artifact.
   **Keep the mapping of every published build** (attach it to the GitHub release, or upload it to Play Console as
   the deobfuscation file). Without it, stack traces from that build cannot be retraced:
   `retrace mapping.txt stacktrace.txt` (from the Android SDK command-line tools).

There is no instrumented test on the release APK in CI (no emulator there). Before a release, install the signed
release APK on a phone and run the P0 items of [device-test-plan.md](device-test-plan.md): receive SMS, send SMS,
receive and send MMS, scheduled send, backup and restore, app lock. Those paths cover the reflection-sensitive
code (Hilt workers, Room, kotlinx.serialization, SQLCipher JNI).

## Keep-rule audit

Checked with grep over all modules (`Class.forName`, `getDeclared*`, `::class.simpleName`, `javaClass.name`,
`serializer()`, `getResourceAsStream`, `getIdentifier`, `ServiceLoader`, `valueOf`, `System.loadLibrary`,
`Parcelable`, `java.io.Serializable`). Findings:

| Area | Needs a Dak rule? | Why |
|---|---|---|
| kotlinx.serialization | No | serialization-core ships R8 rules (Companion, `serializer()`, `$$serializer`, annotations for sealed/polymorphic lookup). Dak mostly calls `X.serializer()` directly. Serial names are compile-time strings (`@SerialName` or the original FQN), so obfuscation never changes JSON on disk or in backups. |
| Hilt / Dagger, `@HiltWorker` | No (plus an explicit worker rule) | Generated code is referenced statically; the libraries ship rules. Worker names: see below. |
| Room (`core-index`) | No | room-runtime keeps `RoomDatabase` subclasses and the generated `_Impl` that `Room.databaseBuilder` finds by name. Column names are strings in generated code. |
| SQLCipher | No | `net.zetetic:sqlcipher-android` ships `-keep class net.zetetic.database.** { *; }` and keeps native methods (checked in the 4.19.0 AAR). |
| WorkManager workers | **Yes** | WorkManager stores the worker class name in its database, and `HiltWorkerFactory` looks workers up by class name. `-keepnames class * extends androidx.work.ListenableWorker` keeps work enqueued by one release valid after an update. |
| Automation action kinds | **Yes** | `RunHistory.kindOf`, `ActionRegistry`, `AutomationRunLog` and `RuleValidator` persist `ActionSpec::class.simpleName` ("ForwardSms", "Webhook", ...) in run-history and audit rows, and the rule list displays it. `-keepnames` on `app.dak.automations.rule.ActionSpec` and its nested classes, plus `-keepattributes InnerClasses,EnclosingMethod` (`getSimpleName()` of a nested class reads that attribute). |
| Enums stored by name (`InstrumentType`, `Category`, `ScheduledSendStatus`, settings values) | Explicit rule | `name()` returns the original constant name (R8 keeps the string passed to the enum constructor). `valueOf()` reaches `values()` reflectively, which the default optimize file keeps for all enums; `proguard-rules.pro` repeats it for `app.dak.**` so persisted values never depend on the default file. |
| Manifest components (activities, receivers, services, providers) | No | AAPT2 generates keep rules from the merged manifest. |
| Bundled data (`classify` templates and model weights, libphonenumber metadata) | No | Loaded with `getResourceAsStream` on absolute paths; R8 keeps Java resources and does not rename them. |
| Android resources looked up by name | No | No `getIdentifier` calls, so resource shrinking only removes unreferenced resources. |
| Log tags and `javaClass.simpleName` in logs | No | Obfuscated in logs; retrace with the mapping. |

Crash traces keep file and line information (`-keepattributes SourceFile,LineNumberTable`, source file names
replaced by `SourceFile`).

**When adding code**, add a rule (and a line to `check-release-mapping.sh` if it is a class name) for anything that
stores or compares a class name, finds a class or member by string, or is called from native code. Prefer stable
string constants over class names for anything persisted.

## Network security config

`android/app/src/main/res/xml/network_security_config.xml`, referenced from the application element of the app
manifest:

- **Cleartext is refused for every host.** Dak makes no HTTP requests of its own: MMS goes through
  `SmsManager.sendMultimediaMessage` / `downloadMultimediaMessage`, and the platform `MmsService` does the HTTP
  exchange with the carrier MMSC in the phone process, under the platform's own network security config. Carrier
  MMSCs on plain `http://` (common) are unaffected, so Dak needs no cleartext exception. Checked by grep: there is
  no `HttpURLConnection`, OkHttp, Ktor or socket use anywhere in the modules (including `src/premium`), and
  `scripts/check-offline-baseline.sh` enforces this for the free tier. If Dak ever does its own MMSC HTTP (for
  example a fallback for OEMs whose MmsService is broken), MMSC hosts come from carrier config and cannot be listed
  in advance; that code would need cleartext for those requests only, which the config cannot express per request,
  so it would have to use a separate process or be decided again.
- **Only system CAs are trusted** (no user-installed CAs), in release and debug. To inspect premium traffic with a
  proxy during development, add a `<debug-overrides>` block with `<certificates src="user" />` in a local change
  and do not commit it.
- **No pins yet.** The file contains a commented `domain-config` / `pin-set` template for premium servers: pin the
  SPKI SHA-256 of the issuing CA plus a backup key, and set an `expiration` so old installs fall back to normal CA
  validation instead of losing premium features when keys rotate.

## Before publishing

- [ ] `versionCode` / `versionName` bumped and the changelog added (see "Versioning").
- [ ] CI green on the tag, including the release smoke check.
- [ ] Mapping files from that run kept with the release (and uploaded to Play Console).
- [ ] Signed release APK installed on a phone; device-test-plan P0 items pass.
- [ ] Upgrade test: install the previous release, schedule a send and a backup, then install the new release over it;
      both still run (checks that persisted worker and class names survived R8).
